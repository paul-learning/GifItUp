# ADR-0014: Contacts, the member list and the contact book

- **Status:** proposed (PR 1 of #18 builds the server side; the unblock decision below is open and settles before PR 2)
- **Date:** 2026-10-22
- **Issue/PR:** #18 (three PRs). Round-0 plan review: SEC-1 to SEC-9.

## Context
A member needs to see who they can talk to, and the app needs a record of which key belongs to which person so that a key swapped by the untrusted server is noticed (ADR-0002 "Member validation" hands this to "the TOFU record (#18)"; ADR-0012 hands over the signature key of every claimed KeyPackage). Two facts shape the design:
- Any member can already claim any other member's KeyPackages by username (ADR-0012, owner decision "one community"). A list of people is therefore a convenience, not an access boundary.
- A username is not an identity: a deleted name can be registered again by anyone with an invite (threat model, SEC-58 of #74). A contact must be keyed by account id and key.

## Options considered
- **Who the list shows** (owner decision, 2026-10-22: "only my invite neighbours and add an issue to work on a contact request / discovery process"; "Inviter and invitees only"): the caller's inviter and the accounts they invited (chosen), vs. every member (tells every member who is on the server, and the server then holds a directory it does not need), vs. members of shared groups (no groups exist yet). Everyone else stays reachable by name until the discovery issue decides how people find each other.
- **A key changes** (owner decision, 2026-10-22: "Block and warn, you can accept"): block and warn (chosen), vs. warn and continue (the warning gets clicked away), vs. block with no way back (a reinstall would orphan a friend).
- **A fingerprint from the server:** refused. A fingerprint the server computes proves nothing (a lying server shows a matching fake), and computing one on the phone needs the crypto crate (crypto boundary, ADR-0007). Fingerprints are #31.
- **Split:** three PRs (owner decision): server and protocol, the contact book and the claim check, the screens.

## Decision
### The member list (`GET /members`)
- A session is required; the account is the session's and the request carries nothing. The answer holds the caller's inviter (first, if there is one and it still exists) and the accounts with `invited_by = caller`, then by username in byte order, ties by id (one deterministic order, so the cut is the same on every request). At most 200 members and `more: true` if cut; per member the account id, username, `relation` (`inviter`, `invitee`, growable, unknown = `UNKNOWN`) and at most 10 devices (id and signature key, oldest first). `Cache-Control: no-store`. Direct edges only: no grandparents, siblings or grandchildren.
- **`relation` is the server's statement and is display only.** No check in the client may depend on it, and nothing is trusted because the server calls it "your inviter" (SEC-7).
- **This is a view, not an access control.** The claim stays open to every member by username, with its 404 oracle and limits (ADR-0012). Docs and screens must not call the list a privacy boundary.
- **Metadata:** nothing new is stored. The tree edge was already known to the server and to both ends; what changes is that it is now served to both ends permanently (the invite list shows `used_by` for 30 days), an invitee learns which account created their link, and both sides get the other's device signature keys (already claimable by username).
- The client refuses the whole answer, never a part, when it breaks any rule: body at most 256 KiB, at most 200 members and 10 devices each, canonical UUIDs, the username rule, canonical base64url keys of exactly 32 bytes, and every account id, username, device id and key unique across the answer (SEC-5).

### The claim response carries the account id
`POST /key-packages/claim` returns `accountId` next to `devices`, so the client can tell a new device of a known account from a new account under the same name. The field is **required**: a response without it does not decode, and the client treats that as a refusal and never as "unknown, so fine" (SEC-2). The server is deployed first. An older client ignores the extra field (`ignoreUnknownKeys`), so this is additive for old clients and fail-closed for new ones (ADR-0006). The id is a random UUID: it is no credential and no server input anywhere; keep it out of logs and URLs.

### The contact book (PR 2)
- One record per account id: username, status, devices as `signature key → device id` (the **signature key** is the authenticated value from the KeyPackage credential; the device id is a label only), first and last seen, and a separate **pending observation** for a blocked entry. Stored in the `LocalStore` under one versioned key, plain (public data); the integrity gaps are not the encoding but the failure modes below.
- **Rules**, all fail closed, applied to both sources of a sighting (the list and every claimed KeyPackage):
  1. Unknown account id: pin it (first sight; TOFU, so an active server can still serve a forged key before the first sighting).
  2. A pinned account with a different key under a pinned device: `KEY_CHANGED`.
  3. A pinned account with a key it did not have: `DEVICE_ADDED`, blocked too, because until multi-device (#72) an account has one device and a second one is what a ghost device looks like.
  4. A pinned account id with another username: `RENAMED`.
  5. A username pinned to account X now belonging to account Y: Y is `USERNAME_REASSIGNED` (SEC-58), X is `GONE`, its pins kept. Always shown with both accounts.
  6. A signature key pinned to account X shown under another account or username: `KEY_CONFLICT` (the server's UNIQUE constraint does not bind a lying server).
  7. A pinned device missing from a **complete** list: `DEVICE_GONE`; a pinned account missing: `GONE`. Information only, and only when `more` is false (SEC-5). `GONE` returns to normal on a reappearance with identical pins.
  8. A claim without a valid `accountId`, or whose username differs from the pinned username of that account id: refused.
- **`BLOCKED` is sticky:** a later good sighting does not clear it, and the pending observation never overwrites the pin (SEC-3). A claim for a blocked contact is refused **before** the request, so the target's KeyPackages are not burned (SEC-6); a first-contact conflict can only be seen after the KeyPackage is consumed, which is the cost of claim-then-check.
- **Failure modes (SEC-4):** a missing, oversize, corrupt or unknown-version record means "contact book unavailable" and every claim is refused; it is never read as an empty book, except after an explicit wipe. Pins are never evicted; at the cap (500) new list-only sightings are refused. All updates run under one lock and write atomically; a failed pin write makes the claim return `Blocked`, never `Usable` (the KeyPackage is already consumed). The book lives and dies with the identity: the same wipe (ADR-0011).
- **Unblocking a blocked contact is open (SEC-1, owner decision pending):** without a client-computed fingerprint the user has nothing to check, so "I checked this, accept" would be a blind click and the wording would claim more than the user did. Either the crate exposes a local fingerprint, shown with the old and new value side by side (a minimal slice of #31), or v1 has no accept and `BLOCKED` ends until the contact is re-added. In both cases the button says what it does, and the real check is out of band. Settled before PR 2.

### Not decided here
How people who are not neighbours find each other and ask to connect, and whether the claim is then restricted to accepted contacts, is a separate issue (owner request, 2026-10-22). A non-neighbour first claimed by name is pinned at first sight with no neighbour context until it decides their trust.

## Consequences
- The server gains one read-only endpoint over existing tables and no stored data. The threat model lists the permanent tree edge and the contact book.
- The client gains a trust store whose failure modes matter more than its encoding; PR 2 tests each (corrupt record, cap, concurrent update, failed write).
- Key verification stays weak until #31 and M3: first contact is TOFU, and unblocking is only as good as the decision above.
