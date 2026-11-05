#!/usr/bin/env bash
# Tests the web parts of deploy/deploy.sh (#27 PR 1b, #250 CR-1) with fakes: `web_for` takes the web image only from the
# server image's label and only when it is exactly a digest of our own web repository; `start` brings the web container
# up or down accordingly; `healthy` also requires the web container when the release has one. The functions are cut out
# of the script; docker, compose, curl and sleep are fakes that read their answers from the environment.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for fn in web_for start web_healthy healthy; do
  sed -n "/^$fn()/,/^}/p" deploy/deploy.sh >> "$tmp/fns.sh"
done
for fn in web_for start web_healthy healthy; do grep -q "^$fn()" "$tmp/fns.sh" || { echo "$fn not found in deploy/deploy.sh"; exit 1; }; done

repo=ghcr.io/paul-learning/gifitup-web
digest=sha256:$(printf 'a%.0s' {1..64})
other=sha256:$(printf 'b%.0s' {1..64})
status=0
fail() { echo "FAIL: $*"; status=1; }

# Runs one scenario in a subshell and prints "result|calls".
#   $1 command to run (a function of deploy.sh); $2 label of the server image; $3 image the web container runs
#   ($4 = "noexec" makes the wget inside the container fail)
scenario() {
  FAKE_LABEL=$2 FAKE_WEB_RUNS=$3 FAKE_EXEC=${4:-ok} CALLS="$tmp/calls" bash -c '
    : > "$CALLS"
    web_repo='"$repo"'; health_timeout=1; public_url=https://x.example; SECONDS=0
    sleep() { :; }
    quiet() { "$@"; }
    set_env() { echo "set_env $1" >> "$CALLS"; }
    curl() { printf 200; }
    docker() {
      case "$1" in
        image) printf "%s" "$FAKE_LABEL" ;;
        inspect) case "$*" in *srvcid*) printf img ;; *webcid*) printf "%s" "$FAKE_WEB_RUNS" ;; esac ;;
      esac
    }
    compose() {
      echo "compose $*" >> "$CALLS"
      case "$*" in
        *"ps -q server"*) echo srvcid ;;
        *"ps -q web"*) echo webcid ;;
        *"exec -T web"*) [ "$FAKE_EXEC" = ok ] ;;
      esac
    }
    source "$1"
    '"$1"'
  ' _ "$tmp/fns.sh" && echo "result|$(uniq "$tmp/calls" | tr '\n' ';' | cut -c1-300)" || echo "failed|$(uniq "$tmp/calls" | tr '\n' ';' | cut -c1-300)"
}
has() { case "$1" in *"$2"*) ;; *) fail "$3: '$2' not in '$1'" ;; esac; }
hasnt() { case "$1" in *"$2"*) fail "$3: '$2' in '$1'" ;; esac; }

# web_for
wf() { FAKE_LABEL=$1 PATH="$tmp:$PATH" bash -c 'docker() { printf "%s" "$FAKE_LABEL"; }; web_repo='"$repo"'; source "$1"; web_for img' _ "$tmp/fns.sh"; }
[ "$(wf "$repo@$digest")" = "$repo@$digest" ] || fail "web_for rejects our own digest"
for bad in "" "ghcr.io/evil/web@$digest" "$repo@sha256:zz" "$repo:latest" "$repo@$digest extra"; do
  [ -z "$(wf "$bad")" ] || fail "web_for accepts '$bad'"
done

# start with a web label: pulls and starts both, records the web image
r=$(scenario 'start img' "$repo@$digest" "")
has "$r" "set_env GIFITUP_WEB_IMAGE" "start with label"
has "$r" "--profile web up -d server web" "start with label"
# start without a label: server only, web stopped
r=$(scenario 'start img' "" "")
has "$r" "up -d server;" "start without label"
has "$r" "--profile web stop web" "start without label"
hasnt "$r" "up -d server web" "start without label"
hasnt "$r" "set_env" "start without label"

# healthy: no web wanted passes on the server alone; a wanted web must run that image and answer
r=$(scenario 'healthy img ""' "" ""); has "$r" "result|" "healthy, no web wanted"
r=$(scenario 'healthy img '"$repo@$digest" "" "$repo@$digest"); has "$r" "result|" "healthy, web ok"
r=$(scenario 'healthy img '"$repo@$digest" "" "$repo@$other"); has "$r" "failed|" "healthy, web runs another image"
r=$(scenario 'healthy img '"$repo@$digest" "" "$repo@$digest" noexec); has "$r" "failed|" "healthy, web does not answer"

[ "$status" -eq 0 ] && echo "deploy.sh's web handling behaves."
exit "$status"
