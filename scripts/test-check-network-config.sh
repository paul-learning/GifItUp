#!/usr/bin/env bash
# Tests for scripts/check-network-config.sh with a stub aapt2 (the spellings aapt2 may print: with and without resource ids,
# quoted and unquoted booleans). Runs in CI (job shell-and-workflow-lint) and locally: `bash scripts/test-check-network-config.sh`.
set -euo pipefail

script="$(cd "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/check-network-config.sh"
work=$(mktemp -d /tmp/check-nsc-test.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/bin"
cat > "$work/bin/aapt2" << 'STUB'
#!/usr/bin/env bash
case "$*" in
  *"dump resources"*)
    printf '  resource 0x7f0a0001 xml/network_security_config\n        () (file) res/xml/network_security_config.xml type=XML\n'
    printf '  resource 0x7f0a0002 xml/other\n        () (file) res/xml/other.xml type=XML\n' ;;
  *"AndroidManifest.xml"*)
    if [ -z "${NO_CONFIG:-}" ]; then
      printf '      A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f0a0001\n'
    fi ;;
  *"network_security_config.xml"*)
    ids="" ; [ "${FORM:-ids}" = ids ] && ids="(0x01010005)"
    q="" ; [ "${QUOTED:-no}" = yes ] && q='"'
    printf '  E: network-security-config\n    E: base-config\n      A: cleartextTrafficPermitted%s=%s%s%s\n' "$ids" "$q" "${CLEAR:-false}" "$q"
    printf '      E: trust-anchors\n        E: certificates\n          A: src%s="%s" (Raw: "%s")\n' "$ids" "${SRC:-system}" "${SRC:-system}"
    [ -z "${DEBUG_OVERRIDES:-}" ] || printf '    E: debug-overrides\n' ;;
esac
STUB
chmod +x "$work/bin/aapt2"

failures=0
# expect <pass|fail> <message part> -- env assignments...
expect() {
  local want=$1 part=$2 out rc=0
  shift 3
  out=$(env "$@" PATH="$work/bin:$PATH" bash "$script" x.apk) || rc=$?
  if [ "$want" = pass ] && [ "$rc" -ne 0 ]; then echo "FAIL ($*): expected a pass, got: $out"; failures=$((failures + 1)); fi
  if [ "$want" = fail ] && { [ "$rc" -eq 0 ] || ! grep -q -- "$part" <<<"$out"; }; then
    echo "FAIL ($*): expected a failure naming '$part', got rc=$rc: $out"; failures=$((failures + 1))
  fi
}

for form in ids plain; do
  for quoted in yes no; do
    expect pass "" -- FORM=$form QUOTED=$quoted
    expect fail "permits cleartext" -- FORM=$form QUOTED=$quoted CLEAR=true
    expect fail "trusts something besides" -- FORM=$form QUOTED=$quoted SRC=user
    expect fail "debug-overrides" -- FORM=$form QUOTED=$quoted DEBUG_OVERRIDES=1
  done
done
expect fail "names no networkSecurityConfig" -- NO_CONFIG=1

if [ "$failures" -ne 0 ]; then echo "$failures case(s) failed"; exit 1; fi
echo "check-network-config tests passed"
