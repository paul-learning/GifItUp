//! uniffi bindings (Android / JVM). Thin wrapper around [`crate::client::Client`].

use std::sync::Arc;

use crate::client::{AddMemberResult, Client, CryptoError};

/// One MLS client (one device). See [`Client`].
#[derive(uniffi::Object)]
pub struct MlsClient(Client);

#[uniffi::export]
impl MlsClient {
    /// Creates a new identity with a fresh signature key and a BasicCredential for `username`.
    #[uniffi::constructor]
    pub fn new(username: String) -> Result<Arc<Self>, CryptoError> {
        Ok(Arc::new(Self(Client::new(username)?)))
    }

    /// Restores a client from [`MlsClient::export_state`] output.
    #[uniffi::constructor]
    pub fn restore(state: Vec<u8>) -> Result<Arc<Self>, CryptoError> {
        Ok(Arc::new(Self(Client::restore(&state)?)))
    }

    pub fn signature_public_key(&self) -> Vec<u8> {
        self.0.signature_public_key()
    }

    pub fn create_key_package(&self) -> Result<Vec<u8>, CryptoError> {
        self.0.create_key_package()
    }

    pub fn create_group(&self) -> Result<Vec<u8>, CryptoError> {
        self.0.create_group()
    }

    pub fn add_member(
        &self,
        group_id: Vec<u8>,
        key_package: Vec<u8>,
    ) -> Result<AddMemberResult, CryptoError> {
        self.0.add_member(&group_id, &key_package)
    }

    pub fn join_from_welcome(&self, welcome: Vec<u8>) -> Result<Vec<u8>, CryptoError> {
        self.0.join_from_welcome(&welcome)
    }

    pub fn encrypt(&self, group_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Vec<u8>, CryptoError> {
        self.0.encrypt(&group_id, &plaintext)
    }

    pub fn process(
        &self,
        group_id: Vec<u8>,
        message: Vec<u8>,
    ) -> Result<Option<Vec<u8>>, CryptoError> {
        self.0.process(&group_id, &message)
    }

    pub fn member_count(&self, group_id: Vec<u8>) -> Result<u32, CryptoError> {
        self.0.member_count(&group_id)
    }

    /// SPIKE: plaintext secret material, see [`Client::export_state`].
    pub fn export_state(&self) -> Result<Vec<u8>, CryptoError> {
        self.0.export_state()
    }
}
