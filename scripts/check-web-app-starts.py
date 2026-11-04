#!/usr/bin/env python3
"""Opens a page in headless Chrome and checks two things (#27 SEC-8, #249): the app starts (a <canvas> appears, also
inside a shadow root) and the browser reports no Content-Security-Policy violation. Standard library only: it talks to
Chrome over the DevTools protocol on a local debugging port, so there is no npm or pip dependency.

    python3 -I scripts/check-web-app-starts.py <chrome binary> <url> [timeout seconds]
"""
import base64
import itertools
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

FIND_CANVAS = (
    "(function(){function f(r){if(r.querySelector('canvas'))return true;"
    "for(const e of r.querySelectorAll('*')){if(e.shadowRoot&&f(e.shadowRoot))return true}return false}"
    "return f(document)})()"
)


class Ws:
    """A minimal client for one WebSocket connection (text frames only)."""

    def __init__(self, url):
        host, rest = url[len("ws://"):].split("/", 1)
        hostname, port = host.split(":")
        self.sock = socket.create_connection((hostname, int(port)), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(
            (f"GET /{rest} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
             f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            head += self.sock.recv(1)
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise RuntimeError("websocket upgrade refused")
        self.sock.settimeout(1.0)

    def send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        n = len(data)
        header = bytes([0x81])
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + n.to_bytes(2, "big")
        else:
            header += bytes([0x80 | 127]) + n.to_bytes(8, "big")
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise RuntimeError("connection closed")
            buf += chunk
        return buf

    def recv(self):
        """The next message, or None when nothing arrived within the socket timeout."""
        try:
            first = self._read(2)
        except socket.timeout:
            return None
        length = first[1] & 0x7F
        if length == 126:
            length = int.from_bytes(self._read(2), "big")
        elif length == 127:
            length = int.from_bytes(self._read(8), "big")
        payload = self._read(length)
        return json.loads(payload) if first[0] & 0x0F == 1 else {}


def main():
    binary, url = sys.argv[1], sys.argv[2]
    timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 60
    port = 9333
    profile = tempfile.mkdtemp()
    chrome = subprocess.Popen(
        [binary, "--headless=new", "--no-sandbox", "--disable-gpu", "--enable-unsafe-swiftshader",
         f"--remote-debugging-port={port}", f"--user-data-dir={profile}", "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        target = None
        for _ in range(60):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=2) as r:
                    pages = [t for t in json.load(r) if t.get("type") == "page"]
                if pages:
                    target = pages[0]["webSocketDebuggerUrl"]
                    break
            except OSError:
                pass
            time.sleep(0.5)
        if target is None:
            print("FAIL: Chrome did not start")
            return 1
        ws = Ws(target)
        ids = itertools.count(1)
        for method in ("Page.enable", "Log.enable", "Runtime.enable"):
            ws.send({"id": next(ids), "method": method})
        ws.send({"id": next(ids), "method": "Page.navigate", "params": {"url": url}})
        violations, started_at, deadline, pending, last_poll = [], None, time.time() + timeout, None, 0.0
        while time.time() < deadline:
            msg = ws.recv()
            if msg:
                if msg.get("method") == "Log.entryAdded":
                    e = msg["params"]["entry"]
                    text = e.get("text", "")
                    if e.get("source") == "security" or "Content Security Policy" in text:
                        violations.append(text[:300])
                elif msg.get("method") == "Runtime.consoleAPICalled":
                    text = " ".join(str(a.get("value", "")) for a in msg["params"].get("args", []))
                    if "Content Security Policy" in text:
                        violations.append(text[:300])
                elif msg.get("id") == pending and started_at is None:
                    if msg.get("result", {}).get("result", {}).get("value") is True:
                        started_at = time.time()
                    pending = None
            if started_at is not None and time.time() - started_at > 4:
                break
            if started_at is None and pending is None and time.time() - last_poll > 0.5:
                last_poll = time.time()
                pending = next(ids)
                ws.send({"id": pending, "method": "Runtime.evaluate",
                         "params": {"expression": FIND_CANVAS, "returnByValue": True}})
        if started_at is None:
            print("FAIL: the app did not start (no canvas)")
            for v in violations:
                print("  violation:", v)
            return 1
        for v in violations:
            print("FAIL: Content-Security-Policy violation:", v)
        if violations:
            return 1
        print("The app started and the browser reported no Content-Security-Policy violation.")
        return 0
    finally:
        chrome.kill()


if __name__ == "__main__":
    sys.exit(main())
