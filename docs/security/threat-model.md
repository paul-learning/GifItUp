# Threat model (draft v0)

_This is a living document. Update it whenever a PR changes security assumptions; the security reviewer checks it each milestone._

## Assets
- GIF message content, meaning which GIF was sent in which chat.
- GIF search queries.
- Private keys (signature keys, MLS group secrets, local storage keys).
- Social graph: who talks to whom and in which groups.
- Account credentials and session tokens; invite codes.

## Adversaries
| Adversary | Capabilities | In scope? |
|---|---|---|
| Curious or compromised **server / VM operator** | Reads the DB and traffic, can modify server code, can inject, drop, reorder or withhold messages | ✅ A **passive** server must not learn content or GIF identity. ⚠️ An **active** server can man-in-the-middle a first contact until key verification exists (see Accepted risks). Forged messages are detected by MLS; dropping, withholding and forking are *not* prevented (see Limitations). |
| **Network attacker** | Passive or active on the client↔server path | ✅ |
| **Malicious group member / friend** | Valid member who may misbehave | ✅ After removal they cannot read messages from later epochs. |
| **GIF provider** (Giphy/Klipy) | Sees search queries and sender IP (and recipient IP in Mode B) | ⚠️ Accepted leak (ADR-0003) |
| **Stolen or unlocked device** | Full access to the app's local state | ⚠️ Partially: OS keystore protection; no defence against an unlocked device |
| **Malicious group member** (active) | Sends envelope/Commit mismatches, spams commits | ⚠️ Can deny service to a group (stuck/forked). Recovery is a new group (ADR-0002). Cannot read groups it was never in. |
| **Prompt injection against AI agents** | Anyone can write issues, PR comments, or content agents read (dependency docs, API responses) on this public repo | ✅ Only owner-authored text counts as instructions (CLAUDE.md rule 7). Agents have no merge or settings power once the bot identity exists (roadmap "Owner setup"). |
| **Supply chain / build pipeline** | Compromised dependency, GitHub Action, GHCR image, deploy SSH key, APK signing key, or web bundle | ✅ Pinned actions, committed lockfiles, Dependabot alerts and security updates, least-privilege workflow tokens, deploy behind an environment that needs owner approval, signing key held by the owner only. |
| Global passive adversary / traffic analysis | | ❌ Out of scope for now |

## Security goals
1. Confidentiality and authenticity of message content against the network and a passive server. Against an active server this holds only for contacts whose keys were verified, or who were first contacted while the server was honest.
2. Forward secrecy and post-compromise security (recovery after a key compromise) as provided by MLS, with regular self-updates. Removed members lose access to later epochs. **Caveats:** FS does not cover the locally stored message history or the blob keys kept in it. Encrypted blobs stay on the server until their TTL. OpenMLS `max_past_epochs` (kept small) retains old epoch secrets for late messages.
3. The server learns neither GIF identity nor search terms (ADR-0003).
4. Only invited people can register; invites are single-use and expire.
5. Users can detect key changes (M3: safety numbers).

## Limitations (not prevented)
- The server can **drop, delay, withhold or reorder** messages. It can also show different members different views of a group (**forking**, e.g. by delivering conflicting commits to different members). MLS makes forged messages impossible, but it does not guarantee delivery or consistency. A later partial mitigation: clients compare the epoch authenticator or transcript hash, out of band or in-band.

- **Web client at rest (spike #4, corrected by SEC-1 on #37):** MLS state in the browser (identity key, all group secrets) is a snapshot in IndexedDB.
  - **A non-extractable WebCrypto key does *not* protect it against offline access to the profile:** Chromium and Firefox store the key's raw bytes on disk next to the ciphertext. Non-extractability is only enforced through the JS API; Safari/WebKit is the exception and wraps stored keys with the Keychain.
  - **Without a user passphrase**, anyone with a copy of the browser profile (backup, stolen laptop, malware) can decrypt all of it.
  - **Script running in our origin** (XSS, a compromised or maliciously served bundle) can use any key and read wasm/JS memory, passphrase or not.
  - **Mitigations:** optional passphrase-derived key (Argon2id inside the crate; protection is proportional to passphrase strength, since a copied profile allows unlimited offline guessing), strict CSP and SRI, and the phone app (Keystore) as the stronger client.
  - **Not yet signed off as an accepted risk:** this goes to the owner with #16.

## Accepted risks (owner sign-off required)
| Risk | Until | Mitigation planned |
|---|---|---|
| Active server serves a forged KeyPackage on first contact (MITM), or adds ghost devices | Key verification ships | M2: fingerprint display + in-chat "member/device added" notices; M3: safety-number verification and key-change warnings |
| AI agent uses the owner's GitHub account, so it technically has the ruleset admin bypass and could push to or merge into `main` | A bot account or GitHub App exists | Hard rule in `CLAUDE.md`, deny list in `.claude/settings.json`, owner reviews every PR |
| Web client code is served by the untrusted server | Indefinitely (inherent to web E2EE) | Evaluate SRI / reproducible builds in a milestone review |

Owner sign-off: ☑ accepted by @paul-learning, 2026-10-13 (in chat with the agent: "i sign accept the risk")

## Known metadata visible to the server
- Usernames, devices, IP addresses, connection times.
- Group IDs, group membership (explicit by design, ADR-0002), message counts, timing, and ciphertext and blob sizes. Blob sizes are padded, and MLS application messages are padded to 256-byte blocks (spike value, final size set in #11/#24).
- Invite tree (who invited whom).
- KeyPackage claims (who is about to add whom), Welcome recipients, which device downloads which blob and when, inbox acks/read cursors, and push-notification tokens and timing once push exists.
- In Mode B, the GIF provider can link a sender's search to the recipients fetching the same GIF shortly after.

## Assumptions
- OpenMLS and the platform CSPRNGs are correct.
- The TLS terminating proxy (Caddy) runs on the same VM; the server is still treated as untrusted for content.
- The APK is distributed through GitHub Releases. The web client is served by the server we distrust (see Accepted risks).

## Open questions
- Web key storage: partly answered by spike #4 (see Limitations). Open: whether to offer an optional passphrase (Argon2id, with a minimum strength) for real stolen-profile protection. That's a product decision for #16.
- Push notifications: which provider, and what metadata does it reveal?
