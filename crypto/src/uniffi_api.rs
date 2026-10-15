//! uniffi bindings (Android / JVM). Thin wrapper around [`Client`]: every call runs through a
//! [`PanicGuard`], so a panic never reaches Kotlin as a message and a client that panicked once
//! refuses every later call, state export included (ADR-0007).

use std::sync::Arc;

use crate::client::Client;
use crate::error::{ErrorCode, PanicGuard, catch, install_silent_panic_hook};
use crate::group::AddMemberResult;

/// One MLS client (one device). See [`Client`].
#[derive(uniffi::Object)]
pub struct NativeMlsClient {
    guard: PanicGuard,
    client: Client,
}

impl NativeMlsClient {
    fn wrap(client: Client) -> Arc<Self> {
        Arc::new(Self {
            guard: PanicGuard::default(),
            client,
        })
    }
}

#[uniffi::export]
impl NativeMlsClient {
    /// Creates a new identity with a fresh signature key and a BasicCredential for `username`.
    #[uniffi::constructor]
    pub fn new(username: String) -> Result<Arc<Self>, ErrorCode> {
        install_silent_panic_hook();
        catch(|| Client::new(username)).map(Self::wrap)
    }

    /// Restores a client from [`NativeMlsClient::export_state`] output.
    #[uniffi::constructor]
    pub fn restore(state: Vec<u8>) -> Result<Arc<Self>, ErrorCode> {
        install_silent_panic_hook();
        catch(|| Client::restore(&state)).map(Self::wrap)
    }

    pub fn signature_public_key(&self) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| Ok(self.client.signature_public_key()))
    }

    pub fn create_key_package(&self) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.client.create_key_package())
    }

    /// SPIKE: plaintext secret material until PR 2 of #11 encrypts it (ADR-0007).
    pub fn export_state(&self) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.client.export_state())
    }

    /// SPIKE (#3), removed with the spikes; group ops come with #22.
    pub fn create_group(&self) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.client.create_group())
    }

    /// SPIKE (#3), removed with the spikes; group ops come with #22.
    pub fn add_member(
        &self,
        group_id: Vec<u8>,
        key_package: Vec<u8>,
    ) -> Result<AddMemberResult, ErrorCode> {
        self.guard
            .run(|| self.client.add_member(&group_id, &key_package))
    }

    /// SPIKE (#3), removed with the spikes; group ops come with #22.
    pub fn join_from_welcome(&self, welcome: Vec<u8>) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.client.join_from_welcome(&welcome))
    }

    /// SPIKE (#3), removed with the spikes; group ops come with #22.
    pub fn encrypt(&self, group_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Vec<u8>, ErrorCode> {
        self.guard
            .run(|| self.client.encrypt(&group_id, &plaintext))
    }

    /// SPIKE (#3), removed with the spikes; group ops come with #22.
    pub fn process(
        &self,
        group_id: Vec<u8>,
        message: Vec<u8>,
    ) -> Result<Option<Vec<u8>>, ErrorCode> {
        self.guard.run(|| self.client.process(&group_id, &message))
    }

    /// SPIKE (#3), removed with the spikes; group ops come with #22.
    pub fn member_count(&self, group_id: Vec<u8>) -> Result<u32, ErrorCode> {
        self.guard.run(|| self.client.member_count(&group_id))
    }
}
