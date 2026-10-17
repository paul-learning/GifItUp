## What & why
<!-- Short description. Link the issue: Closes #… -->

Closes #

## Risk tier
<!-- high | normal | low (see CLAUDE.md, "Risk tiers"): the highest tier any change hits, plus a one-line reason. -->
**Tier:**

## Decision
<!-- Only for decisions that don't need an ADR (CLAUDE.md: ADRs are for hard-to-reverse decisions). Delete if none. -->

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
- [ ] ADR added/updated for a hard-to-reverse decision, or a *Decision* paragraph above for any other decision

## Review log
<!-- Filled by the authoring agent after the independent review agent(s) post their comments.
     Max 2 rounds (round 0 doesn't count). Design-heavy work (crypto/, auth, keys/secrets, protocol/, new endpoints) starts with round 0 (plan) by security-reviewer. -->
| Round | Reviewer | Verdict | Comment |
|---|---|---|---|
| 1 | code-reviewer | | |

| Finding | Severity | Status | Resolution |
|---|---|---|---|
| CR-1 | | fixed / won't fix / later / escalated | commit SHA, justification or `later` issue |
