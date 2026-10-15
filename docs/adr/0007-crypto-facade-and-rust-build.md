# ADR-0007: Crypto facade and Rust build integration

- **Status:** accepted
- **Date:** 2026-10-15
- **Issue/PR:** #11 (4 PRs; this ADR lands with the first). M0 review findings ARCH-M0-1/2/3/7/11 and SEC-M0-2/9; plan review on the first #11 PR.

## Context
The spikes (#3, #4) proved that one OpenMLS crate (`crypto/`) runs on Android (uniffi) and in the browser (wasm-bindgen). #11 turns it into the production crypto core. Open questions from the M0 reviews:
- What Kotlin code sees: an `expect class` or an interface, and with which API (ARCH-M0-1).
- Where the Rust build plugs into Gradle without making every module need a Rust toolchain (ARCH-M0-2).
- Errors and panics across two different FFIs (ARCH-M0-3, spike SEC-1/6/9, #37 SEC-3).
- What happens to the spike scaffolding (ARCH-M0-11).

Constraints: the crypto boundary in CLAUDE.md; constructor injection (ADR-0004); no plaintext or secrets in logs (security rule 4); Kotlin/Wasm in the browser cannot unwind Rust panics.

## Options considered
1. **`expect class MlsClient` in `:shared`, Rust build in `:shared`.** Short path from the spike. But every build and test of `:shared` (and the server's dependency chain) would need rustup, the NDK, cargo-ndk and wasm-bindgen-cli, and fakes for domain tests become awkward.
2. **Plain interface in `:shared`, implementations in a separate `:mls` module** (chosen). `:shared` stays Rust-free, tests use a fake, and only the app modules pay for the Rust build.
3. **Separate Gradle build / published artifact for the bindings.** Clean isolation, but a second build to version and publish for a small project.

Storage: the issue named a "persistent storage provider interface". The spikes compared three options; option A (in-memory OpenMLS storage exported as one snapshot) won for being identical on both platforms (`docs/spikes/openmls-android.md`).

## Decision

### Facade
- `interface MlsClient : AutoCloseable` in `shared/commonMain` (`app.gifitup.shared.data.crypto`), ByteArray in and out, no `expect class`. Implementations are built in the composition roots (ADR-0004) and passed down as the interface.
- **v1 surface:** create an identity, restore from a snapshot, public identity, create and prune KeyPackages, inspect someone else's KeyPackage, export the encrypted snapshot, close. **Group operations are not part of v1**; they arrive with #22 (owner decision, 2026-10-15), with member validation, staged commits checked against the DS envelope and a typed `process()` result (see #22's checklist). Until then the spike group code stays in the crate (`group.rs`) for Rust tests and the spike exports, and becomes test-only when the spikes are deleted.
- No blob or buffer APIs in v1 (#15).
- The "persistent storage provider interface" from the issue is **replaced** by encrypted snapshot export/restore (spike option A). The snapshot is encrypted inside Rust (AES-256-GCM through the OpenMLS crypto provider) with a 32-byte key handed over once at create/restore. That protects against someone who **reads the stored snapshot**, not against someone who can read process memory or run script in our origin. The key arrives as raw bytes, so #16 has to use envelope encryption (a Keystore/WebCrypto key wraps a data key that lives in the Kotlin/JS heap while the app runs).
- The exported Rust type is `NativeMlsClient`; the name `MlsClient` belongs to the Kotlin interface.

### Module `:mls`
- A KMP module with `android`, `jvm` and `wasmJs` targets. It holds the uniffi-generated Kotlin, the Android `.so` files, the wasm-bindgen package and the two implementations. The jvm target exists for host contract tests (uniffi bindings plus the host library through JNA, no emulator).
- Only `:composeApp` and `:androidApp` depend on it. `:shared`, its tests, `:protocol` and `:server` stay Rust-free, and the main CI `Gradle check` job doesn't need a Rust toolchain. The `:mls` checks run in their own CI job.
- The custom Gradle task classes from the spikes move into `:mls` first and into `build-logic/` with #34.

### Errors and panics
- **Closed error codes.** One fieldless `ErrorCode` enum in the core (`crypto/src/error.rs`), the same over both FFIs. OpenMLS errors are mapped to a code and dropped; `Display` is the code name only. uniffi exposes the enum as a Kotlin exception type with no message; wasm-bindgen throws a JS `Error` whose message is exactly the code name. Kotlin wraps both into `MlsException(code)` with no message and no cause.
- **Panics (Android/JVM).** The release profile keeps `panic = "unwind"`. Every FFI entry runs through `PanicGuard`: `catch_unwind` turns a panic into `ErrorCode::Poisoned` and **drops the payload** (uniffi would otherwise forward it to Kotlin as an exception message, and `unwrap`/`expect` payloads can contain internal state). The client is then marked dead: every later call, state export included, returns `Poisoned`, enforced in Rust. A poisoned lock does the same. A silent panic hook replaces the default one, which prints the message to stderr; `console_error_panic_hook` is never used. Kotlin maps any other `Throwable` from the bindings to `MlsException(Poisoned)`.
- **Traps (web).** wasm32 has no unwinding: a panic is a trap (`WebAssembly.RuntimeError`) and the module instance is in an undefined state. Recovery is a **page reload**, then restore from the last persisted snapshot. Re-instantiating the module in place is not safe with the stock wasm-bindgen glue (it caches the dead instance, and its finalizers would later free pointers into the new instance's heap). The Kotlin side classifies by JS type and by default-deny: only an `Error` whose message is exactly a known code name is typed; anything else marks the module dead. In Rust, every entry that touches MLS state (constructors and all client methods) goes through a module-wide trap detector; only the getters of an already returned spike result object and wasm-bindgen's `free` glue don't: a trap leaves the previous call marked as running, so the next call sees it and the whole module returns `Poisoned` from then on, state export included (unit-tested; the browser test that triggers a real trap is #11 PR 4).
- **Never persist after a panic or trap.** Enforced in Rust (`Poisoned`, per client on Android/JVM, per module on the web); the sync engine's part is on #22's checklist.
- **One call at a time per client.** `PanicGuard` serializes calls on a client, so a call on another thread can't slip in between a panic and the moment the client is marked dead, or export state while another call is half done.
- **Release profile:** `overflow-checks = true`, so arithmetic overflow panics (→ `Poisoned`) instead of wrapping silently.

### Crypto parameters recorded here
- Ciphersuite `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (ADR-0002); padding 1024 with an 896-byte content cap (ADR-0006 B7). The sender rejects bigger content.
- MLS authenticated data stays empty: `MlsGroup::set_aad`/`set_safe_aad` are banned by clippy (`crypto/clippy.toml`).
- KeyPackage lifetime: OpenMLS default, 84 days plus a 1-hour margin. Inspecting someone else's KeyPackage enforces OpenMLS's `has_acceptable_range()`, which `validate()` doesn't check. Expiry decisions use the device clock.
- Usernames: `^[a-z0-9_]{3,32}$` (owner decision, 2026-10-15). #12 must use the same rule, byte for byte.

### Supply chain and CI
- `cargo-deny` (pinned, built with `--locked`) checks advisories, yanked crates, licenses, sources (crates.io only) and bans OpenMLS features that would derive `Debug` on secrets, log content, or enable test code and drafts (`crypto/deny.toml`).
- The CI cargo cache is written only on `main` and restored on PRs. Release builds (#27) must not use it.
- `crypto/clippy.toml` sets `too-many-lines-threshold = 60`, matching detekt.

### Spike exit
The last #11 PR deletes `spikes/`, the `gifitup.rust` Gradle property, `kotlin-js-store/wasm/yarn.spikes.lock` and the two spike CI jobs, and adds an "MLS bindings" job. The owner swaps the required checks in the ruleset before that PR merges.

### Rollout (#11 ships as 4 PRs)
1. **In place with this ADR:** closed error codes, `PanicGuard` on every FFI call, silent panic hook, `overflow-checks`, padding 1024/896, the `set_aad` ban, `cargo-deny`, the module split of the crate. The spike FFI exports stay (labelled SPIKE) so the spike jobs keep running.
2. Planned (#11, PR 2): username rule, KeyPackage creation/pruning/inspection, the encrypted snapshot and its restore checks, removing a group's secrets, mutation tests of the parse paths.
3. Planned (#11, PR 3): `MlsClient` interface and fake in `:shared`, the `:mls` module, contract tests on JVM and headless Chrome, spike removal.
4. Planned (#11, PR 4): panic and trap tests with a test-only feature that can't reach release builds, wiring `:mls` into the composition roots.

## Consequences
- Building the apps needs the Rust toolchain, cargo-ndk and wasm-bindgen-cli; working on `:shared`, `:protocol` or `:server` doesn't.
- Adding group operations in #22 changes the interface. That's cheap: every implementation lives in this repo.
- Error codes carry no detail, so debugging a failure means reproducing it, not reading a message. That is deliberate (security rule 4).
- A trap on the web reloads the page. A crafted message that keeps trapping needs #22's quarantine, or it would reload forever.
- The snapshot key lives in the Kotlin/JS heap while the app runs (see Facade); #16 designs where it comes from.
- Handed to other issues (their bodies list the items): #22 (group ops, validation, quarantine, per-group state, never-persist-after-panic in the sync engine), #16 (snapshot key source, persist rules, freshness), #14 (last-resort KeyPackages, delete by ref), #18 (TOFU; the KeyPackage username check is consistency, not authentication), #27 (`wasm-opt`), and #64 (coverage-guided fuzzing).
