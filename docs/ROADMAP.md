# GifItUp roadmap

Goal: a GIF-only messenger for a small circle of friends. It runs on Android and the web, and every chat is end-to-end encrypted with MLS from day one.
Each milestone ends with an **architecture review** and a **security review** (see `CLAUDE.md`).

Status legend: ⬜ not started · 🟨 in progress · ✅ done

---

## M0 — Foundations ⬜
_Get the skeleton, CI, deploy pipeline and the two big technical risks (MLS on KMP, GIF provider) de-risked._

- Scaffold the KMP monorepo: `composeApp` (Android + wasmJs), `protocol`, `shared`, `server`, version catalog, ktlint/detekt. (#1)
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
- Key fingerprints in contact/chat info and in-chat "member/device added" notices, which mitigate the first-contact MITM risk (#31).
- Cross-platform interop test: an Android client and a Web client in the same group (CI). (#26)
- Release: Android APK through GitHub Releases, web app served by Caddy, alpha with friends. (#27)
- 🏛️ Architecture review M2 · 🛡️ Security review M2 — #28, #29

## M3 — Hardening & everyday use ⬜
- Push notifications without content (FCM data-only "wake up" or UnifiedPush/ntfy, decided by ADR).
- Group management: leave, remove member, rename (the name is sent as an encrypted `GroupMeta` message, not in the GroupContext), self-update / key rotation schedule.
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
1. **Separate GitHub identity for the agent (prerequisite for step 2):** a machine user with write access, using a fine-grained PAT, that the agent's `gh`/git use. PRs are then authored by the bot and *you* approve them. GitHub never lets an author approve their own PR. So as long as PRs are authored by your account, a "require approval" rule without a bypass would block every merge.
2. **Ruleset on `main`, once step 1 is done:** require a PR with 1 approval and **Require review from Code Owners**, require status checks once CI exists (#2), block force pushes and deletions. **Remove the Admin "always" bypass** (or set it to "pull requests only"). The bot then has no bypass, and you merge after approving. The `code_scanning` (CodeQL) rule blocks merges until a CodeQL workflow exists (#2), so either disable it until then or merge #2 first.
   - *Interim, before step 1:* PRs are authored by you, so keep the bypass and merge them yourself. In this phase "never merge" is enforced only by `CLAUDE.md` and the `.claude/settings.json` deny list, not by GitHub.
3. **Local toolchain:** JDK 21, Android Studio (SDK + NDK), Rust via rustup (+ `wasm32-unknown-unknown` and Android targets, `cargo-ndk`), Docker Desktop (optional, for running the server locally).
4. **Domain** pointing at the Hetzner VM (A/AAAA record), needed for TLS and the web app (#6).
5. **VM access** for the deploy workflow: a `deploy` user + SSH key stored as GitHub secret (#6, #7).
6. **GIF API key** (free) once the spike (#5) has picked a provider.
