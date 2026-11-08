# ADR-0020: WebSocket client libraries and engines (live updates)

- **Status:** proposed (accepted when the owner merges the PR)
- **Date:** 2026-11-08
- **Issue/PR:** #27 (live updates, PR 1b). Follow-up: #272 (one engine on Android). The plan and its round-0 security review are comments on #27 (SEC-3, SEC-4, SEC-5, SEC-7).

## Context
The server's socket (ADR-0015) pushes hints so a message, a GIF or a reaction shows within a second. The client needs a WebSocket on three platforms: the browser app (`wasmJs`), the Android app and the dev CLI (JVM). The REST client is Ktor with one engine per platform: `Js` in the browser, `Android` (HttpURLConnection) on Android, `Java` in the CLI. The Android engine **cannot do WebSockets**. The socket carries the session token in its first frame, so who validates the server's certificate for it matters as much as for REST.

## Options considered
1. **Ktor's `websockets` client plugin on each platform's own engine; OkHttp on Android for the socket only.** The plugin is Ktor's (already our HTTP library, same version). `Js` uses the browser's `WebSocket`, `Java` the JDK's. On Android, OkHttp is the engine Ktor supports for WebSockets that uses the platform's TLS and trust store. Chosen.
2. **Ktor's CIO engine on Android.** Pure Kotlin, but it brings its own TLS implementation and trust handling instead of the platform's. Rejected (SEC-4): one more code path that decides whether a certificate is good.
3. **Move all Android traffic to OkHttp now.** One engine, but it changes every REST, GIF search and media request in the same PR as a new feature, and it is hard to review next to it. Deferred to #272, to be done once live updates run on a device.
4. **Netty/JDK or hand-written clients; polling only.** No: more code to review, or no push.

## Decision
- **Dependencies** (versions in `gradle/libs.versions.toml`, locks and checksums committed): `io.ktor:ktor-client-websockets` 3.6.0 in `shared` (common code, all platforms); `io.ktor:ktor-client-okhttp` 3.6.0 in `androidApp` only, which brings `com.squareup.okhttp3:okhttp(-android)` 5.5.0 and `com.squareup.okio:okio(-jvm)` 3.18.2 (Square; widely used and maintained; Apache 2.0), and raises `androidx.annotation` to 1.10.0 and `androidx.startup:startup-runtime` to 1.2.0. OkHttp's manifest adds `INTERNET` (already declared) and one `meta-data` entry to androidx.startup's provider, which is not exported and which the app already merges; no new component is exported.
- **One factory:** `androidApp/.../SocketEngine.kt` is the only place OkHttp is created, with redirects off. `scripts/check-repo-hygiene.sh` fails if OkHttp is used anywhere else, if app sources set a socket factory, trust manager, hostname verifier, pinner or `SSLContext`, or if Conscrypt, BouncyCastle or okhttp-tls is on the Android app's classpath (the lock file). The system trust store and the network security config therefore decide, as for REST: no cleartext, no user-added CAs.
- **The connector** (`KtorSocketConnector`, `shared`) takes the engine as a parameter. It sets no logging plugin, follows no redirects, sets only a connect timeout (a quiet socket is normal), keeps no text of any failure (the hello holds the token), and refuses frames over 1 KiB: the library's `maxFrameSize` is set, and because not every engine enforces it (the JDK's delivers a whole message; a test shows it) the connector checks the size itself and ends the socket. What an engine buffers before that is its own limit (OkHttp: 16 MiB per message, the browser's, the JDK's); a real hint is under 120 bytes. This is an accepted residual risk for a server we already trust for availability only (threat model).
- **Dev CLI:** `chat-listen --group <id> --timeout <s>` drives the app's `LiveUpdates` loop over the Java engine and prints fixed lines (`listening`, `hint for group <id>`, `no hint within <s> s`, `stopped: <reason>`); the end-to-end test uses it.
- **Metadata:** no new server-visible field. The socket was already part of the server's view (ADR-0015); what changes is that a device now holds a socket open while the app is in use, so presence and connection times become second-level timelines for the server and for anyone who can see its connection metadata (threat model updated).

## Consequences
- Android has two HTTP engines until #272; the extra one only ever sees the socket. Two engines mean two sets of timeouts and two review surfaces, which is why #272 exists.
- The Android lock entries and checksums for OkHttp, Okio and the androidx bumps were written by hand, because the cloud session has no Android SDK; CI resolves the classpath and is the check. If it ever disagrees, regenerate them as in `docs/dependencies.md`.
- No automated test runs the Android socket on a device: the PR asks the owner for a manual check (a user-added CA and `ws://` are refused) before the wiring PR relies on it.
