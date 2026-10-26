//! GifItUp E2EE core: wraps OpenMLS behind a small API (ADR-0002, ADR-0007).
//!
//! Layout: [`client`], [`identity`] (identity, KeyPackages, state export), [`auth`] (signing the server's
//! login challenge, ADR-0009) and `fingerprint` (the emoji fingerprint of a signature key, ADR-0014) hold the
//! platform-neutral logic, [`group`] the group lifecycle (ADR-0016), [`error`] the
//! closed error codes and panic containment. `uniffi_api` (all non-wasm targets) and `wasm_api`
//! (wasm32) are thin binding layers that run every call through [`error::PanicGuard`].

pub mod auth;
pub mod client;
pub mod error;
mod fingerprint;
pub mod group;
pub mod identity;
mod provider;
mod snapshot;
#[cfg(feature = "test-panic")]
mod test_panic;

#[cfg(not(target_arch = "wasm32"))]
mod uniffi_api;
#[cfg(not(target_arch = "wasm32"))]
pub use uniffi_api::NativeMlsClient;

#[cfg(target_arch = "wasm32")]
mod wasm_api;

#[cfg(not(target_arch = "wasm32"))]
uniffi::setup_scaffolding!();

#[cfg(test)]
mod robustness_tests;
#[cfg(test)]
pub(crate) mod test_support;
