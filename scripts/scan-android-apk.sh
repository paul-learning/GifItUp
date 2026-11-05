#!/usr/bin/env bash
# Scans a release APK for what must never ship (#27 PR 2; ADR-0007, #67, #79): the crypto library for every ABI without
# the test-only panic trigger or build-machine paths, and no dev CLI code in the app's classes. Run in the build job and
# again in the sign job on the same file, so what the key signs was scanned in the job that holds the key (SEC-15).
#   bash scripts/scan-android-apk.sh <apk>
set -euo pipefail
apk=${1:?usage: scan-android-apk.sh <apk>}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for abi in arm64-v8a x86_64; do
  lib="$work/scan-$abi.so"
  unzip -p "$apk" "lib/${abi}/libgifitup_crypto.so" > "$lib" || { echo "::error::the APK lacks ${abi}"; exit 1; }
  [ -s "$lib" ] || { echo "::error::the APK lacks ${abi}"; exit 1; }
  # grep exits 0 = found (fail), 1 = not found (ok), 2 = read error (fail).
  for needle in gifitup_test_panic_marker "$HOME"; do
    rc=0; grep -qaF "$needle" "$lib" || rc=$?
    [ "$rc" -eq 1 ] || { echo "::error::($abi): found a forbidden string (or scan error $rc)"; exit 1; }
  done
done
mkdir "$work/dex"
unzip -q "$apk" 'classes*.dex' -d "$work/dex"
python3 scripts/scan-artifact.py --control "Lapp/gifitup/" \
  --forbid "usage: gifitup-cli" --forbid "app/gifitup/cli" --forbid "app.gifitup.cli" "$work"/dex/classes*.dex
echo "The APK passed the library and class scans."
