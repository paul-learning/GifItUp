#!/usr/bin/env bash
# The network security config of a built APK (#27 SEC-10 of the socket plan, ADR-0020): it must be the one the manifest names,
# trust the system store only (no user-added CA, no debug-overrides) and permit no cleartext anywhere. The push channel's
# socket is held to the same rules as every request. Needs aapt2 on PATH. Prints fixed lines; exit 1 on any failure.
#   bash scripts/check-network-config.sh <apk>
set -euo pipefail

apk=${1:?usage: check-network-config.sh <apk>}
status=0
fail() { echo "FAIL: $*"; status=1; }

# aapt2 prints an attribute's resource id only for attributes of the android namespace, and prints a string attribute
# quoted and a boolean one not; the config's own attributes (src, cleartextTrafficPermitted) are matched in every spelling
# (with or without "(0x…)", with or without quotes). A positive match is required for the good values, so a spelling that
# is not understood fails the check and never passes it.
tree=$(aapt2 dump xmltree --file AndroidManifest.xml "$apk")
cfg_id=$(grep -oE 'networkSecurityConfig\(0x[0-9a-f]+\)=@0x[0-9a-f]+' <<<"$tree" | sed -E 's/.*=@//' | head -1 || true)
[ -n "$cfg_id" ] || fail "the manifest names no networkSecurityConfig"
cfg_path=$(aapt2 dump resources "$apk" | awk -v id="$cfg_id" '
  /^ *resource 0x/ { found = ($0 ~ ("resource " id " ")); next }
  found && /\(file\)/ { sub(/.*\(file\) /, ""); sub(/ type=.*/, ""); print; exit }' || true)
if [ -z "$cfg_path" ]; then
  fail "the network security config file was not found in the APK"
else
  nsc=$(aapt2 dump xmltree --file "$cfg_path" "$apk")
  trust=$(grep -oE '\bsrc(\(0x[0-9a-f]+\))?="[^"]*"' <<<"$nsc" | sed -E 's/.*="(.*)"/\1/' | sort -u || true)
  [ "$trust" = "system" ] || fail "the network security config trusts something besides the system store; found: $(tr '\n' ' ' <<<"$trust")"
  if grep -q 'E: debug-overrides' <<<"$nsc"; then fail "the network security config has debug-overrides"; fi
  if grep -qE 'cleartextTrafficPermitted(\(0x[0-9a-f]+\))?="?true"?' <<<"$nsc"; then fail "the network security config permits cleartext traffic"; fi
  grep -qE 'cleartextTrafficPermitted(\(0x[0-9a-f]+\))?="?false"?' <<<"$nsc" || fail "the network security config does not forbid cleartext traffic"
fi
exit "$status"
