//! uniffi bindings (Android / JVM). Thin wrapper around [`Client`]: every call runs through a
//! [`PanicGuard`], so a panic never reaches Kotlin as a message and a client that panicked once
//! refuses every later call, state export included (ADR-0007).

use std::sync::Arc;

use crate::auth::AuthPurpose;
use crate::client::Client;
use crate::error::{ErrorCode, PanicGuard, catch, install_silent_panic_hook, module_call};
use crate::group::{EncryptedMessage, IncomingMessage, LeafInfo, PreparedCommit, WelcomeInfo};
use crate::history::HistoryCipher;
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

    /// The fingerprint of a 32-byte signature public key: 12 indices into the app's emoji table (ADR-0014).
    pub fn fingerprint(&self, signature_key: Vec<u8>) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.client.fingerprint(&signature_key))
    }

    /// The whole client state, encrypted with the snapshot key.
    pub fn export_state(&self) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.client.export_state())
    }

    /// The whole client state under a **new** key; the key switches only if the export succeeded (ADR-0011).
    pub fn rekey_and_export(&self, new_key: Vec<u8>) -> Result<Vec<u8>, ErrorCode> {
        self.guard
            .run(|| self.client.rekey_and_export(take_key(new_key)?))
    }

    /// Exports made under the current snapshot key.
    pub fn exports_under_key(&self) -> Result<u64, ErrorCode> {
        self.guard.run(|| self.client.exports_under_key())
    }

    /// The sync engine's opaque state, exported inside the next snapshot (at most 256 KiB).
    pub fn set_app_state(&self, state: Vec<u8>) -> Result<(), ErrorCode> {
        self.guard.run(|| self.client.set_app_state(state))
    }

    pub fn app_state(&self) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.client.app_state())
    }

    // Group lifecycle (ADR-0016). Every commit is prepared, then merged or cleared; every incoming commit and
    // welcome is staged, then merged/joined or discarded.

    pub fn create_group(&self) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.client.create_group())
    }

    pub fn prepare_add(
        &self,
        group_id: Vec<u8>,
        key_packages: Vec<Vec<u8>>,
    ) -> Result<PreparedCommit, ErrorCode> {
        self.guard
            .run(|| self.client.prepare_add(&group_id, &key_packages))
    }

    pub fn prepare_remove(
        &self,
        group_id: Vec<u8>,
        leaf_indexes: Vec<u32>,
    ) -> Result<PreparedCommit, ErrorCode> {
        self.guard
            .run(|| self.client.prepare_remove(&group_id, &leaf_indexes))
    }

    pub fn prepare_update(&self, group_id: Vec<u8>) -> Result<PreparedCommit, ErrorCode> {
        self.guard.run(|| self.client.prepare_update(&group_id))
    }

    pub fn merge_pending(&self, group_id: Vec<u8>) -> Result<u64, ErrorCode> {
        self.guard.run(|| self.client.merge_pending(&group_id))
    }

    pub fn clear_pending(&self, group_id: Vec<u8>) -> Result<(), ErrorCode> {
        self.guard.run(|| self.client.clear_pending(&group_id))
    }

    pub fn has_pending_commit(&self, group_id: Vec<u8>) -> Result<bool, ErrorCode> {
        self.guard.run(|| self.client.has_pending_commit(&group_id))
    }

    pub fn encrypt(
        &self,
        group_id: Vec<u8>,
        plaintext: Vec<u8>,
    ) -> Result<EncryptedMessage, ErrorCode> {
        self.guard
            .run(|| self.client.encrypt(&group_id, &plaintext))
    }

    pub fn stage_incoming(
        &self,
        group_id: Vec<u8>,
        message: Vec<u8>,
    ) -> Result<IncomingMessage, ErrorCode> {
        self.guard
            .run(|| self.client.stage_incoming(&group_id, &message))
            .map(IncomingMessage::from)
    }

    pub fn merge_staged(&self, token: u32) -> Result<u64, ErrorCode> {
        self.guard.run(|| self.client.merge_staged(token))
    }

    pub fn discard_staged(&self, token: u32) -> Result<(), ErrorCode> {
        self.guard.run(|| self.client.discard_staged(token))
    }

    pub fn inspect_welcome(
        &self,
        welcome: Vec<u8>,
        expected_group_id: Vec<u8>,
    ) -> Result<WelcomeInfo, ErrorCode> {
        self.guard
            .run(|| self.client.inspect_welcome(&welcome, &expected_group_id))
    }

    pub fn join_welcome(&self, token: u32) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.client.join_welcome(token))
    }

    pub fn discard_welcome(&self, token: u32) -> Result<(), ErrorCode> {
        self.guard.run(|| self.client.discard_welcome(token))
    }

    pub fn forget_group(&self, group_id: Vec<u8>) -> Result<(), ErrorCode> {
        self.guard.run(|| self.client.forget_group(&group_id))
    }

    pub fn members(&self, group_id: Vec<u8>) -> Result<Vec<LeafInfo>, ErrorCode> {
        self.guard.run(|| self.client.members(&group_id))
    }

    pub fn member_count(&self, group_id: Vec<u8>) -> Result<u32, ErrorCode> {
        self.guard.run(|| self.client.member_count(&group_id))
    }

    pub fn epoch(&self, group_id: Vec<u8>) -> Result<u64, ErrorCode> {
        self.guard.run(|| self.client.epoch(&group_id))
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

/// Seals and opens the records of the local message history (ADR-0017), under a key of its own. Separate from the MLS
/// client: its lifetime and its failures don't depend on the group state.
#[derive(uniffi::Object)]
pub struct NativeHistoryCipher {
    guard: PanicGuard,
    cipher: HistoryCipher,
}

#[uniffi::export]
impl NativeHistoryCipher {
    /// `history_key` (32 bytes) is copied and our copy of the passed bytes is wiped (best effort, as for the snapshot
    /// key); `origin` is the account's canonical origin, part of what every record is bound to.
    #[uniffi::constructor]
    pub fn new(history_key: Vec<u8>, origin: String) -> Result<Arc<Self>, ErrorCode> {
        install_silent_panic_hook();
        module_call(|| catch(|| HistoryCipher::new(history_key, &origin))).map(|cipher| {
            Arc::new(Self {
                guard: PanicGuard::default(),
                cipher,
            })
        })
    }

    pub fn group_name(&self, group_id: Vec<u8>) -> Result<Vec<u8>, ErrorCode> {
        self.guard
            .run(|| self.cipher.group_name(&group_id).map(|n| n.to_vec()))
    }

    pub fn message_name(
        &self,
        group_id: Vec<u8>,
        reference: Vec<u8>,
    ) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| {
            self.cipher
                .message_name(&group_id, &reference)
                .map(|n| n.to_vec())
        })
    }

    pub fn read_mark_name(&self, group_id: Vec<u8>) -> Result<Vec<u8>, ErrorCode> {
        self.guard
            .run(|| self.cipher.read_mark_name(&group_id).map(|n| n.to_vec()))
    }

    pub fn seal(&self, name: Vec<u8>, plaintext: Vec<u8>) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.cipher.seal(&name, &plaintext))
    }

    pub fn open(&self, name: Vec<u8>, sealed: Vec<u8>) -> Result<Vec<u8>, ErrorCode> {
        self.guard.run(|| self.cipher.open(&name, &sealed))
    }
}
