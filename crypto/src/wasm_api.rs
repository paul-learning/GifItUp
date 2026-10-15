//! wasm-bindgen bindings (browser). Thin wrapper around [`Client`].
//!
//! Byte arrays cross the boundary as `Uint8Array` (copied). An error becomes a JS `Error` whose
//! message is exactly an [`ErrorCode`] name and nothing else. A panic is a wasm trap here (no
//! unwinding on wasm32): it surfaces in JS as `WebAssembly.RuntimeError`, which the Kotlin side
//! treats as "this module instance is dead" (ADR-0007).

use wasm_bindgen::prelude::*;

use crate::client::Client;
use crate::error::{ErrorCode, PanicGuard, catch, install_silent_panic_hook, module_call};

fn js_err(code: ErrorCode) -> JsError {
    JsError::new(&code.to_string())
}

/// Result of adding a member: the Commit goes to existing members, the Welcome to the new one.
#[wasm_bindgen]
pub struct AddMemberResult {
    commit: Vec<u8>,
    welcome: Vec<u8>,
}

#[wasm_bindgen]
impl AddMemberResult {
    #[wasm_bindgen(getter)]
    pub fn commit(&self) -> Vec<u8> {
        self.commit.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn welcome(&self) -> Vec<u8> {
        self.welcome.clone()
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
    /// Creates a new identity with a fresh signature key and a BasicCredential for `username`.
    #[wasm_bindgen(constructor)]
    pub fn new(username: String) -> Result<NativeMlsClient, JsError> {
        install_silent_panic_hook();
        module_call(|| catch(|| Client::new(username)))
            .map(Self::wrap)
            .map_err(js_err)
    }

    /// Restores a client from [`NativeMlsClient::export_state`] output.
    pub fn restore(state: &[u8]) -> Result<NativeMlsClient, JsError> {
        install_silent_panic_hook();
        module_call(|| catch(|| Client::restore(state)))
            .map(Self::wrap)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = signaturePublicKey)]
    pub fn signature_public_key(&self) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| Ok(self.client.signature_public_key()))
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = createKeyPackage)]
    pub fn create_key_package(&self) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.create_key_package())
            .map_err(js_err)
    }

    /// SPIKE: plaintext secret material until PR 2 of #11 encrypts it (ADR-0007).
    #[wasm_bindgen(js_name = exportState)]
    pub fn export_state(&self) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.export_state())
            .map_err(js_err)
    }

    /// SPIKE (#4), removed with the spikes; group ops come with #22.
    #[wasm_bindgen(js_name = createGroup)]
    pub fn create_group(&self) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.create_group())
            .map_err(js_err)
    }

    /// SPIKE (#4), removed with the spikes; group ops come with #22.
    #[wasm_bindgen(js_name = addMember)]
    pub fn add_member(
        &self,
        group_id: &[u8],
        key_package: &[u8],
    ) -> Result<AddMemberResult, JsError> {
        let r = self
            .guard
            .run(|| self.client.add_member(group_id, key_package))
            .map_err(js_err)?;
        Ok(AddMemberResult {
            commit: r.commit,
            welcome: r.welcome,
        })
    }

    /// SPIKE (#4), removed with the spikes; group ops come with #22.
    #[wasm_bindgen(js_name = joinFromWelcome)]
    pub fn join_from_welcome(&self, welcome: &[u8]) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.join_from_welcome(welcome))
            .map_err(js_err)
    }

    /// SPIKE (#4), removed with the spikes; group ops come with #22.
    pub fn encrypt(&self, group_id: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.encrypt(group_id, plaintext))
            .map_err(js_err)
    }

    /// SPIKE (#4), removed with the spikes. Returns the plaintext for application messages,
    /// `undefined` for handshake messages.
    pub fn process(&self, group_id: &[u8], message: &[u8]) -> Result<Option<Vec<u8>>, JsError> {
        self.guard
            .run(|| self.client.process(group_id, message))
            .map_err(js_err)
    }

    /// SPIKE (#4), removed with the spikes; group ops come with #22.
    #[wasm_bindgen(js_name = memberCount)]
    pub fn member_count(&self, group_id: &[u8]) -> Result<u32, JsError> {
        self.guard
            .run(|| self.client.member_count(group_id))
            .map_err(js_err)
    }
}
