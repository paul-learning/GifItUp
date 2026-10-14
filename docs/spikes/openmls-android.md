# Spike: OpenMLS on Android via uniffi + cargo-ndk

- **Issue:** #3
- **Date:** 2026-10-14
- **Status:** done. The answer is **yes**.
- **Code:** `crypto/` (spike-quality crate) and `spikes/mls-android/` (throwaway Gradle module, opt-in via `-Pgifitup.rust=true`)

## Question
Can we build a Rust crate wrapping OpenMLS for Android (arm64 + x86_64 emulator), call it from Kotlin via uniffi, and run a full MLS round trip?

## Result
**Yes.** A crate of about 230 lines wraps OpenMLS 0.9.1 behind one uniffi object, `MlsClient`. Its operations are:
- identity creation
- KeyPackage creation
- create group
- add member (returns Commit + Welcome)
- join from Welcome
- encrypt
- process incoming message
- member count

Kotlin calls it through uniffi-generated bindings, which use JNA.

The instrumented test (`MlsRoundTripTest`, 2 tests) passes on an API 37 x86_64 emulator:
1. Alice creates a group and adds Bob via his KeyPackage. Bob joins via the Welcome, and both exchange application messages in both directions.
2. A third member joins. An existing member processes the Commit, and messages still decrypt.

The same scenario also runs as a Rust unit test (`cargo test`) on the host.

The configuration follows ADR-0002:
- ciphersuite `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`
- `BasicCredential` (username)
- RustCrypto provider (`openmls_rust_crypto`, pure Rust, no C code)
- `PURE_CIPHERTEXT_WIRE_FORMAT_POLICY`, so handshake messages are `PrivateMessage`s too
- ratchet tree in the Welcome

## Measurements
Measured on the Windows 11 dev machine (Rust 1.99.0, NDK 30.0.16248370, release profile with `opt-level = "z"`, LTO, `codegen-units = 1`, stripped, `panic = "abort"`).

| What | Value |
|---|---|
| `libgifitup_crypto.so` arm64-v8a | 1.79 MiB (874 KiB gzipped) |
| `libgifitup_crypto.so` x86_64 | 2.24 MiB (912 KiB gzipped) |
| JNA `libjnidispatch.so` | 162 KiB (arm64), 114 KiB (x86_64) |
| Clean release build, both ABIs | ~5.3 min (2 min arm64 + 3.2 min x86_64; LTO dominates) |
| Generated Kotlin bindings | 1761 lines (`uniffi.gifitup_crypto`) |
| Two-party round trip on the emulator (2 identities, KeyPackage, group, add, join, 2 messages) | 79 ms |
| Crates in `Cargo.lock` | 249. Most come from `uniffi/cli`, which only the `bindgen` feature (build-time bindgen binary) uses; the shipped library pulls far fewer |

**APK impact:** a release APK that ships only arm64-v8a would grow by about 0.9 MB compressed. The x86_64 build is only needed for emulators and can be left out of release builds with an ABI split.

## Toolchain setup
- `rustup` with `crypto/rust-toolchain.toml` (pins Rust 1.99.0, `rustfmt`, `clippy`, and the targets `aarch64-linux-android`, `x86_64-linux-android`, `wasm32-unknown-unknown`). rustup installs everything automatically on the first `cargo` call in `crypto/`.
- `cargo install cargo-ndk --locked` (4.1.2)
- Android NDK `30.0.16248370`, pinned as `android-ndk` in the version catalog. AGP installs it if it's missing, and the Gradle task passes its path to cargo-ndk as `ANDROID_NDK_HOME`.
- Windows dev machine: MSVC build tools are needed for the host build that the binding generation uses. Linux CI has everything except cargo-ndk.

## How the Gradle wiring works (`spikes/mls-android/build.gradle.kts`)
- **`cargoNdkBuild`** runs `cargo ndk -t arm64-v8a -t x86_64 -P 26 build --release --locked` into `build/rust/jniLibs`. It is registered with AGP through `variant.sources.jniLibs.addGeneratedSourceDirectory(...)`, so AGP wires the task dependencies.
- **`uniffiBindgen`** runs `cargo build --lib`, a host *debug* build, then `uniffi-bindgen generate --library <host lib> --language kotlin` into `build/generated/uniffi/kotlin`. That directory is registered through `variant.sources.kotlin.addGeneratedSourceDirectory(...)`.
  - **Gotcha:** library mode reads uniffi metadata from the binary, and `strip = true` in the release profile removes it. So the bindings are generated from the unstripped host build. The metadata is identical because the Rust code is the same.
- **Up-to-date checks:** both tasks declare the crate sources as inputs, so Gradle skips them when nothing in `crypto/` changed.
- **Windows and Linux:** both tasks only call `cargo`. The host library name (`.dll`/`.so`/`.dylib`) is picked at run time.
- **JNA:** `net.java.dev.jna:jna:<ver>@aar`, which brings `libjnidispatch.so` per ABI.

## Persistence: OpenMLS storage options
OpenMLS keeps all group state, key material and pending commits behind the `StorageProvider` trait (`openmls_traits`, 72 methods, versioned). The spike uses the in-memory provider, so all state is lost when the process exits. Options for the real implementation:

| Option | Android | Web (#4) | Notes |
|---|---|---|---|
| **A. `MemoryStorage` + snapshot.** `openmls_memory_storage` has `serialize`/`deserialize` for the whole key-value map. Kotlin persists the snapshot bytes, encrypted with a Keystore-wrapped key, after every state-changing call. | ✅ | ✅ (IndexedDB) | Simplest and the same on both platforms. The snapshot grows with the number of groups, but that's fine for a few small groups. Needs an atomic write and a "persist before acking to the server" rule. |
| **B. `openmls_sqlite_storage`** (0.3.0, rusqlite) | ✅ (bundled SQLite) | ❌ (no SQLite in the browser without extra work) | Incremental writes. It would need SQLCipher or file-level encryption, and it splits the platforms. |
| **C. Own `StorageProvider` backed by a Kotlin callback interface (uniffi)** | ✅ | ✅ | The most flexible: one key-value store per platform, with encryption in Kotlin. But it means implementing 72 methods or a generic key-value adapter, and a uniffi callback round trip for every storage access. |

**Recommendation for #11 / #16:** use **A**, with the snapshot encrypted at rest by the platform key store, behind the crypto facade. Revisit C if snapshots get too large or too slow. Losing the snapshot means losing the groups, so restoring requires a re-add (ADR-0002, Consequences).

## Proposed updates to ADR-0002
1. **Fix the ciphersuite name.** OpenMLS and RFC 9420 call it `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`; the ADR says `…DCX25519…`.
2. **Android path confirmed:**
   - uniffi with proc-macros (no UDL), library mode for generating bindings, JNA at runtime
   - cargo-ndk via Gradle tasks registered as AGP generated sources
   - ABIs arm64-v8a + x86_64, minSdk 26
3. **Wire format:** state explicitly that groups use `PURE_CIPHERTEXT_WIRE_FORMAT_POLICY`, which implements "handshake messages are sent as `PrivateMessage`s".
4. **Persistence:** add the storage decision (option A above) once #4 confirms it works on the web.
5. **Toolchain pin:** `crypto/rust-toolchain.toml` is the single source of the Rust version, like `libs.versions.toml` for Gradle.

I'll apply these to ADR-0002 after #4 (web spike), so the ADR is revised once with both platforms' results.

## Findings for follow-up issues
- **#11 (real crate):**
  - persistence (option A)
  - member validation per ADR-0002 (username ↔ device owner, TOFU signature key, KeyPackage ciphersuite and lifetime checks)
  - commit merging only after the Delivery Service accepts it (the spike merges right away)
  - typed errors without `Debug` strings of OpenMLS internals
  - `cargo-deny`/`cargo-audit` in CI
  - move the Kotlin wiring from `spikes/mls-android` into `:shared` (androidMain `actual` of the crypto facade)
- **Error strings:** the spike turns OpenMLS errors into `Debug` strings for Kotlin. The real crate must make sure no key material or plaintext can end up in error messages or logs (CLAUDE.md, security rule 4).
- **CI cost:** the spike job adds a cargo-ndk release build of about 5 min per run. Caching `crypto/target` (keyed on `Cargo.lock`) and building only arm64 for PRs would cut that down.

## Throwaway parts
`spikes/mls-android/` is deleted when #11 moves the wiring into `:shared`. The crate in `crypto/` is kept as the starting point for #11, but it is not production code.
