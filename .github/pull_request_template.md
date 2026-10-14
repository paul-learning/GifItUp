## What & why
<!-- Short description. Link the issue: Closes #… -->

Closes #

## How it was tested
- [ ] `./gradlew check` passes locally
- [ ] `cargo test` / `cargo clippy` (if `crypto/` changed)
- [ ] Manually verified on: Android ☐ / Web ☐
<!-- Say honestly what could NOT be tested and why. -->

## Security & privacy checklist
- [ ] No plaintext content, GIF identity, or search query reaches the server
- [ ] No secrets, keys, or tokens in code or logs
- [ ] New server-visible data/metadata: _none_ / _described here_
- [ ] Threat model updated (if assumptions changed)
- [ ] ADR added/updated (if a significant decision was made)

## Review log
<!-- Filled by the authoring agent after the independent review agent(s) post their comments.
     Risky work (crypto/, auth, keys/secrets, protocol/, new endpoints) starts with round 0 (plan) by security-reviewer. -->
| Round | Reviewer | Verdict | Comment |
|---|---|---|---|
| 1 | code-reviewer | | |

| Finding | Severity | Status | Resolution |
|---|---|---|---|
| CR-1 | | fixed / won't fix / escalated | commit SHA or justification |
