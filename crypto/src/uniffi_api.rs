//! uniffi bindings (Android / JVM). Thin wrapper around [`Client`]: every call runs through a
//! [`PanicGuard`], so a panic never reaches Kotlin as a message and a client that panicked once
//! refuses every later call, state export included (ADR-0007).

use std::sync::Arc;

use crate::client::Client;
use crate::error::{ErrorCode, PanicGuard, catch, install_silent_panic_hook, module_call};
use crate::group::AddMemberResult;
use crate::identity::{CreatedKeyPackage, PublicIdentity};
use crate::snapshot::take_key;

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
    /// Creates a new identity for `username`. `snapshot_key` (32 bytes) encrypts every state
    /// export; it is copied and the passed buffer is wiped on the Rust side.
    #[uniffi::constructor]
    pub fn new(username: String, snapshot_key: Vec<u8>) -> Result<Arc<Self>, ErrorCode> {
        install_silent_panic_hook();
        module_call(|| catch(|| Client::new(username, take_key(snapshot_key)?))).map(Self::wrap)
    }

    /// Restores a client from [`NativeMlsClient::export_state`] output and the same key.
    #[uniffi::constructor]
    pub fn restore(state: Vec<u8>, snapshot_key: Vec<u8>) -> Result<Arc<Self>, ErrorCode> {
        install_silent_panic_hook();
        module_call(|| catch(|| Client::restore(&state, take_key(snapshot_key)?))).map(Self::wrap)
    }

    pub fn public_identity(&self) -> Result<PublicIdentity, ErrorCode> {
        self.guard.run(|| Ok(self.client.public_identity()))
    }

    pub fn create_key_packages(&self, count: u32) -> Result<Vec<CreatedKeyPackage>, ErrorCode> {
        self.guard.run(|| self.client.create_key_packages(count))
    }

    pub fn prune_expired_key_packages(&self) -> Result<u32, ErrorCode> {
        self.guard.run(|| self.client.prune_expired_key_packages())
    }

    pub fn inspect_key_package(
        &self,
        key_package: Vec<u8>,
        expected_username: String,
    ) -> Result<PublicIdentity, ErrorCode> {
        self.guard.run(|| {
            self.client
                .inspect_key_package(&key_package, &expected_username)
        })
    }

    /// The whole client state, encrypted with the snapshot key.
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
