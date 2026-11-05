#!/usr/bin/env bash
# Asserts what a public APK must be (#27 PR 2, round-0 SEC-10), on the built or the signed file. Needs the Android build
# tools (aapt2) on PATH. Reads the manifest only; prints fixed lines and never the host in a failure message beyond what
# the caller passes in.
#   bash scripts/assert-release-apk.sh <apk> <expected host> <versionCode> <versionName>
set -euo pipefail

apk=${1:?usage: assert-release-apk.sh <apk> <host> <versionCode> <versionName>}
host=${2:?host}
code=${3:?versionCode}
name=${4:?versionName}
status=0
fail() { echo "FAIL: $*"; status=1; }

badging=$(aapt2 dump badging "$apk")
tree=$(aapt2 dump xmltree --file AndroidManifest.xml "$apk")

# Identity and version.
grep -qE "^package: name='app\.gifitup' versionCode='$code' versionName='$name'" <<<"$badging" \
  || fail "package, versionCode or versionName differ from app.gifitup / $code / $name"

# Not debuggable, no backup, no cleartext (the manifest's own hardening, read back from the built file).
if grep -q "application-debuggable" <<<"$badging"; then fail "the APK is debuggable"; fi
grep -qE 'android:debuggable.*=true|android:debuggable.*=0xffffffff' <<<"$tree" && fail "android:debuggable is set"
grep -qE 'A: http://schemas.android.com/apk/res/android:allowBackup\(0x[0-9a-f]+\)=false' <<<"$tree" || fail "allowBackup is not false"
grep -qE 'usesCleartextTraffic\(0x[0-9a-f]+\)=false' <<<"$tree" || fail "usesCleartextTraffic is not false"

# The one permission, and the invite link host: the build's host, never the placeholders.
# androidx adds one permission of its own, protected by the app's signature, that keeps other apps from sending
# broadcasts to the app's dynamic receivers; it is named after the package and cannot be granted to another app.
perms=$(grep -oE "^uses-permission: name='[^']+'" <<<"$badging" | sed -E "s/^uses-permission: name='(.*)'/\1/" | sort -u)
want=$(printf '%s\n' android.permission.INTERNET app.gifitup.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION | sort -u)
if [ "$perms" != "$want" ]; then
  fail "the APK's permissions are not exactly INTERNET plus androidx's signature permission; found: $(tr '\n' ' ' <<<"$perms")"
fi
grep -qF "host=\"$host\"" <<<"$tree" || grep -qE "host.*\"$host\"" <<<"$tree" || fail "the invite link host is not the expected host"
if grep -qE 'gifitup\.invalid|applinks-disabled\.invalid' <<<"$tree"; then fail "a placeholder host is in the manifest"; fi

# The host the app starts with is inside the code, and the placeholder origin is not. Strings in classes*.dex.
dex=$(mktemp -d)
trap 'rm -rf "$dex"' EXIT
unzip -q "$apk" 'classes*.dex' -d "$dex"
grep -qaF "https://$host" "$dex"/classes*.dex || fail "the default origin of the build is not in the app's code"
if grep -qaE 'gifitup\.invalid' "$dex"/classes*.dex; then fail "the placeholder origin is in the app's code"; fi
# No build-machine paths or file URLs in the code.
for needle in "$HOME" "file:///home" "file:///Users" "/runner/work"; do
  if grep -qaF -- "$needle" "$dex"/classes*.dex; then fail "build-machine path '$needle' in the app's code"; fi
done

# Only the app and its signature: no mapping, no symbol, no source-map files inside the APK.
if unzip -Z1 "$apk" | grep -qiE '\.(map|pdb|sym|debug)$|mapping\.txt$'; then fail "debug or mapping files inside the APK"; fi

[ "$status" -eq 0 ] && echo "The APK has the expected identity, version, host and hardening."
exit "$status"
