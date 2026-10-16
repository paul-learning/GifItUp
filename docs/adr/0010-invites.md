# ADR-0010: Invites

- **Status:** accepted
- **Date:** 2026-10-16
- **Issue/PR:** #13 (PR A: schema, redemption, admin CLI; PR B: user endpoints). Round-0 plan review on the first #13 PR (SEC-44 to SEC-52).

## Context
GifItUp is for a small circle of friends: registration needs an invite (threat model, goal 4). #12 built the registration flow with an invite seam (`InviteRedeemer`) that refused everything. #13 makes invites real.

Constraints:
- **The server is untrusted:** it must store as little as it can.
- **Untrusted links:** an invite link travels through chat apps, so it can be forwarded or leaked.
- **No public signup:** the very first account needs an invite too.

## Options considered
- **Who may invite** (owner decision, 2026-10-16): every member, at most 5 open invites at a time, each valid 7 days (chosen), vs. only the owner (tighter, but the owner becomes the bottleneck for every new friend).
- **Bootstrap** (owner decision): an admin CLI on the VM that prints the first link once (chosen), vs. a code in `.env` that the server creates at startup (the code would sit in a file and in the container environment).
- **Code format:** 128-bit random codes, stored as a plain SHA-256 (chosen). Short human-typeable codes would need a slow hash and rate limits per code; 128 bits makes online guessing hopeless at any rate the server allows.

## Decision
### Codes and links
- **Code:** 16 bytes from `SecureRandom`, written as 22 characters of canonical base64url (`InviteCodes`). Registration accepts only that form (strict decode; anything else is a 400).
- **Storage:** the database stores only `SHA-256(code)` (`invites.code_hash`, unique). The code is never logged and never returned after creation.
- **Link:** `<GIFITUP_PUBLIC_URL>/invite#<code>`. The code is in the URL **fragment**, which browsers never send to a server (SEC-M0-10).
- **What each control protects, and against whom** (#13 SEC-44):
  - **The hash** protects against someone who reads the database: a backup leak, or an SQL injection as the app role. They can't turn a stored hash into a working link.
  - **The fragment** protects against HTTP logs and Referer headers: Caddy's access log, the server's request logs and other sites never see the code.
  - **Neither protects against the server process.** It creates every code and receives it again in the registration request. An active or compromised server can record codes, or simply register anyone. Invites keep *outsiders* out; they don't constrain the server.

### Redemption
- Part of registration, on the same transaction (ADR-0009's check order: the invite before the username, so a taken name is only ever revealed to someone holding a valid invite).
- One statement: `UPDATE invites SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ? RETURNING id, created_by`. Under READ COMMITTED, a concurrent redemption or revocation makes Postgres re-check the condition, so exactly one wins (tested with a race). A taken username rolls the whole transaction back, so the invite stays unused.
- An unknown, used, revoked or expired code, or one whose creator was deleted (the invite goes with its creator), gives the same `403 invite_invalid`.
- No per-invite rate limit: codes are 128-bit, the register budget is global (50/s, ADR-0009), and a successful redemption uses the invite up, so guessing is infeasible and there's nothing per invite to protect.

### The invite tree
- **`accounts.invited_by`:** the inviter, the base for contacts (#18). It becomes NULL if the inviter is deleted.
- **`accounts.joined_via_admin`:** true for accounts that joined with an admin invite (bootstrap roots). It tells a root apart from an account whose inviter was deleted (#13 SEC-46).
- **`invites.used_by`:** which account an invite created, shown to the inviter in their invite list (PR B). That list is how an inviter notices a link that reached the wrong person.

### Admin CLI
In the server image, run as the compose service `admin` (profile `admin`) with the restricted **app role**, so it needs no superuser password:
- `create-invite`:
  - **What:** an admin invite (`created_by` NULL), valid **24 h** (#13 SEC-47).
  - **Output:** the link is written once, straight to stdout, never through the logger. The service has Docker logging off (`logging: driver: none`) and is run with `--rm`, so the link isn't kept in a container log on disk.
- `delete-account <username>`: removes one account with its devices, sessions and invites. People it invited keep their accounts.
- `delete-account --with-invitees <username>`: removes an account and everyone it invited, transitively (recursive CTE, one transaction). This contains a leaked link (#13 SEC-46).

Runbook: `docs/ops/deploy.md`.

### Limits and retention (#13 SEC-45, owner decision 2026-10-16)
- **Open invites:** at most 5 per account, each valid 7 days. **Creation cap:** at most 20 per account per 24 h, so create/revoke can't loop. Both are counted under a lock on the account row (`FOR NO KEY UPDATE`, which doesn't block foreign-key checks; PR B).
- **Retention:** revoked and expired-unused invites are deleted a day after they ended (the creation cap still counts them until then); used invites 30 days after use. The tree itself lives on `accounts`. An hourly job does this (`InviteCleanup`).
- **Invite list** (PR B): at most 50 rows.

### Responses
Responses that carry a secret (register and login session tokens, the created invite link) send `Cache-Control: no-store` (#13 SEC-48).

## Consequences
- **Registration is open to anyone holding a valid invite.** The first invite comes from the admin CLI.
- **A leaked link gives an outsider an account,** and that account can invite more people. Containment is the admin `delete-account --with-invitees` command. The inviter can see in their invite list who joined (threat model, Limitations).
- **The client** builds or checks the link itself. It accepts a server-supplied `link` only as `<its own origin>/invite#<canonical code>` (#13 SEC-50, in #17's checklist), and reads the code from the fragment.
- **Metadata the server holds:** who invited whom; when invites are created, used and revoked; how many each account has open (kept per the retention rules above).
- **A rare deadlock:** an admin deleting an account while one of its invites is being redeemed can deadlock. Postgres aborts one side, which rolls back cleanly (one 500 or one failed admin command).
