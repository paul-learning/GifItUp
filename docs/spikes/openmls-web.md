# Spike: OpenMLS in the browser via wasm-bindgen + Kotlin/Wasm

> **Spike code removed** in #11 (third PR): the Gradle wiring now lives in `:mls`, the opt-in flag and the spike CI jobs are gone (ADR-0007). This document is the spike's record.

- **Issue:** #4
- **Date:** 2026-10-14
- **Status:** done. The answer is **yes**, with one important constraint on how bytes cross the boundary.
- **Code:**
  - `crypto/` (same crate as #3, now split into `client` + `uniffi_api` + `wasm_api`)
  - `spikes/mls-web/` (throwaway Gradle module, opt-in via `-Pgifitup.rust=true`)
- **Companion:** [openmls-android.md](openmls-android.md). The two spikes share the crate and the follow-up requirements.

## Question
Can the same Rust crate compile to `wasm32-unknown-unknown`, be called from the Kotlin/Wasm (`wasmJs`) Compose app, and persist MLS state in the browser?

## Result
**Yes.** The crate now has a platform-neutral `client::Client` with two thin binding layers:
- **Android:** uniffi.
- **Browser:** wasm-bindgen with `--target web`, which produces an ES module with an async `init()`.

`MlsWebRoundTripTest` runs in Chrome headless 154 via Karma and passes 5/5:
1. Alice/Bob round trip (group, add, join via Welcome, messages both ways)
2. A third member joins, and an existing member processes the Commit
3. State **export → restore → continue the conversation**
4. A Rust error surfaces as a Kotlin `JsException` with the message
5. The cost of copying a 2 MiB `ByteArray` between Kotlin and JS (see below)

The same scenarios run as host `cargo test`s (5 tests) and through uniffi on Android (instrumented test). So one crate serves both platforms.

## Measurements
Measured on the Windows 11 dev machine, Chrome headless 154, release profile (`opt-level = "z"`, LTO; the profile's `panic` setting has no effect on wasm32, see Panics below).

| What | Value |
|---|---|
| `gifitup_crypto_bg.wasm` after wasm-bindgen | **1.39 MiB, 452 KiB gzipped** (no `wasm-opt` yet; Binaryen typically saves another 10–20%) |
| JS glue `gifitup_crypto.js` | 25 KiB (unminified) |
| Clean release wasm build | ~5 min (LTO dominates); then wasm-bindgen < 1 s |
| wasm init (fetch + instantiate, local) | 17 ms in Node; 48–74 ms in Karma/Chrome |
| Two-party round trip | 125 ms in Node; 209–323 ms in Chrome headless (noisy, shared machine) |
| State snapshot with 1 group | ~7.8 KB |
| **2 MiB `ByteArray` → `Uint8Array` → `ByteArray`** | **310 ms** (hand-written loop), **382 ms** (kotlinx-browser `toInt8Array`/`toByteArray`) |

For comparison, the Compose app's own wasm bundle (`skiko.wasm` + `composeApp.wasm`) is ~10 MiB uncompressed (see #1). The crypto module adds about 14%.

## Kotlin/Wasm interop
- **Declarations:** hand-written `external` declarations in a `@file:JsModule("gifitup-crypto")` file (`GifitupCrypto.kt`, ~50 lines), mirroring `gifitup_crypto.d.ts`. That's easy for this small API. Dukat (the .d.ts generator) is gone, so #11 keeps them hand-written and reviewed alongside the Rust API.
- **Packaging:** the wasm-bindgen output becomes a local npm package (a `package.json` written by the Gradle task), consumed with `implementation(npm("gifitup-crypto", dir))`. Webpack resolves `new URL("gifitup_crypto_bg.wasm", import.meta.url)` as an asset, in both the app bundle and the Karma test bundle, with no custom webpack config.
- **Init:** `initCrypto(): Promise<JsAny?>` (the wasm-bindgen default export) is awaited once with `kotlinx.coroutines.await()`. After that every call is synchronous.
- **Errors:** `Result<_, JsError>` in Rust becomes a thrown JS `Error`, which arrives in Kotlin as `kotlin.js.JsException` with the message (asserted in the test).
- **Panics are different from errors.** On wasm32 a Rust panic becomes a wasm **trap** (`RuntimeError: unreachable`). It also arrives as an exception in Kotlin, but the module's state is then undefined and the instance can't be used again. Rule for #11: after a trap, drop the module instance, re-instantiate it (or reload the page), restore from the last persisted snapshot, never persist after a trap, and quarantine the triggering message. Unlike Android, `panic = "unwind"` doesn't help here.
- **Memory:** Rust objects (`MlsClient`, `AddMemberResult`) live in wasm linear memory and must be `free()`d explicitly. wasm-bindgen also registers a `FinalizationRegistry`, but that's best-effort. The facade in #11 should own the client for the app's lifetime and free short-lived results right away.
- **Bytes: the important constraint.** A Kotlin/Wasm `ByteArray` lives in the Wasm GC heap, which JS can't see. Every crossing copies element by element, at about **75–95 ms per MiB per direction**. kotlinx-browser's helpers aren't faster. That's fine for MLS messages (a few hundred bytes) and snapshots (KB), but **not for GIF blobs (1–3 MB, ADR-0003)**.
  - **Recommendation for #11/#24 (web):** keep blob bytes on the JS side end to end. `fetch()` gives an `ArrayBuffer`, it goes straight into Rust as a `Uint8Array` (wasm-bindgen copies with `memcpy`, which is fast), the decrypted bytes come back as a `Uint8Array`, and from there go to a `Blob` and an object URL. A Kotlin `ByteArray` should never hold a blob on the web.
  - The crypto facade therefore needs a platform-specific blob type (`expect class Bytes`, or blob functions that take and return platform buffers) instead of `ByteArray` for blob encryption.
  - **Open question for #23/#25:** how Compose for Web renders an animated GIF without pulling the bytes into Kotlin. Skiko has its own wasm memory, so decoding a GIF in Compose would copy anyway. An HTML `<img>` overlay or an `ImageBitmap` from `createImageBitmap()` are candidates.

**Fallback (Kotlin/JS instead of Kotlin/Wasm, ADR-0001): not needed.** Interop works, and the byte-copy cost would exist with Kotlin/JS too (`ByteArray` ↔ `Int8Array`), only cheaper. It doesn't justify switching targets.

## Build and toolchain
- **Rust target:** `wasm32-unknown-unknown` is already in `crypto/rust-toolchain.toml`.
- **Randomness:** OpenMLS's `js` feature (getrandom 0.4 `wasm_js` + `web-time`), plus **getrandom 0.2 with the `js` feature** as a target-specific dependency. The RustCrypto curves (`rand_core 0.6`) still use getrandom 0.2, and without that feature the wasm build fails to compile. Randomness comes from `crypto.getRandomValues`.
- **uniffi** is only a dependency for non-wasm targets, and **wasm-bindgen** only for wasm32 (`[target.'cfg(...)'.dependencies]`).
- **`wasm-bindgen-cli` must match the `wasm-bindgen` crate version exactly** (0.2.129). The crate is therefore pinned with `=0.2.129` in `Cargo.toml`, so a lockfile-only update can't drift.
  - **Locally:** `cargo install wasm-bindgen-cli --version 0.2.129 --locked` (about 10 min).
  - **CI:** the prebuilt release tarball, checked against a SHA-256 pinned in the workflow.
  - A Dependabot bump of the crate needs a manual bump of the CLI version and checksum.
- **Gradle wiring** (`spikes/mls-web/build.gradle.kts`): a `wasmBindgenPackage` task runs `cargo build --target wasm32-unknown-unknown --release`, then wasm-bindgen, and writes a `package.json`. Kotlin's npm install depends on it.
  - **Gotcha:** Kotlin validates `npm(name, dir)` at configuration time, so the directory is created up front.
- **Yarn lockfile:** the local npm package changes Kotlin's `yarn.lock`. Spike builds use their own committed lockfile (`kotlin-js-store/wasm/yarn.spikes.lock`, configured in the root build when `gifitup.rust=true`), so both lockfiles keep the strict mismatch check.
  - **#11:** once the crypto module is part of the normal web build, a single lockfile contains it again.

## Persistence in the browser
The persistence building block from the Android spike's option A is now implemented in `client` and tested on all three runtimes: `Client::export_state()` / `Client::restore()`, one versioned binary snapshot covering identity, signature key, group IDs and the OpenMLS key-value store.

| Option | Verdict for the web |
|---|---|
| **A. Snapshot (implemented) → IndexedDB** | ✅ Recommended. IndexedDB stores `Uint8Array`/`ArrayBuffer` directly, so the snapshot never needs to become a Kotlin `ByteArray`. Writes are transactional, so the snapshot is replaced atomically (crash consistency; rollback detection needs an external anchor, see the recommendation below). A 1-group snapshot is 7.8 KB, so rewriting it after every message is cheap. |
| B. SQLite (`openmls_sqlite_storage`) | ❌ Doesn't run in the browser without a wasm SQLite + OPFS layer. Too much for the benefit. |
| C. Own `StorageProvider` → IndexedDB per key | ⚠️ IndexedDB is async-only, but OpenMLS's `StorageProvider` is synchronous. It would need an in-memory cache plus write-behind, which is basically option A with extra steps. |

**At-rest encryption options for the snapshot:**
1. **WebCrypto, non-extractable AES-GCM key** (`extractable: false`), stored as a `CryptoKey` in IndexedDB (structured-clonable). The snapshot is encrypted with it in JS before it's stored.
   - **Protects against:** other origins and extensions without profile access, and casual inspection of IndexedDB in devtools.
   - **Does *not* protect against offline access to the profile** (corrected after SEC-1 on #37). Chromium and Firefox store the raw key bytes in the profile next to the ciphertext, and non-extractability is enforced only at the JS API. The WebCrypto spec allows this. Safari/WebKit wraps stored keys with a Keychain key. Sources: [Ryan Sleevi on public-webcrypto-comments (2015)](https://lists.w3.org/Archives/Public/public-webcrypto-comments/2015Feb/0037.html), [WebKit bug 164868](https://bugs.webkit.org/show_bug.cgi?id=164868). Not verified by inspecting a current profile.
   - **Doesn't protect against:** script running in our origin (XSS, a compromised bundle). That script can't export the key, but it can use it to decrypt.
2. **Encrypt inside Rust** with a key derived from (1) or from a user passphrase. Matches SEC-5 on #36 (secrets never sit in the JS/Kotlin heap as plaintext). But wasm memory is equally visible to same-origin script, so the gain on the web is small.
3. **Passphrase-derived key:** the **only** real protection against a stolen device or copied profile on the web, at a UX cost (unlock on start). This is a product decision.
   - **KDF: Argon2id, run inside the Rust crate** (memory-hard).
   - **PBKDF2 only as a fallback:** it's the only KDF built into WebCrypto, but it's cheap to brute-force on GPUs. If used at all, use OWASP iteration counts.
   - **Strength:** a copied profile allows unlimited offline guessing, so the protection is **proportional to passphrase strength**. #16 needs a minimum strength (SEC-7 on #37).

**Recommendation for #16 (web part):**
- Store the snapshot in IndexedDB in one transaction.
- Encrypt it with a non-extractable WebCrypto key as a baseline (devtools/other-origin hygiene, and real protection on Safari).
- Offer an **optional passphrase** as the only real at-rest protection on Chromium/Firefox. The owner decides on this.
- **Where to encrypt (SEC-5 on #37):** decide this once for both platforms. The spike's code comment says "encrypt inside Rust", while this doc encrypts in JS. Recommendation: encrypt inside Rust with a key the platform supplies (Keystore-unwrapped on Android, WebCrypto- or passphrase-derived on the web), so the plaintext snapshot never enters the Kotlin/JS heap. The browser test currently copies it into a `ByteArray` for convenience; that's spike-only.
- Apply the same persistence rules as on Android: persist before send, persist before ack, no old snapshots.
- **Rollback (SEC-2 on #37):** a counter in the same store and transaction gets rolled back together with the snapshot. It gives crash consistency, not rollback detection. Freshness has to be anchored outside the local store, and an epoch check alone is not enough: a rollback *within* an epoch keeps the epoch but rewinds the client's own sender-ratchet generation, so the client would reuse key/nonce generations (SEC-6 on #37). #11/#16 must therefore either have the Delivery Service remember the last accepted send per group and device (the client stores its last-sent ID in the snapshot and refuses to encrypt if the server knows a later one), or send a self-update Commit before the first application message after any restore whose freshness can't be confirmed. The first option fails if the server colludes with whoever rolled the state back.

The threat model now spells out what the web client can't protect (offline profile copies without a passphrase, script in our origin). Script in our origin is the realistic web threat, so the web client needs a strict CSP (SEC-4 on #32) and subresource integrity. Phones (Android Keystore) are the stronger client.

## Proposed updates to ADR-0002
These are now applied together with the Android results; see the ADR's "Spike results (2026-10-14)" section.

## Findings for follow-up issues
_Update 2026-10-15: ADR-0007 settles the #11 items below differently in two places: an interface in `:shared` with implementations in a separate `:mls` module (not `expect/actual` in `:shared`), and recovery from a wasm trap by page reload (not re-instantiation)._

- **#11:**
  - move `client` + both binding layers out of spike status
  - trap recovery on the web (see Panics above), plus a browser test that triggers a panic on purpose and checks recovery (SEC-3 on #37)
  - snapshot hygiene (SEC-4 on #37): verify the stored username against the stored credential on restore, call `MlsGroup::delete` when leaving or being removed, and test that a removed group's secrets aren't in the next snapshot
  - make sure a rebuilt wasm package always reaches the npm install. Kotlin copies `file:` npm dependencies, so in incremental local builds a stale copy is possible. Not observed, but not tested either
  - add the requirements from both spike docs
  - `expect/actual` facade in `:shared`: `androidMain` via the uniffi bindings, `wasmJsMain` via the externals
  - blob encryption API that takes and returns platform buffers (see Bytes)
  - run `wasm-opt`
- **#16:** the IndexedDB persistence above, the passphrase decision, and freshness anchored at the Delivery Service (not a local counter).
- **#23/#25:** GIF rendering on the web without copying the bytes into Kotlin.
- **Threat model (#8):** web-client limits (same-origin script, wasm memory), and CSP + SRI as mitigations.
- **CI:** the `MLS web spike` job pinned the `wasm-bindgen-cli` binary by checksum (now the `Gradle check` job does). Dependabot bumps of `wasm-bindgen` will break it until the version and checksum are updated; that's intended.

## Throwaway parts
`spikes/mls-web/` and `kotlin-js-store/wasm/yarn.spikes.lock` were deleted when #11 moved the wiring into `:mls` (ADR-0007).
