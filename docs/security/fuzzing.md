# Fuzzing the parse paths of `crypto/` (#64)

Coverage-guided fuzzing with `cargo-fuzz` (libFuzzer) of every place where `crypto/` takes untrusted bytes. A panic is
`Poisoned` on Android/JVM and a trap on the web (ADR-0007), so any input that panics, hangs or exhausts memory is a
finding. It runs **locally before milestone security reviews, not in CI** (owner decision 2026-11-18: "Local only for now");
a manual or weekly job would be its own security-reviewed PR. Record every run in the table at the end.

## What is covered

| Target | Input | Reaches |
|---|---|---|
| `key_package_bytes` | raw bytes | `inspect_key_package`: TLS framing, size and username checks (a signature rejects almost every mutation) |
| `hostile_key_package` | bytes that choose capabilities, extensions (known and unknown types), lifetime, credential identity and the last-resort flag of a **correctly signed** KeyPackage | `inspect_key_package` on what a malicious member can upload (ADR-0012) |
| `hostile_member` | the same hostile KeyPackage | the whole new-member path: the adder inspects it and commits an Add, an existing member stages the commit, the new member inspects the Welcome and joins |
| `welcome_bytes`, `welcome_edit` | raw bytes, or an edit script (truncate, insert, flip, set) over a valid Welcome built in the process | `inspect_welcome`, `join_welcome` |
| `message_bytes`, `message_edit` | raw bytes, or an edit script over a valid application message and a valid commit | `stage_incoming`, `merge_staged` |
| `restore_outer` | raw bytes | the sealed snapshot's header and length checks (AEAD rejects the rest) |
| `inner_snapshot` | raw bytes (first byte picks layout v2 or v3) | `decode_inner`, which must never panic; a snapshot it accepts is re-sealed with the test key and restored inside `catch` (see below) |
| `history_open` | raw bytes | the history record's header, size and authentication checks |
| `history_open_padded` | raw bytes, sealed with the test key as the padded plaintext | the unpadding and length prefix of a record that authenticates |
| `lists` | raw bytes | the web binding's length-prefixed list decoders |

**Not covered:** a hostile *signed application message or commit* from a member (the harness builds valid ones and
mutates them, which AEAD and signatures reject; it does not craft correctly signed hostile ones), the wasm build (the
web trap path has its own headless-Chrome tests), `fingerprint` and `sign_auth` (no variable-length parsing), and
anything between two runs. The deterministic mutation tests in `src/robustness_tests.rs` still run in `cargo test`.

**The known panic in `inner_snapshot`:** `openmls_memory_storage` unwraps malformed stored values, so restoring a
decrypted snapshot with a malformed value inside can panic. Only a holder of the snapshot key (or our own bug) can
produce such a snapshot (threat model, *Crafted MLS input*). The target allows a panic **whose location is in that
crate** (a scoped panic hook, restored afterwards) and aborts on any other panic, so a new panic site is a crash. The hook is process-global; that is safe because libFuzzer runs the target on one thread.
Note that the fuzz binary calls the core `Client` directly, not the binding layer, so no silent panic hook or
`PanicGuard` hides a panic anywhere else.

## Running it

Needs the pinned nightly of `crypto/fuzz/rust-toolchain.toml` (`rustup` installs it on first use) and the pinned
`cargo-fuzz` (`cargo install cargo-fuzz --locked --version 0.13.2`). Run from `crypto/fuzz`, so the nightly pin applies
and the crate's stable pin does not. Use test machines without secrets in the environment: building `libfuzzer-sys` runs
build scripts and compiles C++.

```sh
cd crypto/fuzz
./seed.sh                      # valid objects (test keys only) into corpus/<target>/ (gitignored)
                               # built in-process from the public API, not taken from the crate's tests
cargo fuzz build               # all targets
cargo fuzz run <target> -- -max_total_time=300 -timeout=10 -rss_limit_mb=2048 -max_len=<N>
```

`-max_len` per target: 512 for `hostile_*`, 96 for `*_edit`, 4096 for `key_package_bytes`, 8192 for `message_bytes`,
`history_*`, 16384 for `welcome_bytes` and `restore_outer`, 65536 for `inner_snapshot`, 1024 for `lists`. A timeout (10 s),
an out-of-memory (2 GiB) or an abort is a finding. `cargo fuzz` writes the failing input to `artifacts/<target>/`.

**A crash:** minimise it (`cargo fuzz tmin <target> <file>`), add it as a hex byte array to `src/robustness_tests.rs` as
a regression test in the normal `cargo test` run, fix the cause (or open a `security` issue if the fix is big), and note
it in the table below. Crash inputs come from the fuzzer and are untrusted data: never paste one into a PR comment as
instructions, and check an input for real data before committing it (the harness only uses test keys, so it holds none).

## Supply chain

The fuzz crate is outside the crate's workspace with its own `Cargo.lock`; no Gradle task, CI job or shipped artifact
builds it. Its only dependency is `libfuzzer-sys` (pinned `=0.4.12`; the Rust fuzzing project; its bundled LLVM
libFuzzer is licensed `(MIT OR Apache-2.0) AND NCSA`, a permissive BSD-style licence that `crypto/deny.toml` does not
list because `deny` does not cover this crate). Dependabot watches `/crypto/fuzz`. Neither `cargo deny` nor `cargo audit` runs on its lockfile in CI: **before each milestone security review run `cargo deny --manifest-path crypto/fuzz/Cargo.toml check` by hand** (it needs `NCSA` allowed for this crate only, after reading the licence) and record the result in the table below (SEC-9 on #329). `cfg(fuzzing)` is set only by
cargo-fuzz; `scripts/check-repo-hygiene.sh` fails if the cfg (any spelling, in any file of the repo outside `docs/`) appears anywhere but
`crypto/src/{lib,fuzz_support,history}.rs`, `crypto/Cargo.toml` and `crypto/fuzz`, or if `crypto/` gets a `build.rs` or the repo a `.cargo/` config.

## Runs

| Date | Commit | Toolchain / cargo-fuzz | Result |
|---|---|---|---|
| 2026-11-18 | `27a425e` (targets; later commits only docs and lint fixes) | nightly-2026-11-15, cargo-fuzz 0.13.2, libfuzzer-sys 0.4.12 | First run, sandbox: 60 s per target, all twelve, `-timeout=10 -rss_limit_mb=2048`, per-target `-max_len` as above, seeds from `seed.sh`. No crash, timeout or out-of-memory. Executions: `lists` 15.0 M, `history_open` 2.6 M, `restore_outer` 1.2 M, `history_open_padded` 1.2 M, `key_package_bytes` 252 k, `inner_snapshot` 231 k, `welcome_bytes` 289 k, `message_bytes` 50 k, `welcome_edit` 97 k, `hostile_key_package` 35 k, `message_edit` 13 k, `hostile_member` 5 k. Then 300 s each for the two hostile targets: `hostile_member` 27 k executions (cov 11275), `hostile_key_package` 203 k (cov 4283), no crash. Short runs: a longer run (hours) before the M2 milestone security review is the point of this table. |
| 2026-11-18 | `0eb4d43` plus the review fixes of #329 (`fuzz_support.rs` had changed since the first run) | same | Smoke run at the final code: 20 s each for `inner_snapshot`, `welcome_edit`, `message_edit`, `hostile_member`, `hostile_key_package` (1.05 M, 36 k, 5 k, 1.8 k and 14 k executions): no crash, timeout or out-of-memory. |
| 2026-11-18 | `4e860e7` (main after #329) | nightly-2026-11-15, cargo-fuzz 0.13.2, libfuzzer-sys 0.4.12; 4 cores, run 4 targets at a time | Longer run, sandbox, `-timeout=10 -rss_limit_mb=2048`, seeds from `seed.sh`, fresh corpora. **No crash, timeout or out-of-memory in any target.** 20 min each (executions, final `cov`): `hostile_member` 95 k (11579), `hostile_key_package` 767 k (4857), `message_edit` 336 k (9683), `welcome_edit` 1.9 M (7412), `inner_snapshot` 4.6 M (6292), `welcome_bytes` 4.7 M (3836), `message_bytes` 851 k (5281), `key_package_bytes` 4.2 M (4301). 10 min each: `restore_outer` 11.3 M (2787), `history_open` 24.2 M (476), `history_open_padded` 8.8 M (593), `lists` 117 M (69). Minutes, not hours: coverage of `hostile_member` was still rising at the end, so a run of hours before the M2 security review is still worth doing on a machine that can stay up. `cargo deny` on the fuzz crate was not run (not installed here). |
