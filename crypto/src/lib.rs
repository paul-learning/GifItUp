//! GifItUp E2EE core: wraps OpenMLS behind a small API (ADR-0002, ADR-0007).
//!
//! Layout: [`client`] (identity, KeyPackages, state export) and [`group`] (spike group operations,
//! not part of the v1 facade) hold the platform-neutral logic; [`error`] the closed error codes and
//! panic containment; `uniffi_api` (all non-wasm targets) and `wasm_api` (wasm32) are thin binding
//! layers that run every call through [`error::PanicGuard`].

pub mod client;
pub mod error;
pub mod group;
pub mod identity;
mod provider;
mod snapshot;

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
