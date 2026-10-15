//! Platform-neutral MLS client (one device): identity, KeyPackages, state export/restore. The FFI
//! layers (`uniffi_api` for Android/JVM, `wasm_api` for the web) wrap it with [`PanicGuard`].
//!
//! [`PanicGuard`]: crate::error::PanicGuard

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, RwLock};

use openmls::prelude::tls_codec::Serialize;
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_memory_storage::MemoryStorage;
use openmls_rust_crypto::RustCrypto;
use openmls_traits::OpenMlsProvider;

use crate::error::{ErrorCode, mls};
use crate::provider::Provider;
use crate::snapshot::Snapshot;

/// Ciphersuite fixed by ADR-0002 (mandatory-to-implement).
pub(crate) const CIPHERSUITE: Ciphersuite =
    Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

/// One MLS client (one device): provider, signature key pair, credential and all its groups.
pub struct Client {
    pub(crate) provider: Provider,
    pub(crate) username: String,
    pub(crate) signer: SignatureKeyPair,
    pub(crate) credential: CredentialWithKey,
    pub(crate) groups: Mutex<HashMap<Vec<u8>, MlsGroup>>,
}

impl Client {
    /// Creates a new identity with a fresh Ed25519 signature key and a BasicCredential for `username`.
    pub fn new(username: String) -> Result<Self, ErrorCode> {
        if username.is_empty() {
            return Err(ErrorCode::InvalidUsername);
        }
        let provider = Provider::default();
        let signer = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).map_err(mls)?;
        signer.store(provider.storage()).map_err(mls)?;
        let credential = credential_with_key(&username, &signer);
        Ok(Self {
            provider,
            username,
            signer,
            credential,
            groups: Mutex::new(HashMap::new()),
        })
    }

    /// Public signature key (for fingerprints / TOFU).
    pub fn signature_public_key(&self) -> Vec<u8> {
        self.signer.to_public_vec()
    }

    /// Creates a single-use KeyPackage, serialized as an MLS message (to be uploaded to the directory).
    pub fn create_key_package(&self) -> Result<Vec<u8>, ErrorCode> {
        let bundle = KeyPackage::builder()
            .build(
                CIPHERSUITE,
                &self.provider,
                &self.signer,
                self.credential.clone(),
            )
            .map_err(mls)?;
        MlsMessageOut::from(bundle.key_package().clone())
            .tls_serialize_detached()
            .map_err(mls)
    }

    /// Exports the complete client state (identity, all group state, key material) as one blob.
    ///
    /// **Plaintext secret material** until PR 2 of #11 encrypts it (ADR-0007).
    pub fn export_state(&self) -> Result<Vec<u8>, ErrorCode> {
        let groups = self.lock_groups()?;
        let values = self
            .provider
            .storage
            .values
            .read()
            .map_err(|_| ErrorCode::Poisoned)?;
        Snapshot {
            username: self.username.clone(),
            signature_public_key: self.signer.to_public_vec(),
            group_ids: groups.keys().cloned().collect(),
            values: values.clone(),
        }
        .encode()
    }

    /// Restores a client from [`Client::export_state`] output.
    pub fn restore(state: &[u8]) -> Result<Self, ErrorCode> {
        Self::from_snapshot(Snapshot::decode(state)?)
    }

    fn from_snapshot(snapshot: Snapshot) -> Result<Self, ErrorCode> {
        let provider = Provider {
            crypto: RustCrypto::default(),
            storage: MemoryStorage {
                values: RwLock::new(snapshot.values),
            },
        };
        let signer = SignatureKeyPair::read(
            provider.storage(),
            &snapshot.signature_public_key,
            CIPHERSUITE.signature_algorithm(),
        )
        .ok_or(ErrorCode::SnapshotInvalid)?;
        let mut groups = HashMap::new();
        for id in snapshot.group_ids {
            let group = MlsGroup::load(provider.storage(), &GroupId::from_slice(&id))
                .map_err(|_| ErrorCode::SnapshotInvalid)?
                .ok_or(ErrorCode::SnapshotInvalid)?;
            groups.insert(id, group);
        }
        let credential = credential_with_key(&snapshot.username, &signer);
        Ok(Self {
            provider,
            username: snapshot.username,
            signer,
            credential,
            groups: Mutex::new(groups),
        })
    }

    pub(crate) fn lock_groups(
        &self,
    ) -> Result<MutexGuard<'_, HashMap<Vec<u8>, MlsGroup>>, ErrorCode> {
        self.groups.lock().map_err(|_| ErrorCode::Poisoned)
    }
}

fn credential_with_key(username: &str, signer: &SignatureKeyPair) -> CredentialWithKey {
    CredentialWithKey {
        credential: BasicCredential::new(username.as_bytes().to_vec()).into(),
        signature_key: signer.to_public_vec().into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_empty_username() {
        assert!(matches!(
            Client::new(String::new()),
            Err(ErrorCode::InvalidUsername)
        ));
    }

    #[test]
    fn restored_identity_keeps_its_signature_key() {
        let alice = Client::new("alice".into()).unwrap();
        let restored = Client::restore(&alice.export_state().unwrap()).unwrap();
        assert_eq!(
            restored.signature_public_key(),
            alice.signature_public_key()
        );
        assert!(!restored.create_key_package().unwrap().is_empty());
    }

    #[test]
    fn rejects_garbage_snapshots() {
        assert!(matches!(
            Client::restore(b"nope"),
            Err(ErrorCode::SnapshotInvalid)
        ));
        let mut snapshot = Client::new("alice".into()).unwrap().export_state().unwrap();
        snapshot.truncate(snapshot.len() - 1);
        assert!(Client::restore(&snapshot).is_err());
    }
}
