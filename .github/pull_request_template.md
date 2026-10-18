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
<!-- One line, set once when the reviews are done (CLAUDE.md, Risk tiers, step 4). Each review round and delta check is
     its own reviewer comment, and the author answers every finding in a reply comment (fix SHA, reason for declining,
     or `later` issue); neither is copied here. Max 2 rounds (round 0, the plan review, doesn't count); delta security
     checks are their own comments and don't count as rounds. -->
Final review: <link to the last reviewer comment> · author reply: <link> · status: <all fixed | fixed except CR-n declined (reason in reply), SEC-n → #later>
