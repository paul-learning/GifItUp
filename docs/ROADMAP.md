# GifItUp roadmap

Goal: a GIF-only messenger for a small circle of friends. It runs on Android and the web, and every chat is end-to-end encrypted with MLS from day one.
Each milestone ends with an **architecture review** and a **security review** (see `CLAUDE.md`).

Status legend: ⬜ not started · 🟨 in progress · ✅ done

---

## M0 — Foundations ✅
_Get the skeleton, CI, deploy pipeline and the two big technical risks (MLS on KMP, GIF provider) de-risked._

- Scaffold the KMP monorepo: `composeApp` (Android + wasmJs), `protocol`, `shared`, `server`, version catalog, ktlint/detekt. (#1)
- CI with GitHub Actions: build + test + lint for every module, required for merge. (#2)
- Hetzner deployment baseline: Docker Compose (Ktor + Postgres) behind the VM's shared Caddy (TLS), deploy workflow, hardening checklist (ADR-0005). (#6, #7)
- **Spike: OpenMLS on Android** (uniffi + cargo-ndk) and **on Web** (wasm-bindgen, called from Kotlin/Wasm). Result: ADR-0002 is confirmed or revised. (#3, #4)
- **Spike: GIF provider** (Giphy vs Klipy). Covers free-tier limits, terms of service on re-hosting, and CORS from the browser. Result: ADR-0003 is confirmed with a delivery mode. (#5) → Klipy, Mode B′ (forward the returned URL, validate strictly); see `docs/spikes/gif-provider.md`.
- Repo security baseline + threat model v1 (#8)
- shellcheck + actionlint in CI (#48)
- 🏛️ Architecture review M0 · 🛡️ Security review M0 — #9, #10

## M1 — Identity, accounts & server plumbing ⬜
_Users can join through an invite, devices register keys, and the server can relay opaque data. Messaging is not built yet._

**Next: walking skeleton.** After #13 and #76, M1 continues with one thin end-to-end slice, in this order:
1. ✅ Content-format amendments to ADR-0006 (one docs-only PR, #92): optional GIF caption (#80) and the content-format decision from the owner's comment on #24 (message reference/ID, "annotation" types that old clients ignore).
2. ✅ Dev CLI client + end-to-end test in CI (#79; PRs #94, #95, #102, #103, #104).
3. Minimal onboarding screen (#17), on the same use cases as the CLI.

Everything else in M1 below that this slice doesn't need comes after it.

- `crypto/` crate: identity/signature keys, credentials, KeyPackage generation; Android + Web bindings; Kotlin facade in `shared`. (#11)
- Server: DB schema and migrations, accounts, invite codes (single-use, expiring), device registration, session auth. (#12, #13)
- Server: KeyPackage directory (upload, single-use claim, low-water-mark refill). (#14)
- Server: one per-session request budget for every authenticated endpoint, before more of them arrive (#76, from #75 SEC-63).
- Client: onboarding (redeem invite → choose username → generate keys → register), secure local key storage (Android Keystore / browser IndexedDB plus wrapping). (#16, #17)
- Client: contacts list (users from the same invite tree). (#18)
- Build logic: convention plugins, detekt 2.x when stable (#34, moved from M0).
- Follow-ups from the M0 reviews (#52–#58): still open are SSH key handling (#53) and Android hardening (#54); the deny-list gaps, threat-model updates, protocol-versioning and presentation-layer ADRs and the cleanups are done.
- 🏛️ Architecture review M1 · 🛡️ Security review M1 — #19, #20

## M2 — Encrypted GIF messaging MVP ⬜ → first friends alpha
_The first usable version: 1:1 and group chats that send GIFs, all MLS-encrypted._

- Server: MLS Delivery Service. Covers per-group ordered message log, commit ordering per epoch, Welcome delivery, and a WebSocket push channel for online clients. (#21)
- Client: create a group / 1:1 chat (a group of 2), add members, process Welcome/Commit, persist MLS group state, message references from `crypto/` (ADR-0006 B9). (#22)
- Client: GIF search UI, talking to the provider directly. (#23)
- Server: hand the GIF provider API key to authenticated clients only (spike #5). (#40)
- Client: send flow (pick → validated provider reference, optional caption → MLS application message) and receive/render flow with URL checks and decode limits (ADR-0003 Mode B′); the annotation drop rule and the message store (ADR-0006 as amended in #92: B3a, B9, B10). (#24)
- Client: chat list + conversation screen UI (GIF grid, timestamps, sender, caption input and caption below the GIF). (#25)
- Key fingerprints in contact/chat info and in-chat "member/device added" notices, which mitigate the first-contact MITM risk (#31).
- Cross-platform interop test: an Android client and a Web client in the same group (CI). (#26)
- Release: Android APK through GitHub Releases, web app served by Caddy, alpha with friends.Before it, ask the friends which Android versions they run and decide `minSdk` (kept at 26 for now, owner decision on #54; 28 would drop Android 8). (#27)
- 🏛️ Architecture review M2 · 🛡️ Security review M2 — #28, #29

## M3 — Hardening & everyday use ⬜
- Push notifications without content (FCM data-only "wake up" or UnifiedPush/ntfy, decided by ADR).
- Group management: leave, remove member, rename (the name is sent as an encrypted `GroupMeta` message, not in the GroupContext), self-update / key rotation schedule.
- Safety-number / key-fingerprint verification UI and key-change warnings.
- Multi-device per user (each device is its own MLS leaf; per-user identity key signing device credentials, ADR first): linking a second device and removing a lost one (#72; #12 registers the first device only, ADR-0009).
- Local state backup/restore strategy (ADR).
- Reactions (as GIFs, naturally) and read receipts, both encrypted.
- Own GIF uploads (encrypted attachments): blob pad/encrypt in `crypto/` (ADR-0003) and the encrypted blob store (#15, moved from M1 after spike #5).
- 🏛️ Architecture review M3 · 🛡️ Security review M3 (issues created when M2 closes)

## Later / ideas
- Own curated GIF collection served by our server, as a fallback if Klipy goes away like Tenor did (spike #5).
- Group members compare a hash of their GIF provider key over MLS, to detect a server handing out per-user keys (spike #5, SEC-7).
- iOS target (needs a Mac or CI macOS runners).
- Metadata reduction (sealed sender-like delivery, padding/timing), Tor/proxy support.
- Desktop (Compose Desktop) target.
- Meme composer: top/bottom text on GIFs, meme templates, photos; decide after the alpha (#81).

---

## Owner setup (one-time, cannot be done by the agent)
1. **Agent identity (decided 2026-10-13):** no separate bot account. The agent uses the owner's account, and the owner merges PRs via the admin bypass. GitHub therefore **cannot** stop the agent from merging. The guard is the hard rule in `CLAUDE.md` plus the `.claude/settings.json` deny list. This is an accepted risk in the threat model. Revisit if a bot account or GitHub App becomes possible.
2. **Ruleset on `main`:** require a PR, block force pushes and deletions, require CI status checks once #2 exists, and re-enable the CodeQL rule once #2 has added the CodeQL workflow. Keep the admin bypass; only the owner uses it.
3. **Local toolchain:** JDK 21, Android Studio (SDK + NDK), Rust via rustup (+ `wasm32-unknown-unknown` and Android targets, `cargo-ndk`), Docker Desktop (required for the server's database tests in `./gradlew check` and for running the server locally, ADR-0008).
4. **Domain** pointing at the Hetzner VM (A/AAAA record), needed for TLS and the web app (#6).
5. **VM access** for the deploy workflow: a `deploy` user, a restricted SSH key and the `production` environment (#6, #7; steps in `docs/ops/deploy.md`).
6. **GIF API key (Klipy, free; spike #5):** create a Partner Panel account and an API key (testing mode, 100 requests/hour), keep ads **off**, and set the content filter. The key goes into the server's environment (deploy secret), never into the repo or a client build; the server hands it to logged-in clients. **Request production access (free, unlimited) before the friends alpha (#27)**, because all clients share the key's rate limit.
7. **Repository security features (#8):** verified on 2026-10-14: secret scanning and push protection, Dependabot alerts and security updates are on. *Private vulnerability reporting*, which `SECURITY.md` relies on, is enabled (verified 2026-10-15). **To do:** secret scanning for non-provider patterns (generic private keys, passwords; #53), and under Settings → Actions, require SHA-pinned actions and an allowlist of actions (SEC-M0-11).
