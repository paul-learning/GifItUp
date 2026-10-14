//! GifItUp E2EE core.
//!
//! **SPIKE QUALITY (#3).** This crate proves that OpenMLS can be wrapped and called from Kotlin on
//! Android via uniffi. It keeps all state in memory, has no persistence and performs none of the
//! member validation required by ADR-0002. Do not build on it without the follow-up in #11.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use openmls::prelude::tls_codec::{Deserialize, Serialize};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;

uniffi::setup_scaffolding!();

/// Ciphersuite fixed by ADR-0002 (mandatory-to-implement).
const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

#[derive(Debug, thiserror::Error, uniffi::Error)]
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

/// Result of adding a member: the Commit goes to existing members, the Welcome to the new one.
#[derive(uniffi::Record)]
pub struct AddMemberResult {
    pub commit: Vec<u8>,
    pub welcome: Vec<u8>,
}

/// One MLS client (one device). Holds the provider (crypto + in-memory storage), the signature
/// key pair, the credential and all groups this device is a member of.
#[derive(uniffi::Object)]
pub struct MlsClient {
    provider: OpenMlsRustCrypto,
    signer: SignatureKeyPair,
    credential: CredentialWithKey,
    groups: Mutex<HashMap<Vec<u8>, MlsGroup>>,
}

#[uniffi::export]
impl MlsClient {
    /// Creates a new identity with a fresh Ed25519 signature key and a BasicCredential for `username`.
    #[uniffi::constructor]
    pub fn new(username: String) -> Result<Arc<Self>, CryptoError> {
        if username.is_empty() {
            return Err(CryptoError::InvalidInput {
                reason: "username must not be empty".into(),
            });
        }
        let provider = OpenMlsRustCrypto::default();
        let signer = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).map_err(mls_err)?;
        signer.store(provider.storage()).map_err(mls_err)?;
        let credential = CredentialWithKey {
            credential: BasicCredential::new(username.into_bytes()).into(),
            signature_key: signer.to_public_vec().into(),
        };
        Ok(Arc::new(Self {
            provider,
            signer,
            credential,
            groups: Mutex::new(HashMap::new()),
        }))
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
        group_id: Vec<u8>,
        key_package: Vec<u8>,
    ) -> Result<AddMemberResult, CryptoError> {
        let key_package = match MlsMessageIn::tls_deserialize_exact(key_package)
            .map_err(input_err)?
            .extract()
        {
            MlsMessageBodyIn::KeyPackage(kp) => kp
                .validate(self.provider.crypto(), ProtocolVersion::Mls10)
                .map_err(input_err)?,
            _ => {
                return Err(CryptoError::InvalidInput {
                    reason: "not a KeyPackage".into(),
                });
            }
        };
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(&group_id).ok_or(CryptoError::UnknownGroup)?;
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
    pub fn join_from_welcome(&self, welcome: Vec<u8>) -> Result<Vec<u8>, CryptoError> {
        let welcome = match MlsMessageIn::tls_deserialize_exact(welcome)
            .map_err(input_err)?
            .extract()
        {
            MlsMessageBodyIn::Welcome(w) => w,
            _ => {
                return Err(CryptoError::InvalidInput {
                    reason: "not a Welcome".into(),
                });
            }
        };
        let join_config = MlsGroupJoinConfig::builder()
            .wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
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
    pub fn encrypt(&self, group_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Vec<u8>, CryptoError> {
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(&group_id).ok_or(CryptoError::UnknownGroup)?;
        group
            .create_message(&self.provider, &self.signer, &plaintext)
            .map_err(mls_err)?
            .tls_serialize_detached()
            .map_err(mls_err)
    }

    /// Processes an incoming message. Returns the plaintext for application messages, or `None`
    /// for handshake messages (a Commit is merged immediately in this spike).
    pub fn process(
        &self,
        group_id: Vec<u8>,
        message: Vec<u8>,
    ) -> Result<Option<Vec<u8>>, CryptoError> {
        let protocol_message = MlsMessageIn::tls_deserialize_exact(message)
            .map_err(input_err)?
            .try_into_protocol_message()
            .map_err(input_err)?;
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(&group_id).ok_or(CryptoError::UnknownGroup)?;
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
    pub fn member_count(&self, group_id: Vec<u8>) -> Result<u32, CryptoError> {
        let groups = self.lock_groups()?;
        let group = groups.get(&group_id).ok_or(CryptoError::UnknownGroup)?;
        Ok(u32::try_from(group.members().count()).unwrap_or(u32::MAX))
    }
}

impl MlsClient {
    fn lock_groups(
        &self,
    ) -> Result<std::sync::MutexGuard<'_, HashMap<Vec<u8>, MlsGroup>>, CryptoError> {
        self.groups.lock().map_err(|_| CryptoError::Mls {
            reason: "group state lock poisoned".into(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alice_and_bob_exchange_messages() {
        let alice = MlsClient::new("alice".into()).unwrap();
        let bob = MlsClient::new("bob".into()).unwrap();

        let group_id = alice.create_group().unwrap();
        let added = alice
            .add_member(group_id.clone(), bob.create_key_package().unwrap())
            .unwrap();
        let bob_group_id = bob.join_from_welcome(added.welcome).unwrap();
        assert_eq!(group_id, bob_group_id);
        assert_eq!(alice.member_count(group_id.clone()).unwrap(), 2);
        assert_eq!(bob.member_count(group_id.clone()).unwrap(), 2);

        let to_bob = alice.encrypt(group_id.clone(), b"hi bob".to_vec()).unwrap();
        assert_eq!(
            bob.process(group_id.clone(), to_bob).unwrap().unwrap(),
            b"hi bob"
        );

        let to_alice = bob.encrypt(group_id.clone(), b"hi alice".to_vec()).unwrap();
        assert_eq!(
            alice.process(group_id, to_alice).unwrap().unwrap(),
            b"hi alice"
        );
    }

    #[test]
    fn rejects_garbage_key_package() {
        let alice = MlsClient::new("alice".into()).unwrap();
        let group_id = alice.create_group().unwrap();
        assert!(matches!(
            alice.add_member(group_id, vec![1, 2, 3]),
            Err(CryptoError::InvalidInput { .. })
        ));
    }
}
