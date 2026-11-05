#!/usr/bin/env bash
# Tests scripts/release-version.sh (#27 PR 2): the tag text is untrusted, so what it accepts is pinned down here.
set -euo pipefail
cd "$(dirname "$0")/.."
status=0
fail() { echo "FAIL: $*"; status=1; }
ok() { # <expected output> <args...>
  local want=$1 got
  shift
  got=$(bash scripts/release-version.sh "$@" 2>/dev/null) || { fail "rejected: $*"; return; }
  [ "$got" = "$want" ] || fail "$* gave '$got', expected '$want'"
}
bad() { if bash scripts/release-version.sh "$@" >/dev/null 2>&1; then fail "accepted: $*"; fi; }

ok "10000 1.0.0" v1.0.0
ok "1 0.0.1" v0.0.1
ok "10 0.0.10" v0.0.10
ok "10199 1.1.99" v1.1.99
ok "2000000 200.0.0" v200.0.0
ok "100 0.1.0" v0.1.0 v0.0.5 v0.0.99
ok "100 0.1.0" v0.1.0 v0.1.0 not-a-tag 'v1.0.0-rc1'
bad v0.0.0
bad v1.0
bad v1.0.0.0
bad v1.0.0-rc1
bad v1.0.0+build
bad V1.0.0
bad 1.0.0
bad v01.0.0
bad v1.00.0
bad v1.0.00
bad v1.100.0
bad v1.0.100
bad v201.0.0
bad 'v1.0.0 '
bad $'v1.0.0\n'
bad 'v1.0.0; echo hi'
# shellcheck disable=SC2016 # a literal command substitution, on purpose
bad '$(id)'
bad ''
bad v0.1.0 v0.1.1
bad v0.1.0 v0.2.0
bad v1.0.0 v1.0.0-rc1 v2.0.0
[ "$status" -eq 0 ] && echo "release-version.sh accepts only newer vMAJOR.MINOR.PATCH tags."
exit "$status"
