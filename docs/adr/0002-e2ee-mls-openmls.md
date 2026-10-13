# ADR-0002: End-to-end encryption with MLS via OpenMLS

- **Status:** accepted (implementation details pending the M0 spike)
- **Date:** 2026-10-13

## Context
All chat content must be end-to-end encrypted from the first version, for both 1:1 and group chats. The server is untrusted. We need one implementation that works on Android and in the browser.

## Options considered
1. **MLS (RFC 9420) via OpenMLS (Rust, MIT).** Group-native, IETF standard, actively maintained, compiles to `wasm32` and to Android via the NDK. Needs a Rust toolchain and a binding layer.
2. **Signal protocol via libsignal (Rust, AGPL-3.0).** Proven, with official Android/Java bindings, but no official web build. The AGPL clashes with this repo's MIT license. Groups need sender keys on top.
3. **Pure-Kotlin implementation.** Rejected: we do not implement cryptographic protocols ourselves.

## Decision
Use **MLS via OpenMLS**.
- A Rust crate in `crypto/` wraps OpenMLS behind a small API, roughly: create identity, create KeyPackages, create group, add/remove members, process incoming message, encrypt application message, export state.
- **Android:** uniffi-generated Kotlin bindings, `.so` built with `cargo-ndk`.
- **Web:** `wasm-bindgen` module that Kotlin/Wasm calls through JS interop.
- Kotlin code sees only an `MlsClient`-style interface in `shared` (`expect/actual` or platform-injected implementation).
- Ciphersuite: `MLS_128_DCX25519_AES128GCM_SHA256_Ed25519` (mandatory-to-implement) unless the spike finds a reason to change.
- Every 1:1 chat is an MLS group with two members.
- The server acts as the MLS **Delivery Service**: it orders commits per group/epoch, stores and forwards ciphertext, and distributes KeyPackages and Welcomes. Authentication is TOFU on the username ↔ signature key binding, with safety-number verification in M3.

## Consequences
- Up front, we pay for the Rust toolchain, the binding build in CI, and Android↔Web interop testing.
- The server still sees metadata: group IDs, membership changes (unless they are encrypted handshake messages), message timing and sizes. This is documented in the threat model.
- Client state (group secrets) must be persisted securely and carefully. Losing it means losing access to the group, so restore requires a re-add.
- **Open questions for the spike:** wasm size and performance, the storage provider on the web (IndexedDB), and the ergonomics of Kotlin/Wasm ↔ wasm-bindgen interop.
