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
- **v1 surface:** create an identity, restore from a snapshot, public identity, create and prune KeyPackages, inspect someone else's KeyPackage, export the encrypted snapshot, close. **Group operations are not part of v1**; they arrive with #22 (owner decision, 2026-10-15), with member validation, staged commits checked against the DS envelope and a typed `process()` result (see #22's checklist). The spike group code stays in the crate (`group.rs`), compiled only for tests (padding, AAD, `forget_group`, mutation tests), until #22. **Amendment (#12 PR C):** `sign_auth` / `MlsClient.signAuth` signs the server's auth challenge with the device's signature key; typed parameters only, no generic signing (ADR-0009).
- No blob or buffer APIs in v1 (#15).
- The "persistent storage provider interface" from the issue is **replaced** by encrypted snapshot export/restore (spike option A). The snapshot is encrypted inside Rust (AES-256-GCM through the OpenMLS crypto provider) with a 32-byte key handed over once at create/restore. That protects against someone who **reads the stored snapshot**, not against someone who can read process memory or run script in our origin. The key arrives as raw bytes, so #16 has to use envelope encryption (a Keystore/WebCrypto key wraps a data key that lives in the Kotlin/JS heap while the app runs). Format v2: `magic ‖ version ‖ OpenMLS storage version ‖ nonce[12] ‖ ciphertext ‖ tag`, header as associated data, fresh random nonce per export; nothing is parsed before the length checks and the inner plaintext is decoded only after decryption succeeded (no plaintext restore path). Wiping is **best effort**: the key, the encoded plaintext snapshot (allocated once, so no reallocation leaves copies behind), partially decoded entries on an error path, the restored storage when a restore check fails, and every stored value when a client is dropped are wiped (`zeroize`). Copies that remain in freed memory: the uniffi/wasm-bindgen transfer buffer the key arrives in, the decrypted snapshot buffer inside `openmls_rust_crypto`'s `aead_decrypt`, the AES key schedule (`aes` is built without its `zeroize` feature), the in-memory `MlsGroup` state with its epoch secrets (OpenMLS 0.9.1 only zeroizes its PPRF secrets; affects a client being dropped and a restore that fails part-way through loading groups), and entries OpenMLS deletes (forgotten groups, used or pruned KeyPackages). The signature private key is wiped by OpenMLS on drop. That is accepted with the memory-reader limit above. With random 96-bit nonces, NIST SP 800-38D allows at most 2^32 encryptions per key; #16 rotates the data key long before that (e.g. on every re-wrap or yearly). Restore checks that every group's own leaf carries the snapshot's username and signature key.
- The exported Rust type is `NativeMlsClient`; the name `MlsClient` belongs to the Kotlin interface.

### Module `:mls`
- A KMP module with `android`, `jvm` and `wasmJs` targets. It holds the uniffi-generated Kotlin, the Android `.so` files, the wasm-bindgen package and the two implementations. The jvm target exists for host contract tests (uniffi bindings plus the host library through JNA, no emulator).
- Only `:composeApp` and `:androidApp` depend on it. `:shared`, its tests, `:protocol` and `:server` stay Rust-free in the module graph.
- **Amendment (third #11 PR, owner decision 2026-10-15: "Rust in Gradle check"):** the full build needs Rust after all. Kotlin/Wasm resolves the npm packages of every module into one root `yarn.lock`, so once `:mls` depends on the wasm-bindgen package, any web build first builds that package. The alternatives were an opt-in flag with a second lockfile (the spike scaffolding ARCH-M0-11 removed) or loading the wasm module outside npm (custom glue). So the `Gradle check` CI job and a local `./gradlew check` need the pinned Rust toolchain and `wasm-bindgen-cli`; they run the `:mls` contract tests on the JVM and in headless Chrome. The "MLS bindings" job builds the Android `.so` files with cargo-ndk.
- The custom Gradle task classes from the spikes move into `:mls` first and into `build-logic/` with #34.

### Errors and panics
- **Closed error codes.** One fieldless `ErrorCode` enum in the core (`crypto/src/error.rs`), the same over both FFIs. OpenMLS errors are mapped to a code and dropped; `Display` is the code name only. uniffi exposes the enum as a Kotlin exception type with no message; wasm-bindgen throws a JS `Error` whose message is exactly the code name. Kotlin wraps both into `MlsException(code)` with no message and no cause.
- **Panics (Android/JVM).** The release profile keeps `panic = "unwind"`. Every FFI entry runs through `PanicGuard`: `catch_unwind` turns a panic into `ErrorCode::Poisoned` and **drops the payload** (uniffi would otherwise forward it to Kotlin as an exception message, and `unwrap`/`expect` payloads can contain internal state). The client is then marked dead: every later call, state export included, returns `Poisoned`, enforced in Rust. A poisoned lock does the same. A silent panic hook replaces the default one, which prints the message to stderr; `console_error_panic_hook` is never used. Kotlin maps any other `Throwable` from the bindings to `MlsException(Poisoned)`.
- **Traps (web).** wasm32 has no unwinding: a panic is a trap (`WebAssembly.RuntimeError`) and the module instance is in an undefined state. Recovery is a **page reload**, then restore from the last persisted snapshot. Re-instantiating the module in place is not safe with the stock wasm-bindgen glue (it caches the dead instance, and its finalizers would later free pointers into the new instance's heap). The Kotlin side runs every call into the module inside a small JavaScript try/catch: Kotlin/Wasm's own `try`/`catch`/`finally` compiles to Wasm exception handling, which engines skip for trap errors, so only a JS `catch` sees the trap on the call that caused it (#66 SEC-1; tested with a real trap in headless Chrome, `TrapTest`). It then classifies by JS type and by default-deny: only a plain `Error` whose message is exactly a known code name is typed; anything else marks the whole module dead, and every later call and the factory throw `Poisoned`; the app reloads the page then (#17). In Rust, every entry that touches MLS state (constructors and all client methods) goes through a module-wide trap detector; only the getters of already returned result objects (`PublicIdentity`, `CreatedKeyPackage`; they copy bytes the caller already has) and wasm-bindgen's `free` glue don't (on the Kotlin side those run through the JS try/catch too): a trap leaves the previous call marked as running, so the next call sees it and the whole module returns `Poisoned` from then on, state export included (unit-tested, and tested with a real trap in headless Chrome, `TrapTest`).
- **Known panic inside restore.** `openmls_memory_storage` unwraps when it decodes a stored value, so a malformed value **inside a decrypted snapshot** panics while groups load (found by the mutation tests). Only the key holder or our own bug can produce one, because the snapshot is authenticated. On Android/JVM the constructor's `catch` turns it into `Poisoned`; on the web it is a trap during restore, so #16 must stop a reload loop (count failed restores outside the snapshot and offer a reset after a few).
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
The third #11 PR deletes `spikes/`, the `gifitup.rust` Gradle property, `kotlin-js-store/wasm/yarn.spikes.lock` and the two spike CI jobs, makes the spike group code test-only, and adds the "MLS bindings" job. The owner swaps the required checks in the ruleset before that PR merges.

### Rollout (#11 ships as 4 PRs)
1. **In place with this ADR:** closed error codes, `PanicGuard` on every FFI call, silent panic hook, `overflow-checks`, padding 1024/896, the `set_aad` ban, `cargo-deny`, the module split of the crate. The spike FFI exports stay (labelled SPIKE) so the spike jobs keep running.
2. **In place with the second #11 PR:** username rule, KeyPackage creation (at most 100 per call, 200 held), pruning of expired ones, inspection of others' KeyPackages (incl. `has_acceptable_range()`, ciphersuite, username, no last-resort), the encrypted snapshot and its restore checks, `forget_group` (test: storage equals the pre-group baseline after a self-update and with a pending commit), mutation tests of the parse paths.
3. **In place with the third #11 PR:** `MlsClient`/`MlsClientFactory` and a fake in `:shared`, the `:mls` module (`UniffiMlsClient` on Android/JVM, `WasmMlsClient` in the browser), one contract-test suite run on the JVM and in headless Chrome, spike removal. `:mls` is not wired into the apps yet.
4. **In place with the fourth #11 PR:** panic and trap tests through a real Rust panic (JVM: `Poisoned`, no payload, no export; browser: the trap is caught on the call itself, the module is dead, the Kotlin and JS copies of the key are zeroed, and the Rust trap detector refuses the next call; the Rust copy and all MLS state stay in the dead module's memory until the page reloads); `:mls` wired into the composition roots (`AppGraph`, factory loaded lazily) and its `.so` files shipped in the APK.

### Test-only panic trigger
- A cargo feature `test-panic` makes `inspect_key_package` panic on the reserved username `__panic__`. It adds **no FFI function**, so the uniffi bindings and their checksums are the same with and without it (the production bindings are generated from the production library; the JVM tests load a feature build of the same API).
- It can't be built with the release profile (`compile_error!` in `crypto/src/test_panic.rs`). Test builds use their own cargo target dirs and, on the web, their own npm package (`gifitup-crypto-test`), so the trap test never touches the production module the contract tests use.
- A marker symbol (`gifitup_test_panic_marker`) exists only with the feature. `:mls:verifyNoTestPanic` (part of `check`) scans the production wasm package (and the generated bindings as a tripwire), `:mls:verifyNoTestPanicAndroid` the release `.so` files; CI also scans what ships: every file of the web bundle and the `.so` files inside both APKs.
- Release builds of the crate rewrite the build machine's cargo home and checkout paths (`--remap-path-prefix`), so the shipped `.so`/`.wasm` files don't contain the account name; CI checks the shipped files for the runner's home path.
- Android ships `arm64-v8a` and `x86_64` only (`abiFilters`), the ABIs the crate is built for.

## Consequences
- Building the apps needs the Rust toolchain, cargo-ndk and wasm-bindgen-cli; working on `:shared`, `:protocol` or `:server` doesn't.
- Adding group operations in #22 changes the interface. That's cheap: every implementation lives in this repo.
- Error codes carry no detail, so debugging a failure means reproducing it, not reading a message. That is deliberate (security rule 4).
- A trap on the web reloads the page. A crafted message that keeps trapping needs #22's quarantine, or it would reload forever.
- The snapshot key lives in the Kotlin/JS heap while the app runs (see Facade); #16 designs where it comes from.
- Handed to other issues (their bodies list the items): #22 (group ops, validation, quarantine, per-group state, never-persist-after-panic in the sync engine), #16 (snapshot key source, persist rules, freshness), #14 (last-resort KeyPackages, delete by ref), #18 (TOFU; the KeyPackage username check is consistency, not authentication), #27 (`wasm-opt`), and #64 (coverage-guided fuzzing).
