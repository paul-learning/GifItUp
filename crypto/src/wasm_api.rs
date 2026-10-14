//! wasm-bindgen bindings (browser). Thin wrapper around [`crate::core::Client`].
//!
//! Byte arrays cross the boundary as `Uint8Array` (copied). Errors become JS `Error`s.

use wasm_bindgen::prelude::*;

use crate::core::{Client, CryptoError};

fn js_err(e: CryptoError) -> JsError {
    JsError::new(&e.to_string())
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
pub struct MlsClient(Client);

#[wasm_bindgen]
impl MlsClient {
    /// Creates a new identity with a fresh signature key and a BasicCredential for `username`.
    #[wasm_bindgen(constructor)]
    pub fn new(username: String) -> Result<MlsClient, JsError> {
        Client::new(username).map(Self).map_err(js_err)
    }

    /// Restores a client from [`MlsClient::export_state`] output.
    pub fn restore(state: &[u8]) -> Result<MlsClient, JsError> {
        Client::restore(state).map(Self).map_err(js_err)
    }

    #[wasm_bindgen(js_name = signaturePublicKey)]
    pub fn signature_public_key(&self) -> Vec<u8> {
        self.0.signature_public_key()
    }

    #[wasm_bindgen(js_name = createKeyPackage)]
    pub fn create_key_package(&self) -> Result<Vec<u8>, JsError> {
        self.0.create_key_package().map_err(js_err)
    }

    #[wasm_bindgen(js_name = createGroup)]
    pub fn create_group(&self) -> Result<Vec<u8>, JsError> {
        self.0.create_group().map_err(js_err)
    }

    #[wasm_bindgen(js_name = addMember)]
    pub fn add_member(
        &self,
        group_id: &[u8],
        key_package: &[u8],
    ) -> Result<AddMemberResult, JsError> {
        let r = self.0.add_member(group_id, key_package).map_err(js_err)?;
        Ok(AddMemberResult {
            commit: r.commit,
            welcome: r.welcome,
        })
    }

    #[wasm_bindgen(js_name = joinFromWelcome)]
    pub fn join_from_welcome(&self, welcome: &[u8]) -> Result<Vec<u8>, JsError> {
        self.0.join_from_welcome(welcome).map_err(js_err)
    }

    pub fn encrypt(&self, group_id: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, JsError> {
        self.0.encrypt(group_id, plaintext).map_err(js_err)
    }

    /// Returns the plaintext for application messages, `undefined` for handshake messages.
    pub fn process(&self, group_id: &[u8], message: &[u8]) -> Result<Option<Vec<u8>>, JsError> {
        self.0.process(group_id, message).map_err(js_err)
    }

    #[wasm_bindgen(js_name = memberCount)]
    pub fn member_count(&self, group_id: &[u8]) -> Result<u32, JsError> {
        self.0.member_count(group_id).map_err(js_err)
    }

    /// SPIKE: plaintext secret material, see [`Client::export_state`].
    #[wasm_bindgen(js_name = exportState)]
    pub fn export_state(&self) -> Result<Vec<u8>, JsError> {
        self.0.export_state().map_err(js_err)
    }
}
