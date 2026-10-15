# ADR-0002: End-to-end encryption with MLS via OpenMLS

- **Status:** accepted (confirmed by the M0 spikes #3 and #4, see "Spike results")
- **Date:** 2026-10-13, revised 2026-10-14

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
- Ciphersuite: `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (mandatory-to-implement). Confirmed by the spikes; the earlier text had a typo (`DCX25519`).
- Every 1:1 chat is an MLS group with two members.
- The server acts as the MLS **Delivery Service**: it orders commits per group/epoch, stores and forwards ciphertext, and distributes KeyPackages and Welcomes.
- **Handshake messages** (Commits/Proposals) are sent as encrypted MLS `PrivateMessage`s (OpenMLS `PURE_CIPHERTEXT_WIRE_FORMAT_POLICY` in both the create and the join config); the server does **not** parse MLS. Group membership is **server-visible metadata by design**: every Commit that adds or removes devices travels in a DS envelope that also lists the affected device IDs. The server uses this list for authorization (only current member devices may post or fetch) and for fan-out. Clients verify that the envelope matches the MLS Commit they process, and they reject and flag any mismatch.
- **Who may change membership (v1 policy):** any current member may add members. A member may remove itself, and the group creator may remove anyone. The server enforces only "the sender is a current member device" plus the epoch ordering. It applies an envelope's membership change **only when it accepts that Commit** for the epoch, never independently.
- **Envelope/Commit mismatch recovery:** a malicious member could send an envelope that doesn't match its Commit, for example to lock others out at the server or to grant a device access. A client that detects a mismatch does not apply the Commit, and it shows a warning naming the sender. The group is then considered **compromised/stuck**. Recovery: any honest member creates a **new group** (new group ID) with the verified members, and the old group becomes read-only. A malicious member can therefore always deny service to a group. That is accepted (threat model).
- **Member validation (every Welcome/Commit/KeyPackage processed):** the leaf's BasicCredential username must equal the server-registered owner of that device. Its signature key must match the TOFU record for that user/device (#18), or the user must confirm a key change. KeyPackages must use our ciphersuite and must not have expired.
- **External commits are disabled**, and GroupInfo is never uploaded to the server. Joining happens only via Welcome.
- **Multi-device (M3) needs a per-user identity key** that signs each device's credential. It is designed in an ADR before M3 work starts.
- **Group names and other group metadata** are sent as encrypted application messages (a `GroupMeta` content type), **not** in the GroupContext. GroupContext extensions are not encrypted.
- **Authentication:** TOFU on the username ↔ signature-key binding. Until key verification exists, a malicious server *can* man-in-the-middle the first contact by serving a forged KeyPackage (threat model, accepted alpha risk). M2 adds key fingerprints and in-chat "member/device added" notices; M3 adds safety-number verification and key-change warnings.

## Consequences
- Up front, we pay for the Rust toolchain, the binding build in CI, and Android↔Web interop testing.
- The server still sees metadata: group IDs, group membership (by design, see above), message timing and sizes. This is documented in the threat model.
- Client state (group secrets) must be persisted securely and carefully. Losing it means losing access to the group, so restore requires a re-add.
- Application messages are padded (`padding_size`, set in both the create and the join config) so that ciphertext length reveals plaintext length only in coarse steps. The block size is 1024 bytes with a content cap of 896 bytes, so every v1 message is one block (ADR-0006, ADR-0007). `authenticated_data` stays empty: it is cleartext and unpadded.

## Spike results (2026-10-14)
Details: [docs/spikes/openmls-android.md](../spikes/openmls-android.md), [docs/spikes/openmls-web.md](../spikes/openmls-web.md).
- **One crate, two binding layers.** `crypto/` has a platform-neutral core (`client::Client`), a uniffi layer (non-wasm targets) and a wasm-bindgen layer (wasm32). The same round trips (two parties, a third member via Commit, snapshot restore) pass on the host, on an Android emulator (uniffi + JNA) and in Chrome (Kotlin/Wasm).
- **Android:**
  - uniffi proc-macros (no UDL); bindings generated in library mode from the unstripped host build; JNA at runtime
  - `cargo-ndk` via Gradle tasks registered as AGP generated sources
  - ABIs arm64-v8a + x86_64, minSdk 26
  - about 2.0 MiB `.so` (980 KiB gzipped) for arm64
- **Web:**
  - wasm-bindgen `--target web`, consumed as a local npm package from Kotlin/Wasm through hand-written `external` declarations
  - 452 KiB gzipped `.wasm`
  - Kotlin/JS fallback not needed
- **Bytes on the web:** a Kotlin/Wasm `ByteArray` can't be shared with JS, and copying costs about 75–95 ms per MiB per direction. Blob (GIF) bytes therefore stay in JS typed arrays end to end on the web, and the facade's blob API uses platform buffers, not `ByteArray`.
- **Persistence:** one versioned state snapshot (`export_state`/`restore`) over OpenMLS's in-memory store. The rules are persist before send, persist before ack, and no old snapshots. The snapshot is encrypted inside Rust (ADR-0007, #11); the key source and the persist rules are #16's.
  - **Android:** encrypted with a key held by the Android Keystore.
  - **Web:** there is **no** real at-rest protection without a user secret. A non-extractable WebCrypto key in IndexedDB is only enforced at the JS API: Chromium and Firefox store its raw bytes in the profile next to the ciphertext (Safari wraps them with the Keychain). Real protection on the web needs a passphrase-derived key, which is a product decision for #16. Otherwise the web client's state is only as safe as the browser profile.
  - **Write atomically.** That gives crash consistency, not rollback detection: a counter stored with the snapshot gets rolled back with it. Freshness has to be anchored outside the local store, and an epoch check alone is not enough: a rollback *within* an epoch keeps the epoch but rewinds the client's own sender-ratchet generation, so the client would reuse key/nonce generations (SEC-6 on #37). #11/#16 must therefore either have the Delivery Service remember the last accepted send per group and device (the client stores its last-sent ID in the snapshot and refuses to encrypt if the server knows a later one), or send a self-update Commit before the first application message after any restore whose freshness can't be confirmed. The first option fails if the server colludes with whoever rolled the state back.
- **Panics:**
  - Superseded in detail by ADR-0007. **Android/JVM:** release builds use `panic = "unwind"`; the crate catches every panic itself and returns `ErrorCode::Poisoned` (no exception message reaches Kotlin). The client is then discarded and reloaded from the last snapshot.
  - **Web:** a panic is a wasm trap that leaves the whole module unusable (`panic = "unwind"` has no effect there). Recovery is a page reload and a restore from the last snapshot; re-instantiating the module in place is not safe (ADR-0007).
  - **Both:** never persist state after a panic or trap (enforced per instance in Rust, ADR-0007), and quarantine the triggering message (#22).
- **Toolchain pins:**
  - `crypto/rust-toolchain.toml` is the single source of the Rust version
  - the Android NDK version lives in the version catalog
  - `wasm-bindgen-cli` must match the `wasm-bindgen` crate version exactly (pinned by checksum in CI)
