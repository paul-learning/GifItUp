//! Platform-neutral MLS client. The binding layers (`uniffi_api` for Android, `wasm_api` for the
//! web) are thin wrappers around [`Client`].
//!
//! **SPIKE QUALITY (#3, #4).** See `docs/spikes/openmls-android.md` and `docs/spikes/openmls-web.md`
//! for the requirements #11 must add (validation, typed results, persistence rules, ...).

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, RwLock};

use openmls::prelude::tls_codec::{Deserialize, Serialize};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_memory_storage::MemoryStorage;
use openmls_rust_crypto::RustCrypto;
use openmls_traits::OpenMlsProvider;

/// Ciphersuite fixed by ADR-0002 (mandatory-to-implement).
const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

/// MLS application-message padding (bytes), so ciphertext length does not reveal plaintext length
/// (SEC-2 on #36). Local setting: must be applied to both create and join configs. The real value
/// is decided with the message format in #11/#24.
const PADDING_SIZE: usize = 256;

/// Snapshot format marker and version (spike format, see `export_state`).
const SNAPSHOT_MAGIC: &[u8; 4] = b"GIUS";
const SNAPSHOT_VERSION: u8 = 1;

#[derive(Debug, thiserror::Error)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Error))]
pub enum CryptoError {
    #[error("invalid input: {reason}")]
    InvalidInput { reason: String },
    #[error("unknown group")]
    UnknownGroup,
    #[error("MLS operation failed: {reason}")]
    Mls { reason: String },
}

fn mls_err(e: impl std::fmt::Debug) -> CryptoError {
    CryptoError::Mls {
        reason: format!("{e:?}"),
    }
}

fn input_err(e: impl std::fmt::Debug) -> CryptoError {
    CryptoError::InvalidInput {
        reason: format!("{e:?}"),
    }
}

fn invalid(reason: &str) -> CryptoError {
    CryptoError::InvalidInput {
        reason: reason.into(),
    }
}

/// Result of adding a member: the Commit goes to existing members, the Welcome to the new one.
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Record))]
pub struct AddMemberResult {
    pub commit: Vec<u8>,
    pub welcome: Vec<u8>,
}

/// OpenMLS provider: RustCrypto for crypto/randomness, in-memory key-value storage that can be
/// exported as a snapshot (option A in the Android spike doc).
#[derive(Default)]
struct Provider {
    crypto: RustCrypto,
    storage: MemoryStorage,
}

impl OpenMlsProvider for Provider {
    type CryptoProvider = RustCrypto;
    type RandProvider = RustCrypto;
    type StorageProvider = MemoryStorage;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        &self.crypto
    }

    fn rand(&self) -> &Self::RandProvider {
        &self.crypto
    }
}

/// One MLS client (one device): provider, signature key pair, credential and all its groups.
pub struct Client {
    provider: Provider,
    username: String,
    signer: SignatureKeyPair,
    credential: CredentialWithKey,
    groups: Mutex<HashMap<Vec<u8>, MlsGroup>>,
}

impl Client {
    /// Creates a new identity with a fresh Ed25519 signature key and a BasicCredential for `username`.
    pub fn new(username: String) -> Result<Self, CryptoError> {
        if username.is_empty() {
            return Err(invalid("username must not be empty"));
        }
        let provider = Provider::default();
        let signer = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).map_err(mls_err)?;
        signer.store(provider.storage()).map_err(mls_err)?;
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
    pub fn create_key_package(&self) -> Result<Vec<u8>, CryptoError> {
        let bundle = KeyPackage::builder()
            .build(
                CIPHERSUITE,
                &self.provider,
                &self.signer,
                self.credential.clone(),
            )
            .map_err(mls_err)?;
        MlsMessageOut::from(bundle.key_package().clone())
            .tls_serialize_detached()
            .map_err(mls_err)
    }

    /// Creates a new group with this client as the only member. Returns the group ID.
    pub fn create_group(&self) -> Result<Vec<u8>, CryptoError> {
        let config = MlsGroupCreateConfig::builder()
            .ciphersuite(CIPHERSUITE)
            // Handshake messages travel as PrivateMessage too (ADR-0002: the server does not parse MLS).
            .wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
            .padding_size(PADDING_SIZE)
            .use_ratchet_tree_extension(true)
            .build();
        let group = MlsGroup::new(
            &self.provider,
            &self.signer,
            &config,
            self.credential.clone(),
        )
        .map_err(mls_err)?;
        let id = group.group_id().as_slice().to_vec();
        self.lock_groups()?.insert(id.clone(), group);
        Ok(id)
    }

    /// Adds the owner of `key_package` to the group and merges the commit locally.
    ///
    /// SPIKE: in the real flow the commit is merged only after the Delivery Service accepted it for
    /// the epoch (ADR-0002); here it is merged immediately.
    pub fn add_member(
        &self,
        group_id: &[u8],
        key_package: &[u8],
    ) -> Result<AddMemberResult, CryptoError> {
        let key_package = match MlsMessageIn::tls_deserialize_exact(key_package)
            .map_err(input_err)?
            .extract()
        {
            MlsMessageBodyIn::KeyPackage(kp) => kp
                .validate(self.provider.crypto(), ProtocolVersion::Mls10)
                .map_err(input_err)?,
            _ => return Err(invalid("not a KeyPackage")),
        };
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(CryptoError::UnknownGroup)?;
        let (commit, welcome, _group_info) = group
            .add_members(&self.provider, &self.signer, &[key_package])
            .map_err(mls_err)?;
        group
            .merge_pending_commit(&self.provider)
            .map_err(mls_err)?;
        Ok(AddMemberResult {
            commit: commit.tls_serialize_detached().map_err(mls_err)?,
            welcome: welcome.tls_serialize_detached().map_err(mls_err)?,
        })
    }

    /// Joins a group from a Welcome message. Returns the group ID.
    pub fn join_from_welcome(&self, welcome: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let welcome = match MlsMessageIn::tls_deserialize_exact(welcome)
            .map_err(input_err)?
            .extract()
        {
            MlsMessageBodyIn::Welcome(w) => w,
            _ => return Err(invalid("not a Welcome")),
        };
        let join_config = MlsGroupJoinConfig::builder()
            .wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
            .padding_size(PADDING_SIZE)
            .use_ratchet_tree_extension(true)
            .build();
        let group = StagedWelcome::new_from_welcome(&self.provider, &join_config, welcome, None)
            .map_err(mls_err)?
            .into_group(&self.provider)
            .map_err(mls_err)?;
        let id = group.group_id().as_slice().to_vec();
        self.lock_groups()?.insert(id.clone(), group);
        Ok(id)
    }

    /// Encrypts an application message for the group.
    pub fn encrypt(&self, group_id: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(CryptoError::UnknownGroup)?;
        group
            .create_message(&self.provider, &self.signer, plaintext)
            .map_err(mls_err)?
            .tls_serialize_detached()
            .map_err(mls_err)
    }

    /// Processes an incoming message. Returns the plaintext for application messages, or `None`
    /// for handshake messages (a Commit is merged immediately in this spike).
    pub fn process(&self, group_id: &[u8], message: &[u8]) -> Result<Option<Vec<u8>>, CryptoError> {
        let protocol_message = MlsMessageIn::tls_deserialize_exact(message)
            .map_err(input_err)?
            .try_into_protocol_message()
            .map_err(input_err)?;
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(CryptoError::UnknownGroup)?;
        let processed = group
            .process_message(&self.provider, protocol_message)
            .map_err(mls_err)?;
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => Ok(Some(app.into_bytes())),
            ProcessedMessageContent::StagedCommitMessage(staged) => {
                group
                    .merge_staged_commit(&self.provider, *staged)
                    .map_err(mls_err)?;
                Ok(None)
            }
            _ => Ok(None),
        }
    }

    /// Number of members in the group (for tests and UI).
    pub fn member_count(&self, group_id: &[u8]) -> Result<u32, CryptoError> {
        let groups = self.lock_groups()?;
        let group = groups.get(group_id).ok_or(CryptoError::UnknownGroup)?;
        Ok(u32::try_from(group.members().count()).unwrap_or(u32::MAX))
    }

    /// Exports the complete client state (identity, all group state, key material) as one blob.
    ///
    /// SPIKE: the blob is **plaintext secret material**. #11 must encrypt it before it leaves Rust
    /// and apply the persistence rules from the spike docs (persist before send/ack, rollback
    /// protection).
    ///
    /// Format: magic, version, username, signature public key, group IDs, storage entries; every
    /// variable-length field is u32-BE length-prefixed.
    pub fn export_state(&self) -> Result<Vec<u8>, CryptoError> {
        let groups = self.lock_groups()?;
        let values = self
            .provider
            .storage
            .values
            .read()
            .map_err(|_| poisoned())?;
        let mut out = Vec::new();
        out.extend_from_slice(SNAPSHOT_MAGIC);
        out.push(SNAPSHOT_VERSION);
        put(&mut out, self.username.as_bytes())?;
        put(&mut out, &self.signer.to_public_vec())?;
        put_len(&mut out, groups.len())?;
        for id in groups.keys() {
            put(&mut out, id)?;
        }
        put_len(&mut out, values.len())?;
        for (k, v) in values.iter() {
            put(&mut out, k)?;
            put(&mut out, v)?;
        }
        Ok(out)
    }

    /// Restores a client from [`Client::export_state`] output.
    pub fn restore(state: &[u8]) -> Result<Self, CryptoError> {
        let mut r = Reader(state);
        if r.take(4)? != SNAPSHOT_MAGIC || r.take(1)? != [SNAPSHOT_VERSION] {
            return Err(invalid(
                "not a GifItUp state snapshot (or unsupported version)",
            ));
        }
        let username = String::from_utf8(r.field()?.to_vec()).map_err(input_err)?;
        let public_key = r.field()?.to_vec();
        let group_ids: Vec<Vec<u8>> = (0..r.len()?)
            .map(|_| r.field().map(<[u8]>::to_vec))
            .collect::<Result<_, _>>()?;
        let mut values = HashMap::new();
        for _ in 0..r.len()? {
            let k = r.field()?.to_vec();
            values.insert(k, r.field()?.to_vec());
        }
        if !r.0.is_empty() {
            return Err(invalid("trailing bytes in state snapshot"));
        }

        let provider = Provider {
            crypto: RustCrypto::default(),
            storage: MemoryStorage {
                values: RwLock::new(values),
            },
        };
        let signer = SignatureKeyPair::read(
            provider.storage(),
            &public_key,
            CIPHERSUITE.signature_algorithm(),
        )
        .ok_or_else(|| invalid("signature key missing from snapshot"))?;
        let mut groups = HashMap::new();
        for id in group_ids {
            let group = MlsGroup::load(provider.storage(), &GroupId::from_slice(&id))
                .map_err(mls_err)?
                .ok_or_else(|| invalid("group missing from snapshot"))?;
            groups.insert(id, group);
        }
        let credential = credential_with_key(&username, &signer);
        Ok(Self {
            provider,
            username,
            signer,
            credential,
            groups: Mutex::new(groups),
        })
    }

    fn lock_groups(&self) -> Result<MutexGuard<'_, HashMap<Vec<u8>, MlsGroup>>, CryptoError> {
        self.groups.lock().map_err(|_| poisoned())
    }
}

fn credential_with_key(username: &str, signer: &SignatureKeyPair) -> CredentialWithKey {
    CredentialWithKey {
        credential: BasicCredential::new(username.as_bytes().to_vec()).into(),
        signature_key: signer.to_public_vec().into(),
    }
}

fn poisoned() -> CryptoError {
    CryptoError::Mls {
        reason: "state lock poisoned".into(),
    }
}

fn put_len(out: &mut Vec<u8>, len: usize) -> Result<(), CryptoError> {
    let len = u32::try_from(len).map_err(|_| invalid("state too large"))?;
    out.extend_from_slice(&len.to_be_bytes());
    Ok(())
}

fn put(out: &mut Vec<u8>, bytes: &[u8]) -> Result<(), CryptoError> {
    put_len(out, bytes.len())?;
    out.extend_from_slice(bytes);
    Ok(())
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], CryptoError> {
        if self.0.len() < n {
            return Err(invalid("truncated state snapshot"));
        }
        let (head, tail) = self.0.split_at(n);
        self.0 = tail;
        Ok(head)
    }

    fn len(&mut self) -> Result<usize, CryptoError> {
        let bytes: [u8; 4] = self.take(4)?.try_into().map_err(input_err)?;
        usize::try_from(u32::from_be_bytes(bytes)).map_err(input_err)
    }

    fn field(&mut self) -> Result<&'a [u8], CryptoError> {
        let n = self.len()?;
        self.take(n)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair() -> (Client, Client, Vec<u8>) {
        let alice = Client::new("alice".into()).unwrap();
        let bob = Client::new("bob".into()).unwrap();
        let group_id = alice.create_group().unwrap();
        let added = alice
            .add_member(&group_id, &bob.create_key_package().unwrap())
            .unwrap();
        assert_eq!(bob.join_from_welcome(&added.welcome).unwrap(), group_id);
        (alice, bob, group_id)
    }

    #[test]
    fn alice_and_bob_exchange_messages() {
        let (alice, bob, group_id) = pair();
        assert_eq!(alice.member_count(&group_id).unwrap(), 2);
        assert_eq!(bob.member_count(&group_id).unwrap(), 2);

        let to_bob = alice.encrypt(&group_id, b"hi bob").unwrap();
        assert_eq!(bob.process(&group_id, &to_bob).unwrap().unwrap(), b"hi bob");
        let to_alice = bob.encrypt(&group_id, b"hi alice").unwrap();
        assert_eq!(
            alice.process(&group_id, &to_alice).unwrap().unwrap(),
            b"hi alice"
        );
    }

    #[test]
    fn third_member_joins_and_existing_member_processes_commit() {
        let (alice, bob, group_id) = pair();
        let carol = Client::new("carol".into()).unwrap();

        let add_carol = alice
            .add_member(&group_id, &carol.create_key_package().unwrap())
            .unwrap();
        assert_eq!(bob.process(&group_id, &add_carol.commit).unwrap(), None);
        carol.join_from_welcome(&add_carol.welcome).unwrap();
        assert_eq!(bob.member_count(&group_id).unwrap(), 3);

        let msg = carol.encrypt(&group_id, b"hi all").unwrap();
        assert_eq!(alice.process(&group_id, &msg).unwrap().unwrap(), b"hi all");
        assert_eq!(bob.process(&group_id, &msg).unwrap().unwrap(), b"hi all");
    }

    #[test]
    fn ciphertext_length_does_not_reveal_plaintext_length() {
        let (alice, bob, group_id) = pair();
        // Padding quantizes to PADDING_SIZE blocks (content + signature + framing), so lengths
        // within one block are indistinguishable and larger messages differ by whole blocks.
        let short = alice.encrypt(&group_id, &[b'a'; 10]).unwrap();
        let medium = alice.encrypt(&group_id, &[b'a'; 100]).unwrap();
        let long = alice.encrypt(&group_id, &[b'a'; 400]).unwrap();
        assert_eq!(short.len(), medium.len());
        assert_eq!((long.len() - short.len()) % PADDING_SIZE, 0);
        // The joiner's config pads too (padding is a local setting).
        let from_bob_short = bob.encrypt(&group_id, &[b'b'; 10]).unwrap();
        let from_bob_medium = bob.encrypt(&group_id, &[b'b'; 100]).unwrap();
        assert_eq!(from_bob_short.len(), from_bob_medium.len());
    }

    #[test]
    fn restored_client_continues_the_conversation() {
        let (alice, bob, group_id) = pair();
        let first = alice.encrypt(&group_id, b"before restart").unwrap();
        assert_eq!(
            bob.process(&group_id, &first).unwrap().unwrap(),
            b"before restart"
        );

        let snapshot = bob.export_state().unwrap();
        drop(bob);
        let bob = Client::restore(&snapshot).unwrap();
        assert_eq!(bob.member_count(&group_id).unwrap(), 2);

        let second = alice.encrypt(&group_id, b"after restart").unwrap();
        assert_eq!(
            bob.process(&group_id, &second).unwrap().unwrap(),
            b"after restart"
        );
        let reply = bob.encrypt(&group_id, b"still here").unwrap();
        assert_eq!(
            alice.process(&group_id, &reply).unwrap().unwrap(),
            b"still here"
        );
        // A restored identity can still create KeyPackages with the same signature key.
        assert!(!bob.create_key_package().unwrap().is_empty());
    }

    #[test]
    fn rejects_garbage_input() {
        let alice = Client::new("alice".into()).unwrap();
        let group_id = alice.create_group().unwrap();
        assert!(matches!(
            alice.add_member(&group_id, &[1, 2, 3]),
            Err(CryptoError::InvalidInput { .. })
        ));
        assert!(matches!(
            Client::restore(b"nope"),
            Err(CryptoError::InvalidInput { .. })
        ));
        let mut snapshot = alice.export_state().unwrap();
        snapshot.truncate(snapshot.len() - 1);
        assert!(Client::restore(&snapshot).is_err());
    }
}
