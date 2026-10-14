//! GifItUp E2EE core.
//!
//! **SPIKE QUALITY (#3, #4).** Proves that OpenMLS can be wrapped and called from Kotlin on Android
//! (uniffi) and from Kotlin/Wasm in the browser (wasm-bindgen). It performs none of the member
//! validation required by ADR-0002 and its state snapshot is unencrypted. Do not build on it
//! without the follow-up in #11.
//!
//! Layout: [`client`] holds the platform-neutral logic; `uniffi_api` (all non-wasm targets) and
//! `wasm_api` (wasm32) are thin binding layers.

pub mod client;

#[cfg(not(target_arch = "wasm32"))]
mod uniffi_api;
#[cfg(not(target_arch = "wasm32"))]
pub use uniffi_api::MlsClient;

#[cfg(target_arch = "wasm32")]
mod wasm_api;

#[cfg(not(target_arch = "wasm32"))]
uniffi::setup_scaffolding!();
