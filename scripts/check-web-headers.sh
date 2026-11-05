#!/usr/bin/env bash
# Checks what a browser gets from the GifItUp edge (#27 SEC-7, SEC-8, SEC-9, SEC-12): exactly one Content-Security-Policy
# per answer and the right one for the API and for the web app, the cache policy of the invite page, and the content type
# of the wasm file. Used by CI against a local copy of the edge (.github/workflows/ci.yml, job web-edge) and by the owner
# against the real host (the owner checklist of #27). Reads headers only; sends no cookies and no credentials.
#   bash scripts/check-web-headers.sh <base url> [--assetlinks <sha256 fingerprint of the release certificate>]
set -euo pipefail

base=${1:?usage: check-web-headers.sh <base url> [--assetlinks <fingerprint>]}
base=${base%/}
fingerprint=
if [ "${2:-}" = --assetlinks ]; then fingerprint=${3:?--assetlinks needs a fingerprint}; fi
status=0
fail() { echo "FAIL: $*"; status=1; }

# Prints the response headers of one GET (names lowercased, CR removed).
headers() { curl -sS -o /dev/null -D - --max-time 20 "$base$1" | tr -d '\r' | awk -F: 'NF>1 && !/^HTTP/ {n=tolower($1); sub(/^[^:]*:[ \t]*/,""); print n ": " $0}'; }
csp_count() { printf '%s\n' "$1" | grep -c '^content-security-policy: ' || true; }

page=$(headers /)
invite=$(headers /invite)
[ "$(csp_count "$page")" -eq 1 ] || fail "/ has $(csp_count "$page") Content-Security-Policy headers, expected 1"
[ "$(csp_count "$invite")" -eq 1 ] || fail "/invite has $(csp_count "$invite") Content-Security-Policy headers, expected 1"

policy=$(printf '%s\n' "$page" | sed -n 's/^content-security-policy: //p')
for must in "default-src 'none'" "script-src 'self' 'wasm-unsafe-eval'" "object-src 'none'" "frame-ancestors 'none'" "base-uri 'none'" "img-src 'self' blob:;" "https://static.klipy.com" "https://static1.klipy.com" "https://static2.klipy.com" "https://api.klipy.com"; do
  case "$policy" in *"$must"*) ;; *) fail "the web policy lacks: $must" ;; esac
done
# Style attributes are the one allowed inline (the Compose Web runtime sets some); everything else must not be.
stripped=${policy//"style-src-attr 'unsafe-inline'"/}
case "$stripped" in *unsafe-inline* | *"'unsafe-eval'"* | *" * "* | *"data:"*) fail "the web policy allows unsafe-inline, unsafe-eval, a wildcard or data:" ;; esac

for path in /health /groups/x /config/gif-provider; do
  api=$(headers "$path")
  [ "$(csp_count "$api")" -eq 1 ] || fail "$path has $(csp_count "$api") Content-Security-Policy headers, expected 1"
  case "$(printf '%s\n' "$api" | sed -n 's/^content-security-policy: //p')" in
    "default-src 'none'; frame-ancestors 'none'") ;;
    *) fail "$path does not carry the API policy" ;;
  esac
done

# Invite code in the fragment (SEC-9): the page that reads it is never cached, and /invite does not redirect.
case "$page" in *"cache-control: no-store"*) ;; *) fail "/ is not Cache-Control: no-store" ;; esac
case "$invite" in *"cache-control: no-store"*) ;; *) fail "/invite is not Cache-Control: no-store" ;; esac
code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$base/invite")
[ "$code" = 200 ] || fail "/invite answered $code, expected 200 with no redirect"

# Browsers need application/wasm to compile a module while it streams.
wasm=$(curl -sS --max-time 20 "$base/composeApp.js" | grep -oE '[A-Za-z0-9_.-]+\.wasm' | head -n 1 || true)
if [ -z "$wasm" ]; then
  fail "no .wasm file is named in /composeApp.js, so its content type could not be checked"
else
  type=$(headers "/$wasm" | sed -n 's/^content-type: //p')
  case "$type" in application/wasm*) ;; *) fail "/$wasm is served as '$type', expected application/wasm" ;; esac
fi

if [ -n "$fingerprint" ]; then
  links=$(curl -sS --max-time 20 "$base/.well-known/assetlinks.json")
  [ "$(printf '%s' "$links" | grep -oE '[0-9A-F]{2}(:[0-9A-F]{2}){31}' | wc -l)" -eq 1 ] || fail "assetlinks.json must hold exactly one fingerprint"
  case "$links" in *"\"$fingerprint\""*) ;; *) fail "assetlinks.json does not hold the release fingerprint" ;; esac
fi

[ "$status" -eq 0 ] && echo "Web edge checks passed."
exit "$status"
