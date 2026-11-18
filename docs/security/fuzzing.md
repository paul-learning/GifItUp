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
crate** (a scoped panic hook, restored afterwards) and aborts on any other panic, so a new panic site is a crash.
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
list because `deny` does not cover this crate). Dependabot watches `/crypto/fuzz`. `cfg(fuzzing)` is set only by
cargo-fuzz; `scripts/check-repo-hygiene.sh` fails if the cfg appears anywhere but `crypto/src/{lib,fuzz_support,history}.rs`,
`crypto/Cargo.toml` and `crypto/fuzz`, or if `crypto/` gets a `build.rs` or `.cargo/` config.

## Runs

| Date | Commit | Toolchain / cargo-fuzz | Result |
|---|---|---|---|
