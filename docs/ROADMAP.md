# GifItUp roadmap

Goal: a GIF-only messenger for a small circle of friends. It runs on Android and the web, and every chat is end-to-end encrypted with MLS from day one.
Each milestone ends with an **architecture review** and a **security review** (see `CLAUDE.md`).

Status legend: ⬜ not started · 🟨 in progress · ✅ done

---

## M0 — Foundations ⬜
_Get the skeleton, CI, deploy pipeline and the two big technical risks (MLS on KMP, GIF provider) de-risked._

- Scaffold the KMP monorepo: `composeApp` (Android + wasmJs), `shared`, `server`, version catalog, ktlint/detekt. (#1)
- CI with GitHub Actions: build + test + lint for every module, required for merge. (#2)
- Hetzner deployment baseline: Docker Compose (Caddy + Ktor + Postgres), TLS, deploy workflow, hardening checklist. (#6, #7)
- **Spike: OpenMLS on Android** (uniffi + cargo-ndk) and **on Web** (wasm-bindgen, called from Kotlin/Wasm). Result: ADR-0002 is confirmed or revised. (#3, #4)
- **Spike: GIF provider** (Giphy vs Klipy). Covers free-tier limits, terms of service on re-hosting, and CORS from the browser. Result: ADR-0003 is confirmed with a delivery mode. (#5)
- Repo security baseline + threat model v1 (#8)
- 🏛️ Architecture review M0 · 🛡️ Security review M0 — #9, #10

## M1 — Identity, accounts & server plumbing ⬜
_Users can join through an invite, devices register keys, and the server can relay opaque data. Messaging is not built yet._

- `crypto/` crate: identity/signature keys, credentials, KeyPackage generation; Android + Web bindings; Kotlin facade in `shared`. (#11)
- Server: DB schema and migrations, accounts, invite codes (single-use, expiring), device registration, session auth. (#12, #13)
- Server: KeyPackage directory (upload, single-use claim, low-water-mark refill). (#14)
- Server: encrypted blob store (upload/download of opaque ciphertext, size limits, TTL cleanup). (#15)
- Client: onboarding (redeem invite → choose username → generate keys → register), secure local key storage (Android Keystore / browser IndexedDB plus wrapping). (#16, #17)
- Client: contacts list (users from the same invite tree). (#18)
- 🏛️ Architecture review M1 · 🛡️ Security review M1 — #19, #20

## M2 — Encrypted GIF messaging MVP ⬜ → first friends alpha
_The first usable version: 1:1 and group chats that send GIFs, all MLS-encrypted._

- Server: MLS Delivery Service. Covers per-group ordered message log, commit ordering per epoch, Welcome delivery, and a WebSocket push channel for online clients. (#21)
- Client: create a group / 1:1 chat (a group of 2), add members, process Welcome/Commit, persist MLS group state. (#22)
- Client: GIF search UI, talking to the provider directly. (#23)
- Client: send flow (pick → fetch → encrypt → upload blob or reference → MLS application message) and receive/render flow. (#24)
- Client: chat list + conversation screen UI (GIF grid, timestamps, sender). (#25)
- Cross-platform interop test: an Android client and a Web client in the same group (CI). (#26)
- Release: Android APK through GitHub Releases, web app served by Caddy, alpha with friends. (#27)
- 🏛️ Architecture review M2 · 🛡️ Security review M2 — #28, #29

## M3 — Hardening & everyday use ⬜
- Push notifications without content (FCM data-only "wake up" or UnifiedPush/ntfy, decided by ADR).
- Group management: leave, remove member, rename (the name is encrypted inside the group context), self-update / key rotation schedule.
- Safety-number / key-fingerprint verification UI and key-change warnings.
- Multi-device per user (each device is its own MLS leaf).
- Local state backup/restore strategy (ADR).
- Reactions (as GIFs, naturally) and read receipts, both encrypted.
- Own GIF uploads (encrypted attachments).
- 🏛️ Architecture review M3 · 🛡️ Security review M3 (issues created when M2 closes)

## Later / ideas
- iOS target (needs a Mac or CI macOS runners).
- Metadata reduction (sealed sender-like delivery, padding/timing), Tor/proxy support.
- Desktop (Compose Desktop) target.

---

## Owner setup (one-time, cannot be done by the agent)
1. **Branch protection / ruleset on `main`:** require a PR, require 1 approving review (Code Owners), require status checks once CI exists (#2), block force pushes and deletions.
2. **Separate GitHub identity for the agent** (recommended): a machine user or a fine-grained PAT that the agent uses. Then PRs are authored by the bot and *you* approve them. If PRs are authored by your own account, GitHub won't let you approve them, and a "require approval" rule would block every PR.
3. **Local toolchain:** JDK 21, Android Studio (SDK + NDK), Rust via rustup (+ `wasm32-unknown-unknown` and Android targets, `cargo-ndk`), Docker Desktop (optional, for running the server locally).
4. **Domain** pointing at the Hetzner VM (A/AAAA record), needed for TLS and the web app (#6).
5. **VM access** for the deploy workflow: a `deploy` user + SSH key stored as GitHub secret (#6, #7).
6. **GIF API key** (free) once the spike (#5) has picked a provider.
