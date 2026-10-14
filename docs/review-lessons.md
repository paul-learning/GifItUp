# Review lessons

Recurring review-finding types, so they get caught while writing instead of in review. Read at the start of every session. After each PR, add a line for any finding type that could recur; merge duplicates, keep one line per type.

- **Docs drift:** when a decision changes, grep `docs/`, ADRs, open issues, code comments and file headers for the old term and update all of them in the same PR. — #39 CR-2, CR-3, CR-4, CR-10, SEC-6, SEC-13 · #37 SEC-5 · #36 CR-4, SEC-8 · #38 CR-4 · #30 CR-6, CR-7
- **Security claims:** every claim names its adversary (passive vs. active server) and its limits; check platform/browser facts against primary docs before writing them down. — #30 CR-1, CR-3, CR-9, SEC-7 · #37 SEC-1, SEC-2, SEC-3 · #39 CR-1, CR-8, SEC-7, SEC-8, SEC-12
- **Untrusted fields:** every sender- or server-supplied field (URLs, ids, sizes, names) gets explicit bounds, a validation rule applied to the raw input, and a test. — #39 SEC-1, SEC-3, CR-5, CR-9 · #36 SEC-7 · #30 SEC-4
- **Insecure defaults:** when adding a tool or library, check its security-relevant defaults (padding, panic strategy, caching provider, query suite, lock mode, repository filtering) and record the chosen value. — #36 SEC-2, SEC-1 · #35 SEC-2, SEC-6 · #38 CR-2, CR-3, SEC-5
- **IDE/Windows artifacts:** stage explicit paths; file modes, `gradle-daemon-jvm.properties` and IDE edits to `gradle.properties` must not slip in. Checked by `scripts/check-repo-hygiene.sh`. — #35 CR-1, CR-2, SEC-1, SEC-7 · #36 CR-4
- **Matching pins:** versions that must match (crate ↔ CLI ↔ CI) are exact (`=`) and cross-referenced in comments. Checked by `scripts/check-repo-hygiene.sh`. — #37 CR-3
- **Claimed behaviour needs a test:** every behaviour the PR body or docs claim ("error arrives as X", "Commit path works") has a test that asserts it. — #36 CR-1 · #37 CR-2 · #32 CR-1
- **Size and error metadata:** lengths and error strings leak; pad payloads and map errors to closed codes before they cross FFI or reach logs. — #36 SEC-2, SEC-6 · #30 CR-12, SEC-6
- **PR accuracy:** the PR body lists every changed area; check the security-review trigger list against your own diff; record owner decisions only with the owner's explicit words. — #35 CR-2 · #30 CR-11 · #39 SEC-11
- **Dependencies:** declare them `implementation` (not `api`) and only when used. — #32 CR-2, CR-3
