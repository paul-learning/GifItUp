//! uniffi bindings (Android / JVM). Thin wrapper around [`Client`]: every call runs through a
//! [`PanicGuard`], so a panic never reaches Kotlin as a message and a client that panicked once
//! refuses every later call, state export included (ADR-0007).

use std::sync::Arc;

use crate::auth::AuthPurpose;
use crate::client::Client;
use crate::error::{ErrorCode, PanicGuard, catch, install_silent_panic_hook, module_call};
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
    /// export; it is copied and our copy of the passed bytes is wiped (best effort: the uniffi
    /// transfer buffer it arrived in is freed unwiped, ADR-0007).
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

    /// Signs the server's login or registration challenge (ADR-0009). Only this typed form exists: no FFI function
    /// signs arbitrary bytes.
    pub fn sign_auth(
        &self,
        purpose: AuthPurpose,
        challenge: Vec<u8>,
        origin: String,
        subject: String,
    ) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| {
            self.client
                .sign_auth(purpose, &challenge, &origin, &subject)
        })
    }
}
