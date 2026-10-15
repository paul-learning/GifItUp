# crypto

Rust crate wrapping [OpenMLS](https://github.com/openmls/openmls). This is the only place in the repo that handles keys, MLS state, ciphertext formats and GIF blob encryption/padding (see [ADR-0002](../docs/adr/0002-e2ee-mls-openmls.md) and [ADR-0003](../docs/adr/0003-gif-source-and-privacy.md)).

- **Android:** Kotlin bindings via uniffi, `.so` built with `cargo-ndk`.
- **Web:** `wasm-bindgen` module, called from Kotlin/Wasm.
- Kotlin code reaches this crate only through the crypto facade in `shared`.

> **Status:** being turned into the production crate in #11 ([ADR-0007](../docs/adr/0007-crypto-facade-and-rust-build.md)). Errors are closed codes and every FFI call is panic-guarded. The group operations in `src/group.rs` are still spike quality (no member validation) and come with #22; the state snapshot is encrypted inside Rust (AES-256-GCM, key handed over once). Spike results: [docs/spikes/openmls-android.md](../docs/spikes/openmls-android.md).

Supply chain: `cargo deny --locked check` (policy in `deny.toml`, pinned `cargo-deny` version in `.github/workflows/ci.yml`).

## Commands
Run these in `crypto/`. `rust-toolchain.toml` pins the toolchain, and rustup installs it on first use.

| Task | Command |
|---|---|
| Test | `cargo test --locked` |
| Lint | `cargo clippy --all-targets --locked -- -D warnings` and `cargo fmt --all --check` |
| Android `.so` (needs `cargo install cargo-ndk`) | `cargo ndk -t arm64-v8a -t x86_64 -P 26 -o <dir> build --release --locked` |
| Kotlin bindings | `cargo build --lib && cargo run --features bindgen --bin uniffi-bindgen -- generate --library target/debug/<lib> --language kotlin --out-dir <dir>` |

From the repo root, the Gradle spike module does both Android steps: `./gradlew -Pgifitup.rust=true :spikes:mls-android:connectedDebugAndroidTest` (needs a running emulator or a connected device).
