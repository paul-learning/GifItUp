#!/usr/bin/env bash
# Shows that scripts/check-web-headers.sh fails on the mistakes it exists to catch (#249 CR-3): a stub answers with two
# Content-Security-Policy headers, a cacheable invite page, and no wasm name, and the check must refuse it.
set -euo pipefail
cd "$(dirname "$0")/.."
stub=$(mktemp)
trap 'kill "${pid:-0}" 2>/dev/null || true; rm -f "$stub"' EXIT
cat > "$stub" <<'PY'
import http.server

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'")
        self.send_header("Content-Security-Policy", "default-src 'none'")
        self.send_header("Cache-Control", "public, max-age=60")
        self.end_headers()
        self.wfile.write(b"x")
    def log_message(self, *a):
        pass

http.server.HTTPServer(("127.0.0.1", 18099), H).serve_forever()
PY
python3 -I "$stub" &
pid=$!
for _ in $(seq 1 20); do curl -sf -o /dev/null http://127.0.0.1:18099/ && break; sleep 0.5; done
if out=$(bash scripts/check-web-headers.sh http://127.0.0.1:18099 2>&1); then
  echo "check-web-headers.sh accepted a broken edge"; exit 1
fi
for want in "2 Content-Security-Policy headers" "unsafe-inline" "no-store" "no .wasm file"; do
  grep -q "$want" <<<"$out" || { echo "expected a failure mentioning '$want', got:"; echo "$out"; exit 1; }
done
echo "check-web-headers.sh refuses a broken edge."
