//! wasm-bindgen bindings (browser). Thin wrapper around [`Client`].
//!
//! Byte arrays cross the boundary as `Uint8Array` (copied). An error becomes a JS `Error` whose
//! message is exactly an [`ErrorCode`] name and nothing else. A panic is a wasm trap here (no
//! unwinding on wasm32): it surfaces in JS as `WebAssembly.RuntimeError`, which the Kotlin side
//! treats as "this module instance is dead" (ADR-0007).

use wasm_bindgen::prelude::*;

use crate::client::Client;
use crate::error::{ErrorCode, PanicGuard, catch, install_silent_panic_hook, module_call};
use crate::identity::{CreatedKeyPackage, PublicIdentity};
use crate::snapshot::take_key;

fn js_err(code: ErrorCode) -> JsError {
    JsError::new(&code.to_string())
}

/// See [`PublicIdentity`]. Getters copy bytes the caller already received (no MLS state).
#[wasm_bindgen(js_name = PublicIdentity)]
pub struct WasmPublicIdentity(PublicIdentity);

#[wasm_bindgen(js_class = PublicIdentity)]
impl WasmPublicIdentity {
    #[wasm_bindgen(getter)]
    pub fn username(&self) -> String {
        self.0.username.clone()
    }

    #[wasm_bindgen(getter, js_name = signaturePublicKey)]
    pub fn signature_public_key(&self) -> Vec<u8> {
        self.0.signature_public_key.clone()
    }
}

/// See [`CreatedKeyPackage`]. Getters copy bytes the caller already received (no MLS state).
#[wasm_bindgen(js_name = CreatedKeyPackage)]
pub struct WasmCreatedKeyPackage(CreatedKeyPackage);

#[wasm_bindgen(js_class = CreatedKeyPackage)]
impl WasmCreatedKeyPackage {
    #[wasm_bindgen(getter, js_name = keyPackage)]
    pub fn key_package(&self) -> Vec<u8> {
        self.0.key_package.clone()
    }

    #[wasm_bindgen(getter, js_name = keyPackageRef)]
    pub fn key_package_ref(&self) -> Vec<u8> {
        self.0.key_package_ref.clone()
    }

    /// Unix seconds, as a JS number (exact below 2^53).
    #[wasm_bindgen(getter, js_name = notAfter)]
    pub fn not_after(&self) -> f64 {
        self.0.not_after as f64
    }
}

/// One MLS client (one browser profile). See [`Client`].
#[wasm_bindgen]
pub struct NativeMlsClient {
    guard: PanicGuard,
    client: Client,
}

impl NativeMlsClient {
    fn wrap(client: Client) -> Self {
        Self {
            guard: PanicGuard::default(),
            client,
        }
    }
}

#[wasm_bindgen]
impl NativeMlsClient {
    /// Creates a new identity for `username`. `snapshot_key` (32 bytes) encrypts every state
    /// export; it is copied and its copy in wasm memory is wiped (best effort, ADR-0007).
    #[wasm_bindgen(constructor)]
    pub fn new(username: String, snapshot_key: Vec<u8>) -> Result<NativeMlsClient, JsError> {
        install_silent_panic_hook();
        module_call(|| catch(|| Client::new(username, take_key(snapshot_key)?)))
            .map(Self::wrap)
            .map_err(js_err)
    }

    /// Restores a client from [`NativeMlsClient::export_state`] output and the same key.
    pub fn restore(state: &[u8], snapshot_key: Vec<u8>) -> Result<NativeMlsClient, JsError> {
        install_silent_panic_hook();
        module_call(|| catch(|| Client::restore(state, take_key(snapshot_key)?)))
            .map(Self::wrap)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = publicIdentity)]
    pub fn public_identity(&self) -> Result<WasmPublicIdentity, JsError> {
        self.guard
            .run(|| Ok(self.client.public_identity()))
            .map(WasmPublicIdentity)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = createKeyPackages)]
    pub fn create_key_packages(&self, count: u32) -> Result<Vec<WasmCreatedKeyPackage>, JsError> {
        self.guard
            .run(|| self.client.create_key_packages(count))
            .map(|kps| kps.into_iter().map(WasmCreatedKeyPackage).collect())
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = pruneExpiredKeyPackages)]
    pub fn prune_expired_key_packages(&self) -> Result<u32, JsError> {
        self.guard
            .run(|| self.client.prune_expired_key_packages())
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = inspectKeyPackage)]
    pub fn inspect_key_package(
        &self,
        key_package: &[u8],
        expected_username: &str,
    ) -> Result<WasmPublicIdentity, JsError> {
        self.guard
            .run(|| {
                self.client
                    .inspect_key_package(key_package, expected_username)
            })
            .map(WasmPublicIdentity)
            .map_err(js_err)
    }

    /// The whole client state, encrypted with the snapshot key.
    #[wasm_bindgen(js_name = exportState)]
    pub fn export_state(&self) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.export_state())
            .map_err(js_err)
    }
}
