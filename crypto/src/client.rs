//! Platform-neutral MLS client (one device): identity, state export/restore. KeyPackages live in
//! [`crate::identity`]. The FFI layers (`uniffi_api` for Android/JVM, `wasm_api` for the web) wrap
//! it with [`PanicGuard`].
//!
//! [`PanicGuard`]: crate::error::PanicGuard

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, RwLock};

use openmls::prelude::tls_codec::{Deserialize, Serialize};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_memory_storage::MemoryStorage;
use openmls_rust_crypto::RustCrypto;
use openmls_traits::OpenMlsProvider;

use crate::error::{ErrorCode, mls};
use crate::identity::is_valid_username;
use crate::provider::Provider;
use crate::snapshot::{Snapshot, SnapshotKey};

/// Ciphersuite fixed by ADR-0002 (mandatory-to-implement).
pub(crate) const CIPHERSUITE: Ciphersuite =
    Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

/// One MLS client (one device): provider, signature key pair, credential, all its groups, its
/// unused KeyPackages and the key its state snapshots are encrypted with.
pub struct Client {
    pub(crate) provider: Provider,
    pub(crate) username: String,
    pub(crate) signer: SignatureKeyPair,
    pub(crate) credential: CredentialWithKey,
    pub(crate) groups: Mutex<HashMap<Vec<u8>, MlsGroup>>,
    pub(crate) key_package_refs: Mutex<Vec<KeyPackageRef>>,
    /// Commits and welcomes processed but not yet decided on (ADR-0016). Never exported.
    pub(crate) staging: Mutex<crate::group::Staging>,
    snapshot_key: SnapshotKey,
}

impl Client {
    /// Creates a new identity with a fresh Ed25519 signature key and a BasicCredential for
    /// `username`. Snapshots of this client are encrypted with `snapshot_key`.
    pub fn new(username: String, snapshot_key: SnapshotKey) -> Result<Self, ErrorCode> {
        if !is_valid_username(&username) {
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
            key_package_refs: Mutex::new(Vec::new()),
            staging: Mutex::new(crate::group::Staging::default()),
            snapshot_key,
        })
    }

    /// The fingerprint of a signature public key (ADR-0014): 12 indices into the app's emoji table. Needs no client
    /// state; it is a method so it goes through the same panic guard as every other call.
    pub fn fingerprint(&self, signature_key: &[u8]) -> Result<Vec<u8>, ErrorCode> {
        crate::fingerprint::fingerprint(self.provider.crypto(), signature_key)
    }

    /// Exports the complete client state (identity, groups, KeyPackages, key material),
    /// encrypted with the snapshot key (format in [`crate::snapshot`]).
    ///
    /// Refused with [`ErrorCode::PendingOperation`] while a staged commit or welcome exists: processing a message
    /// consumes secrets that the snapshot already holds, so a snapshot without the staged item could never be
    /// completed (ADR-0016).
    pub fn export_state(&self) -> Result<Vec<u8>, ErrorCode> {
        if !self.lock_staging()?.is_empty() {
            return Err(ErrorCode::PendingOperation);
        }
        let groups = self.lock_groups()?;
        let key_package_refs = self.lock_key_package_refs()?;
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
            key_package_refs: encode_refs(&key_package_refs)?,
            values: values.clone(),
        }
        .seal(&self.snapshot_key, self.provider.crypto())
    }

    /// Restores a client from [`Client::export_state`] output and the same key.
    pub fn restore(state: &[u8], snapshot_key: SnapshotKey) -> Result<Self, ErrorCode> {
        let snapshot = Snapshot::open(state, &snapshot_key, &RustCrypto::default())?;
        Self::from_snapshot(snapshot, snapshot_key)
    }

    fn from_snapshot(mut snapshot: Snapshot, snapshot_key: SnapshotKey) -> Result<Self, ErrorCode> {
        let username = std::mem::take(&mut snapshot.username);
        if !is_valid_username(&username) {
            return Err(ErrorCode::SnapshotInvalid);
        }
        let mut provider = Provider {
            crypto: RustCrypto::default(),
            storage: MemoryStorage {
                values: RwLock::new(std::mem::take(&mut snapshot.values)),
            },
        };
        // If a check fails there is no Client yet whose Drop would wipe the storage (#65 SEC-7).
        let (signer, groups, refs) = load_state(&provider, &snapshot, &username)
            .inspect_err(|_| wipe(&mut provider.storage))?;
        let credential = credential_with_key(&username, &signer);
        Ok(Self {
            provider,
            username,
            signer,
            credential,
            groups: Mutex::new(groups),
            key_package_refs: Mutex::new(refs),
            staging: Mutex::new(crate::group::Staging::default()),
            snapshot_key,
        })
    }

    pub(crate) fn lock_groups(
        &self,
    ) -> Result<MutexGuard<'_, HashMap<Vec<u8>, MlsGroup>>, ErrorCode> {
        self.groups.lock().map_err(|_| ErrorCode::Poisoned)
    }

    pub(crate) fn lock_staging(&self) -> Result<MutexGuard<'_, crate::group::Staging>, ErrorCode> {
        self.staging.lock().map_err(|_| ErrorCode::Poisoned)
    }

    pub(crate) fn lock_key_package_refs(
        &self,
    ) -> Result<MutexGuard<'_, Vec<KeyPackageRef>>, ErrorCode> {
        self.key_package_refs
            .lock()
            .map_err(|_| ErrorCode::Poisoned)
    }
}

/// Best effort (#65 SEC-2): wipes every stored value (private keys, group secrets) when the
/// client goes away, e.g. on `close()`. Entries OpenMLS deleted earlier (a forgotten group, a
/// used or pruned KeyPackage) were freed without wiping; that is outside our control.
impl Drop for Client {
    fn drop(&mut self) {
        wipe(&mut self.provider.storage);
    }
}

fn wipe(storage: &mut MemoryStorage) {
    let values = storage
        .values
        .get_mut()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    values.values_mut().for_each(zeroize::Zeroize::zeroize);
}

type LoadedState = (
    SignatureKeyPair,
    HashMap<Vec<u8>, MlsGroup>,
    Vec<KeyPackageRef>,
);

/// Reads the signing key, every group (with its leaf checks) and the KeyPackage refs.
fn load_state(
    provider: &Provider,
    snapshot: &Snapshot,
    username: &str,
) -> Result<LoadedState, ErrorCode> {
    let signer = SignatureKeyPair::read(
        provider.storage(),
        &snapshot.signature_public_key,
        CIPHERSUITE.signature_algorithm(),
    )
    .ok_or(ErrorCode::SnapshotInvalid)?;
    let groups = load_groups(provider, &snapshot.group_ids, username, &signer)?;
    let refs = decode_refs(&snapshot.key_package_refs)?;
    Ok((signer, groups, refs))
}

/// Loads every group and checks that our own leaf in it carries this identity: the username in
/// its credential and the signature key we hold (#11 plan review SEC-10).
fn load_groups(
    provider: &Provider,
    group_ids: &[Vec<u8>],
    username: &str,
    signer: &SignatureKeyPair,
) -> Result<HashMap<Vec<u8>, MlsGroup>, ErrorCode> {
    let mut groups = HashMap::new();
    for id in group_ids {
        let group = MlsGroup::load(provider.storage(), &GroupId::from_slice(id))
            .map_err(|_| ErrorCode::SnapshotInvalid)?
            .ok_or(ErrorCode::SnapshotInvalid)?;
        let leaf = group.own_leaf_node().ok_or(ErrorCode::SnapshotInvalid)?;
        let identity = BasicCredential::try_from(leaf.credential().clone())
            .map_err(|_| ErrorCode::SnapshotInvalid)?;
        if identity.identity() != username.as_bytes()
            || leaf.signature_key().as_slice() != signer.to_public_vec().as_slice()
        {
            return Err(ErrorCode::SnapshotInvalid);
        }
        groups.insert(id.clone(), group);
    }
    Ok(groups)
}

/// Own KeyPackage refs travel in the snapshot in their TLS encoding (OpenMLS only builds a ref
/// from raw bytes in its test builds).
fn encode_refs(refs: &[KeyPackageRef]) -> Result<Vec<Vec<u8>>, ErrorCode> {
    refs.iter()
        .map(|r| r.tls_serialize_detached().map_err(|_| ErrorCode::Internal))
        .collect()
}

fn decode_refs(encoded: &[Vec<u8>]) -> Result<Vec<KeyPackageRef>, ErrorCode> {
    encoded
        .iter()
        .map(|r| KeyPackageRef::tls_deserialize_exact(r).map_err(|_| ErrorCode::SnapshotInvalid))
        .collect()
}

fn credential_with_key(username: &str, signer: &SignatureKeyPair) -> CredentialWithKey {
    CredentialWithKey {
        credential: BasicCredential::new(username.as_bytes().to_vec()).into(),
        signature_key: signer.to_public_vec().into(),
    }
}

#[cfg(test)]
mod tests {
    use zeroize::Zeroizing;

    use super::*;
    use crate::snapshot::KEY_LEN;
    use crate::test_support::{client, key, pair};

    /// Re-seals Bob's state with a changed inner field, as a buggy migration could (the AEAD
    /// keeps outsiders out, so only our own code can produce such a snapshot).
    fn resealed(client: &Client, change: impl FnOnce(&mut Snapshot)) -> Vec<u8> {
        let sealed = client.export_state().unwrap();
        let mut snapshot = Snapshot::open(&sealed, &key(), &RustCrypto::default()).unwrap();
        change(&mut snapshot);
        snapshot.seal(&key(), &RustCrypto::default()).unwrap()
    }

    #[test]
    fn restore_keeps_identity_groups_and_key_packages() {
        let (_alice, bob, group_id) = pair();
        bob.create_key_packages(2).unwrap();
        let restored = Client::restore(&bob.export_state().unwrap(), key()).unwrap();
        assert_eq!(restored.public_identity(), bob.public_identity());
        assert_eq!(restored.member_count(&group_id).unwrap(), 2);
        assert_eq!(restored.lock_key_package_refs().unwrap().len(), 2);
    }

    #[test]
    fn wipe_zeroes_every_stored_value() {
        let mut storage = MemoryStorage {
            values: RwLock::new(HashMap::from([(vec![1], vec![0xAB; 8])])),
        };
        wipe(&mut storage);
        // `Vec::zeroize` overwrites the bytes, then truncates the vector to length 0.
        assert!(storage.values.read().unwrap()[&vec![1]].is_empty());
    }

    #[test]
    fn restore_needs_the_same_key() {
        let alice = client("alice");
        let other = Zeroizing::new([8; KEY_LEN]);
        assert_eq!(
            Client::restore(&alice.export_state().unwrap(), other).err(),
            Some(ErrorCode::SnapshotAuth)
        );
    }

    /// #11 plan review SEC-10: the own leaf of every group must carry this identity.
    #[test]
    fn restore_rejects_a_username_that_does_not_match_the_group_leaves() {
        let (_alice, bob, _group_id) = pair();
        let renamed = resealed(&bob, |s| s.username = "mallory".into());
        assert_eq!(
            Client::restore(&renamed, key()).err(),
            Some(ErrorCode::SnapshotInvalid)
        );
        let invalid = resealed(&bob, |s| s.username = "Bob".into());
        assert_eq!(
            Client::restore(&invalid, key()).err(),
            Some(ErrorCode::SnapshotInvalid)
        );
    }

    #[test]
    fn restore_rejects_unknown_groups_and_keys() {
        let (_alice, bob, _group_id) = pair();
        let ghost_group = resealed(&bob, |s| s.group_ids.push(vec![1, 2, 3]));
        assert_eq!(
            Client::restore(&ghost_group, key()).err(),
            Some(ErrorCode::SnapshotInvalid)
        );
        let other_key = resealed(&bob, |s| s.signature_public_key = vec![0; 32]);
        assert_eq!(
            Client::restore(&other_key, key()).err(),
            Some(ErrorCode::SnapshotInvalid)
        );
    }
}
