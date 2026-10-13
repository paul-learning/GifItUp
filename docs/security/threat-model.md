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
| Curious or compromised **server / VM operator** | Reads the DB and traffic, can modify server code, can inject or drop messages | ✅ It must not learn content or GIF identity, and tampering must be detectable. |
| **Network attacker** | Passive or active on the client↔server path | ✅ |
| **Malicious group member / friend** | Valid member who may misbehave | ✅ After removal (PCS) they cannot read new messages. |
| **GIF provider** (Giphy/Klipy) | Sees search queries and sender IP (and recipient IP in Mode B) | ⚠️ Accepted leak (ADR-0003) |
| **Stolen or unlocked device** | Full access to the app's local state | ⚠️ Partially: OS keystore protection; no defence against an unlocked device |
| Global passive adversary / traffic analysis | | ❌ Out of scope for now |

## Security goals
1. Confidentiality and integrity of message content against the server and the network (MLS).
2. Forward secrecy and post-compromise security as provided by MLS, with regular self-updates.
3. The server learns neither GIF identity nor search terms (ADR-0003).
4. Only invited people can register; invites are single-use and expire.
5. Users can detect key changes (M3: safety numbers).

## Known metadata visible to the server
- Usernames, devices, IP addresses, connection times.
- Group IDs, group membership as derived from delivery, message counts, timing, and ciphertext and blob sizes (blob sizes are padded).
- Invite tree (who invited whom).

## Assumptions
- OpenMLS and the platform CSPRNGs are correct.
- The TLS terminating proxy (Caddy) runs on the same VM; the server is still treated as untrusted for content.
- Clients are distributed from trusted sources: the APK through GitHub Releases, the web app from our domain. ⚠️ The web client is served by the same server we distrust. This is an inherent limitation of web E2EE; it is documented here and revisited in a milestone review (e.g. SRI or reproducible builds).

## Open questions
- Web key storage: how strong can at-rest protection of keys in IndexedDB realistically be?
- Push notifications: which provider, and what metadata does it reveal?
