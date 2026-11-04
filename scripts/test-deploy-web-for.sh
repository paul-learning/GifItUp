#!/usr/bin/env bash
# Tests deploy/deploy.sh's web_for (#27 PR 1b): the web image comes only from the server image's label, and only when
# the label is exactly a digest of our own web repository. A fake `docker` prints the label under test.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf '#!/usr/bin/env bash\nprintf "%%s" "$FAKE_LABEL"\n' > "$tmp/docker"
chmod +x "$tmp/docker"
sed -n '/^web_for()/,/^}/p' deploy/deploy.sh > "$tmp/web_for.sh"
[ -s "$tmp/web_for.sh" ] || { echo "web_for not found in deploy/deploy.sh"; exit 1; }
repo=ghcr.io/paul-learning/gifitup-web
digest=sha256:$(printf 'a%.0s' {1..64})
status=0
check() { # <label> <expected output>
  local got
  got=$(PATH="$tmp:$PATH" FAKE_LABEL="$1" web_repo=$repo bash -c 'source "$1"; web_for some-image' _ "$tmp/web_for.sh")
  if [ "$got" != "$2" ]; then echo "FAIL: label '$1' gave '$got', expected '$2'"; status=1; fi
}
check "$repo@$digest" "$repo@$digest"
check "" ""
check "ghcr.io/evil/web@$digest" ""
check "$repo@sha256:zz" ""
check "$repo:latest" ""
check "$repo@$digest extra" ""
[ "$status" -eq 0 ] && echo "web_for accepts only our own web digest."
exit "$status"
