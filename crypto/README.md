# crypto

Rust crate wrapping [OpenMLS](https://github.com/openmls/openmls). This is the only place in the repo that handles keys, MLS state, ciphertext formats and GIF blob encryption/padding (see [ADR-0002](../docs/adr/0002-e2ee-mls-openmls.md) and [ADR-0003](../docs/adr/0003-gif-source-and-privacy.md)).

- **Android:** Kotlin bindings via uniffi, `.so` built with `cargo-ndk`.
- **Web:** `wasm-bindgen` module, called from Kotlin/Wasm.
- Kotlin code reaches this crate only through the crypto facade in `shared`.

Empty for now. The crate is created by the MLS spikes (#3, #4) and issue #11.
