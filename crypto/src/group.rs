//! Group lifecycle (ADR-0016, #22): create, prepare commits (held pending until the Delivery Service accepts them),
//! stage incoming commits and welcomes (inspected, then merged or dropped), encrypt, forget.
//!
//! Rules that live here because they need no contact book (ADR-0016):
//! - a commit is created but **not applied** until [`Client::merge_pending`]; a rejected one is wiped with
//!   [`Client::clear_pending`];
//! - an incoming message is **staged** and described ([`Incoming`]); a commit is applied only by
//!   [`Client::merge_staged`], a welcome joins only through [`Client::join_welcome`]. Staged items live in memory under
//!   a token, are never exported, and block [`Client::export_state`] while they exist;
//! - a commit may only add and remove members (no ReInit, GroupContextExtensions, PSK or external proposals), and
//!   nobody's credential or signature key may change (identity continuity), the committer's own path leaf included;
//! - the sender of an application message is the MLS-authenticated leaf, never the server's envelope.

use std::collections::HashMap;

use openmls::framing::errors::{MessageDecryptionError, SecretTreeError};
use openmls::prelude::tls_codec::{Deserialize, Serialize};
use openmls::prelude::*;
use openmls_traits::OpenMlsProvider;
use openmls_traits::crypto::OpenMlsCrypto;

use crate::client::{CIPHERSUITE, Client};
use crate::error::{ErrorCode, input, mls};
use crate::identity::is_valid_username;

/// MLS padding block size (ADR-0006 B7). Must be set in both the create and the join config.
pub(crate) const PADDING_SIZE: usize = 1024;

/// Largest application content that still fits one padding block with this ciphersuite:
/// 2 + 896 + 2 + 64 + 16 = 980 <= 1024 (ADR-0006 B7). Recompute if the ciphersuite changes.
pub(crate) const MAX_APPLICATION_CONTENT: usize = 896;

/// At most this many leaves in a group, ghosts included (ADR-0015).
pub(crate) const MAX_LEAVES: usize = 50;
/// At most this many devices added plus removed by one commit (ADR-0015).
pub(crate) const MAX_CHANGES: usize = 10;
/// A commit or a welcome is at most this many bytes (ADR-0015); anything larger is refused before parsing.
pub(crate) const MAX_HANDSHAKE_BYTES: usize = 16 * 1024;
/// An application message is at most this many bytes on the wire (one padded block plus framing, ADR-0006 B7).
const MAX_MESSAGE_BYTES: usize = 4 * 1024;
const MIN_GROUP_ID: usize = 16;
const MAX_GROUP_ID: usize = 64;

/// What the MLS tree says about one leaf: the username in its credential and its signature key. The leaf has no
/// device id; the Delivery Service's device ids are only advisory (ADR-0015).
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Record))]
pub struct LeafInfo {
    pub username: String,
    pub signature_key: Vec<u8>,
    pub leaf_index: u32,
}

/// A commit the Delivery Service has not accepted yet: send [`commit`](Self::commit) (and the welcome, if any) in the
/// envelope, then [`Client::merge_pending`] or [`Client::clear_pending`].
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Record))]
pub struct PreparedCommit {
    pub commit: Vec<u8>,
    pub welcome: Option<Vec<u8>>,
    /// The epoch the commit applies to (the envelope's `epoch`).
    pub epoch: u64,
    pub added: Vec<LeafInfo>,
    pub removed: Vec<LeafInfo>,
}

/// An application message sealed for the group, with its message reference (ADR-0006 B9).
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Record))]
pub struct EncryptedMessage {
    pub message: Vec<u8>,
    pub message_ref: Vec<u8>,
}

/// The typed result of staging an incoming message.
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Enum))]
pub enum Incoming {
    /// An application message: [`sender`](Incoming::Application::sender) is authenticated by MLS.
    Application {
        plaintext: Vec<u8>,
        sender: LeafInfo,
        message_ref: Vec<u8>,
    },
    /// A commit, staged and **not applied**: decide with [`Client::merge_staged`] or [`Client::discard_staged`].
    Commit {
        token: u32,
        sender: LeafInfo,
        /// The epoch the commit applies to.
        epoch: u64,
        added: Vec<LeafInfo>,
        removed: Vec<LeafInfo>,
        /// This commit removes us: merging it deactivates the group, then [`Client::forget_group`] it.
        self_removed: bool,
    },
}

/// [`Incoming`] flattened for the FFI layers (a record both bindings can carry): `is_commit` says which fields count.
/// For an application message `token` and `epoch` are 0 and `added`/`removed` are empty; for a commit `plaintext`
/// and `message_ref` are empty.
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Record))]
pub struct IncomingMessage {
    pub is_commit: bool,
    pub token: u32,
    pub plaintext: Vec<u8>,
    pub sender: LeafInfo,
    pub message_ref: Vec<u8>,
    pub epoch: u64,
    pub added: Vec<LeafInfo>,
    pub removed: Vec<LeafInfo>,
    pub self_removed: bool,
}

impl From<Incoming> for IncomingMessage {
    fn from(incoming: Incoming) -> Self {
        match incoming {
            Incoming::Application {
                plaintext,
                sender,
                message_ref,
            } => Self {
                is_commit: false,
                token: 0,
                plaintext,
                sender,
                message_ref,
                epoch: 0,
                added: Vec::new(),
                removed: Vec::new(),
                self_removed: false,
            },
            Incoming::Commit {
                token,
                sender,
                epoch,
                added,
                removed,
                self_removed,
            } => Self {
                is_commit: true,
                token,
                plaintext: Vec::new(),
                sender,
                message_ref: Vec::new(),
                epoch,
                added,
                removed,
                self_removed,
            },
        }
    }
}

/// A welcome, parsed in memory and **not joined**: [`Client::join_welcome`] or [`Client::discard_welcome`].
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Record))]
pub struct WelcomeInfo {
    pub token: u32,
    pub group_id: Vec<u8>,
    pub sender: LeafInfo,
    pub members: Vec<LeafInfo>,
}

/// Commits and welcomes that were processed but not decided on. In memory only, and one per group for commits.
#[derive(Default)]
pub(crate) struct Staging {
    next_token: u32,
    commits: HashMap<u32, (Vec<u8>, StagedCommit)>,
    welcomes: HashMap<u32, (Vec<u8>, StagedWelcome)>,
}

impl Staging {
    pub(crate) fn is_empty(&self) -> bool {
        self.commits.is_empty() && self.welcomes.is_empty()
    }

    fn token(&mut self) -> Result<u32, ErrorCode> {
        self.next_token = self
            .next_token
            .checked_add(1)
            .ok_or(ErrorCode::LimitExceeded)?;
        Ok(self.next_token)
    }

    fn has_commit_for(&self, group_id: &[u8]) -> bool {
        self.commits.values().any(|(id, _)| id == group_id)
    }
}

impl Client {
    /// Creates a new group with this client as the only member. Returns the group ID (16 random bytes from OpenMLS).
    pub fn create_group(&self) -> Result<Vec<u8>, ErrorCode> {
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
        .map_err(mls)?;
        let id = group.group_id().as_slice().to_vec();
        self.lock_groups()?.insert(id.clone(), group);
        Ok(id)
    }

    /// Prepares a commit that adds the owners of `key_packages` (1..=[`MAX_CHANGES`]). Every KeyPackage is
    /// validated here again with the rules of `inspect_key_package`, never trusting an earlier inspection. The commit
    /// is held pending, not applied.
    pub fn prepare_add(
        &self,
        group_id: &[u8],
        key_packages: &[Vec<u8>],
    ) -> Result<PreparedCommit, ErrorCode> {
        if key_packages.is_empty() || key_packages.len() > MAX_CHANGES {
            return Err(ErrorCode::LimitExceeded);
        }
        let parsed = key_packages
            .iter()
            .map(|bytes| self.parse_key_package(bytes))
            .collect::<Result<Vec<_>, _>>()?;
        let mut groups = self.lock_groups()?;
        let group = self.ready_group(&mut groups, group_id)?;
        if group.members().count() + parsed.len() > MAX_LEAVES {
            return Err(ErrorCode::LimitExceeded);
        }
        let added = parsed
            .iter()
            .map(|kp| leaf_of_key_package(kp, group))
            .collect::<Result<Vec<_>, _>>()?;
        let mut keys: Vec<&[u8]> = added.iter().map(|l| l.signature_key.as_slice()).collect();
        keys.sort_unstable();
        keys.dedup();
        if keys.len() != added.len() {
            return Err(ErrorCode::Refused); // one leaf per signature key, in a batch too
        }
        let epoch = group.epoch().as_u64();
        let (commit, welcome, _group_info) = group
            .add_members(&self.provider, &self.signer, &parsed)
            .map_err(mls)?;
        let prepared = (|| {
            Ok(PreparedCommit {
                commit: serialize(&commit)?,
                welcome: Some(serialize(&welcome)?),
                epoch,
                added,
                removed: Vec::new(),
            })
        })();
        self.checked_prepared(group, prepared)
    }

    /// Prepares a commit that removes the members at `leaf_indexes` (1..=[`MAX_CHANGES`], never our own leaf).
    pub fn prepare_remove(
        &self,
        group_id: &[u8],
        leaf_indexes: &[u32],
    ) -> Result<PreparedCommit, ErrorCode> {
        if leaf_indexes.is_empty() || leaf_indexes.len() > MAX_CHANGES {
            return Err(ErrorCode::LimitExceeded);
        }
        let mut groups = self.lock_groups()?;
        let group = self.ready_group(&mut groups, group_id)?;
        let own = group.own_leaf_index().u32();
        let mut removed = Vec::new();
        for &index in leaf_indexes {
            let member = group
                .member_at(LeafNodeIndex::new(index))
                .ok_or(ErrorCode::InvalidInput)?;
            if index == own || removed.iter().any(|l: &LeafInfo| l.leaf_index == index) {
                return Err(ErrorCode::InvalidInput);
            }
            removed.push(leaf_info(&member)?);
        }
        let indexes: Vec<LeafNodeIndex> = leaf_indexes
            .iter()
            .map(|&i| LeafNodeIndex::new(i))
            .collect();
        let epoch = group.epoch().as_u64();
        let (commit, welcome, _group_info) = group
            .remove_members(&self.provider, &self.signer, &indexes)
            .map_err(mls)?;
        let prepared = (|| {
            Ok(PreparedCommit {
                commit: serialize(&commit)?,
                welcome: welcome.as_ref().map(serialize).transpose()?,
                epoch,
                added: Vec::new(),
                removed,
            })
        })();
        self.checked_prepared(group, prepared)
    }

    /// Prepares a self-update commit (fresh leaf keys, same identity).
    pub fn prepare_update(&self, group_id: &[u8]) -> Result<PreparedCommit, ErrorCode> {
        let mut groups = self.lock_groups()?;
        let group = self.ready_group(&mut groups, group_id)?;
        let epoch = group.epoch().as_u64();
        let bundle = group
            .self_update(&self.provider, &self.signer, LeafNodeParameters::default())
            .map_err(mls)?;
        let prepared = serialize(bundle.commit()).map(|commit| PreparedCommit {
            commit,
            welcome: None,
            epoch,
            added: Vec::new(),
            removed: Vec::new(),
        });
        self.checked_prepared(group, prepared)
    }

    /// Applies the pending commit after the Delivery Service accepted it. Returns the new epoch.
    pub fn merge_pending(&self, group_id: &[u8]) -> Result<u64, ErrorCode> {
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(ErrorCode::UnknownGroup)?;
        if self.lock_staging()?.has_commit_for(group_id) {
            return Err(ErrorCode::PendingOperation);
        }
        if group.pending_commit().is_none() {
            return Err(ErrorCode::UnknownToken);
        }
        group.merge_pending_commit(&self.provider).map_err(mls)?;
        Ok(group.epoch().as_u64())
    }

    /// Drops the pending commit after a rejection (an epoch conflict), wiping its path secrets.
    pub fn clear_pending(&self, group_id: &[u8]) -> Result<(), ErrorCode> {
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(ErrorCode::UnknownGroup)?;
        if group.pending_commit().is_none() {
            return Err(ErrorCode::UnknownToken);
        }
        group
            .clear_pending_commit(self.provider.storage())
            .map_err(mls)
    }

    /// Whether a commit of ours is pending in this group (it survives an export, ADR-0016).
    pub fn has_pending_commit(&self, group_id: &[u8]) -> Result<bool, ErrorCode> {
        let groups = self.lock_groups()?;
        let group = groups.get(group_id).ok_or(ErrorCode::UnknownGroup)?;
        Ok(group.pending_commit().is_some())
    }

    /// Encrypts an application message (at most [`MAX_APPLICATION_CONTENT`] bytes) for the group. The MLS
    /// authenticated data stays empty: it is sent in the clear (ADR-0006 B6).
    pub fn encrypt(
        &self,
        group_id: &[u8],
        plaintext: &[u8],
    ) -> Result<EncryptedMessage, ErrorCode> {
        if plaintext.len() > MAX_APPLICATION_CONTENT {
            return Err(ErrorCode::InvalidInput);
        }
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(ErrorCode::UnknownGroup)?;
        let message = group
            .create_message(&self.provider, &self.signer, plaintext)
            .map_err(mls)?
            .tls_serialize_detached()
            .map_err(mls)?;
        let message_ref = self.message_ref(&message)?;
        Ok(EncryptedMessage {
            message,
            message_ref,
        })
    }

    /// Processes an incoming message of the group without applying anything that changes membership. An
    /// application message comes back decrypted with its MLS-authenticated sender; a commit is **staged** (see
    /// [`Incoming::Commit`]). The staged commit, when merged, is the only way the group changes.
    pub fn stage_incoming(&self, group_id: &[u8], message: &[u8]) -> Result<Incoming, ErrorCode> {
        if message.len() > MAX_HANDSHAKE_BYTES.max(MAX_MESSAGE_BYTES) {
            return Err(ErrorCode::InvalidInput);
        }
        let protocol_message = MlsMessageIn::tls_deserialize_exact(message)
            .map_err(input)?
            .try_into_protocol_message()
            .map_err(input)?;
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(ErrorCode::UnknownGroup)?;
        if !matches!(protocol_message, ProtocolMessage::PrivateMessage(_)) {
            return Err(ErrorCode::Refused);
        }
        if protocol_message.epoch() != group.epoch() {
            return Err(ErrorCode::WrongEpoch);
        }
        // Our own pending commit has to be resolved first (merged or cleared): merging a competing commit would
        // leave the pending one and its path secrets behind (SEC-14). An echo of our own commit lands here as
        // `PendingOperation` too; the engine catches echoes by device id before it stages anything.
        let mut staging = self.lock_staging()?;
        if group.pending_commit().is_some() || staging.has_commit_for(group_id) {
            return Err(ErrorCode::PendingOperation);
        }
        let processed = group
            .process_message(&self.provider, protocol_message)
            .map_err(classify)?;
        let sender = match processed.sender() {
            Sender::Member(index) => group
                .member_at(*index)
                .ok_or(ErrorCode::Refused)
                .and_then(|m| leaf_info(&m))?,
            _ => return Err(ErrorCode::Refused),
        };
        let epoch = group.epoch().as_u64();
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => Ok(Incoming::Application {
                plaintext: app.into_bytes(),
                sender,
                message_ref: self.message_ref(message)?,
            }),
            ProcessedMessageContent::StagedCommitMessage(staged) => {
                let (added, removed) = inspect_commit(group, &sender, &staged)?;
                let self_removed = staged.self_removed();
                let token = staging.token()?;
                staging.commits.insert(token, (group_id.to_vec(), *staged));
                Ok(Incoming::Commit {
                    token,
                    sender,
                    epoch,
                    added,
                    removed,
                    self_removed,
                })
            }
            // Standalone proposals and external join proposals are never stored: our commits carry their
            // proposals by value (ADR-0016).
            _ => Err(ErrorCode::Refused),
        }
    }

    /// Applies a staged commit. Returns the new epoch.
    pub fn merge_staged(&self, token: u32) -> Result<u64, ErrorCode> {
        let mut groups = self.lock_groups()?;
        let mut staging = self.lock_staging()?;
        let (group_id, staged) = staging
            .commits
            .remove(&token)
            .ok_or(ErrorCode::UnknownToken)?;
        let group = groups.get_mut(&group_id).ok_or(ErrorCode::UnknownGroup)?;
        group
            .merge_staged_commit(&self.provider, staged)
            .map_err(mls)?;
        Ok(group.epoch().as_u64())
    }

    /// Drops a staged commit without applying it. The message's secret is used up, so the commit cannot be
    /// processed again: the caller treats the group as stuck (ADR-0016).
    pub fn discard_staged(&self, token: u32) -> Result<(), ErrorCode> {
        let mut staging = self.lock_staging()?;
        staging
            .commits
            .remove(&token)
            .map(drop)
            .ok_or(ErrorCode::UnknownToken)
    }

    /// Parses a welcome in memory without joining. `expected_group_id` is the Delivery Service group the welcome was
    /// fetched for: the MLS group id inside must be the same, and no such group may exist here yet. Parsing consumes
    /// the KeyPackage the welcome was made for (OpenMLS deletes its private key), so a welcome is inspected **once**:
    /// join with the token or discard it.
    pub fn inspect_welcome(
        &self,
        welcome: &[u8],
        expected_group_id: &[u8],
    ) -> Result<WelcomeInfo, ErrorCode> {
        if welcome.len() > MAX_HANDSHAKE_BYTES
            || !(MIN_GROUP_ID..=MAX_GROUP_ID).contains(&expected_group_id.len())
        {
            return Err(ErrorCode::InvalidInput);
        }
        let welcome = match MlsMessageIn::tls_deserialize_exact(welcome)
            .map_err(input)?
            .extract()
        {
            MlsMessageBodyIn::Welcome(w) => w,
            _ => return Err(ErrorCode::InvalidInput),
        };
        if self.lock_groups()?.contains_key(expected_group_id) {
            return Err(ErrorCode::Refused);
        }
        let join_config = MlsGroupJoinConfig::builder()
            .wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
            .padding_size(PADDING_SIZE)
            .use_ratchet_tree_extension(true)
            .build();
        // Existing members' leaves may have outlived their KeyPackage lifetime (84 days) if they never committed:
        // only the KeyPackages we add and our own count (ADR-0016), so the lifetime check on the tree is skipped.
        let staged = StagedWelcome::build_from_welcome(&self.provider, &join_config, welcome)
            .map_err(mls)?
            .skip_lifetime_validation()
            .build()
            .map_err(mls)?;
        let context = staged.group_context();
        if context.group_id().as_slice() != expected_group_id
            || context.ciphersuite() != CIPHERSUITE
        {
            return Err(ErrorCode::Refused);
        }
        let members = staged
            .members()
            .map(|m| leaf_info(&m))
            .collect::<Result<Vec<_>, _>>()?;
        if members.len() > MAX_LEAVES {
            return Err(ErrorCode::LimitExceeded);
        }
        let sender_leaf = staged.welcome_sender().map_err(mls)?;
        let sender = members
            .iter()
            .find(|m| m.signature_key == sender_leaf.signature_key().as_slice())
            .cloned()
            .ok_or(ErrorCode::Refused)?;
        let mut staging = self.lock_staging()?;
        let token = staging.token()?;
        staging
            .welcomes
            .insert(token, (expected_group_id.to_vec(), staged));
        Ok(WelcomeInfo {
            token,
            group_id: expected_group_id.to_vec(),
            sender,
            members,
        })
    }

    /// Joins the group of an inspected welcome. Returns the group id.
    pub fn join_welcome(&self, token: u32) -> Result<Vec<u8>, ErrorCode> {
        let mut groups = self.lock_groups()?;
        let mut staging = self.lock_staging()?;
        let (group_id, staged) = staging
            .welcomes
            .remove(&token)
            .ok_or(ErrorCode::UnknownToken)?;
        if groups.contains_key(&group_id) {
            return Err(ErrorCode::Refused);
        }
        let group = staged.into_group(&self.provider).map_err(mls)?;
        groups.insert(group_id.clone(), group);
        Ok(group_id)
    }

    /// Drops an inspected welcome without joining. The KeyPackage it used is gone either way.
    pub fn discard_welcome(&self, token: u32) -> Result<(), ErrorCode> {
        let mut staging = self.lock_staging()?;
        staging
            .welcomes
            .remove(&token)
            .map(drop)
            .ok_or(ErrorCode::UnknownToken)
    }

    /// Leaves this device's copy of a group: deletes the group's state and secrets from storage, so they are absent
    /// from the next snapshot (#37 SEC-4). Staged items of the group go with it.
    pub fn forget_group(&self, group_id: &[u8]) -> Result<(), ErrorCode> {
        let mut groups = self.lock_groups()?;
        // Staged items of the id go first, even when no such group exists (a staged welcome is for a group not yet
        // joined).
        let mut staging = self.lock_staging()?;
        staging.commits.retain(|_, (id, _)| id != group_id);
        staging.welcomes.retain(|_, (id, _)| id != group_id);
        drop(staging);
        let mut group = groups.remove(group_id).ok_or(ErrorCode::UnknownGroup)?;
        group.delete(self.provider.storage()).map_err(mls)
    }

    /// The members of the group as the tree has them now, ghosts included.
    pub fn members(&self, group_id: &[u8]) -> Result<Vec<LeafInfo>, ErrorCode> {
        let groups = self.lock_groups()?;
        let group = groups.get(group_id).ok_or(ErrorCode::UnknownGroup)?;
        group.members().map(|m| leaf_info(&m)).collect()
    }

    /// Number of members in the group (for tests and UI).
    pub fn member_count(&self, group_id: &[u8]) -> Result<u32, ErrorCode> {
        let groups = self.lock_groups()?;
        let group = groups.get(group_id).ok_or(ErrorCode::UnknownGroup)?;
        Ok(u32::try_from(group.members().count()).unwrap_or(u32::MAX))
    }

    /// The group's current epoch.
    pub fn epoch(&self, group_id: &[u8]) -> Result<u64, ErrorCode> {
        let groups = self.lock_groups()?;
        let group = groups.get(group_id).ok_or(ErrorCode::UnknownGroup)?;
        Ok(group.epoch().as_u64())
    }

    /// `SHA-256("GifItUp v1 message ref" || message)` (ADR-0006 B9): the reference of a message whose bytes were
    /// parsed and processed. Needs no client state.
    fn message_ref(&self, message: &[u8]) -> Result<Vec<u8>, ErrorCode> {
        let mut data = b"GifItUp v1 message ref".to_vec();
        data.extend_from_slice(message);
        self.provider
            .crypto()
            .hash(HashType::Sha2_256, &data)
            .map_err(mls)
    }

    /// The group, if no commit of ours is pending and none is staged for it (one operation at a time).
    fn ready_group<'a>(
        &self,
        groups: &'a mut HashMap<Vec<u8>, MlsGroup>,
        group_id: &[u8],
    ) -> Result<&'a mut MlsGroup, ErrorCode> {
        let group = groups.get_mut(group_id).ok_or(ErrorCode::UnknownGroup)?;
        if group.pending_commit().is_some() || self.lock_staging()?.has_commit_for(group_id) {
            return Err(ErrorCode::PendingOperation);
        }
        Ok(group)
    }

    /// Returns `prepared` if it fit; otherwise wipes the pending commit that was just created.
    fn checked_prepared(
        &self,
        group: &mut MlsGroup,
        prepared: Result<PreparedCommit, ErrorCode>,
    ) -> Result<PreparedCommit, ErrorCode> {
        let fits = prepared.as_ref().is_ok_and(|p| {
            p.commit.len() <= MAX_HANDSHAKE_BYTES
                && p.welcome
                    .as_ref()
                    .is_none_or(|w| w.len() <= MAX_HANDSHAKE_BYTES)
        });
        if fits {
            prepared
        } else {
            group
                .clear_pending_commit(self.provider.storage())
                .map_err(mls)?;
            prepared.and(Err(ErrorCode::LimitExceeded))
        }
    }

    /// Parses and validates someone else's KeyPackage with the rules of `inspect_key_package`; the username is
    /// whatever its credential says (it must follow the username rule).
    fn parse_key_package(&self, bytes: &[u8]) -> Result<KeyPackage, ErrorCode> {
        if bytes.len() > crate::identity::MAX_KEY_PACKAGE_BYTES {
            return Err(ErrorCode::InvalidKeyPackage);
        }
        let kp = match MlsMessageIn::tls_deserialize_exact(bytes)
            .map_err(|_| ErrorCode::InvalidKeyPackage)?
            .extract()
        {
            MlsMessageBodyIn::KeyPackage(kp) => kp
                .validate(self.provider.crypto(), ProtocolVersion::Mls10)
                .map_err(|_| ErrorCode::InvalidKeyPackage)?,
            _ => return Err(ErrorCode::InvalidKeyPackage),
        };
        let username = credential_username(kp.leaf_node().credential())
            .map_err(|_| ErrorCode::InvalidKeyPackage)?;
        crate::identity::check_key_package(&kp, &username)?;
        Ok(kp)
    }
}

fn serialize(message: &MlsMessageOut) -> Result<Vec<u8>, ErrorCode> {
    message.tls_serialize_detached().map_err(mls)
}

/// The username in a Basic credential, which must follow the username rule.
fn credential_username(credential: &Credential) -> Result<String, ErrorCode> {
    let identity = BasicCredential::try_from(credential.clone()).map_err(|_| ErrorCode::Refused)?;
    let username =
        String::from_utf8(identity.identity().to_vec()).map_err(|_| ErrorCode::Refused)?;
    if is_valid_username(&username) {
        Ok(username)
    } else {
        Err(ErrorCode::Refused)
    }
}

fn leaf_info(member: &Member) -> Result<LeafInfo, ErrorCode> {
    Ok(LeafInfo {
        username: credential_username(&member.credential)?,
        signature_key: member.signature_key.clone(),
        leaf_index: member.index.u32(),
    })
}

/// The leaf a KeyPackage would add. Refuses a signature key that a member of the group already uses.
fn leaf_of_key_package(kp: &KeyPackage, group: &MlsGroup) -> Result<LeafInfo, ErrorCode> {
    let leaf = kp.leaf_node();
    let key = leaf.signature_key().as_slice().to_vec();
    if group.members().any(|m| m.signature_key == key) {
        return Err(ErrorCode::Refused);
    }
    Ok(LeafInfo {
        username: credential_username(leaf.credential())?,
        signature_key: key,
        leaf_index: u32::MAX, // assigned by the tree when the commit is merged
    })
}

/// Maps a processing failure to a closed code; a message whose secret is used up is a replay.
fn classify<E: std::fmt::Debug>(error: ProcessMessageError<E>) -> ErrorCode {
    match error {
        ProcessMessageError::ValidationError(ValidationError::UnableToDecrypt(
            MessageDecryptionError::SecretTreeError(SecretTreeError::SecretReuseError),
        )) => ErrorCode::Replay,
        ProcessMessageError::ValidationError(ValidationError::WrongEpoch) => ErrorCode::WrongEpoch,
        ProcessMessageError::IncompatibleWireFormat => ErrorCode::Refused,
        _ => ErrorCode::Mls,
    }
}

/// Checks a staged commit against our rules (ADR-0016) and describes it. Only Add and Remove proposals are allowed;
/// nobody's credential or signature key may change, the committer's new path leaf included; the group stays within
/// its limits. Nothing is applied.
fn inspect_commit(
    group: &MlsGroup,
    sender: &LeafInfo,
    staged: &StagedCommit,
) -> Result<(Vec<LeafInfo>, Vec<LeafInfo>), ErrorCode> {
    let only_adds_and_removes = staged
        .queued_proposals()
        .all(|q| matches!(q.proposal(), Proposal::Add(_) | Proposal::Remove(_)));
    if !only_adds_and_removes {
        return Err(ErrorCode::Refused);
    }
    // Identity continuity: the committer's new leaf keeps its credential and signature key.
    if let Some(path_leaf) = staged.update_path_leaf_node() {
        let same_name = credential_username(path_leaf.credential())? == sender.username;
        let same_key = path_leaf.signature_key().as_slice() == sender.signature_key.as_slice();
        if !(same_name && same_key) {
            return Err(ErrorCode::Refused);
        }
    }
    let mut added = Vec::new();
    for proposal in staged.add_proposals() {
        let kp = proposal.add_proposal().key_package();
        let key = kp.leaf_node().signature_key().as_slice().to_vec();
        let taken = group.members().any(|m| m.signature_key == key)
            || added.iter().any(|l: &LeafInfo| l.signature_key == key);
        let username = credential_username(kp.leaf_node().credential())?;
        // The same rules as for a KeyPackage we add ourselves (suite, lifetime, not last-resort, name).
        if taken || crate::identity::check_key_package(kp, &username).is_err() {
            return Err(ErrorCode::Refused);
        }
        added.push(LeafInfo {
            username,
            signature_key: key,
            leaf_index: u32::MAX,
        });
    }
    let mut removed = Vec::new();
    for proposal in staged.remove_proposals() {
        let member = group
            .member_at(proposal.remove_proposal().removed())
            .ok_or(ErrorCode::Refused)?;
        removed.push(leaf_info(&member)?);
    }
    if added.len() + removed.len() > MAX_CHANGES
        || group.members().count() + added.len() > MAX_LEAVES + removed.len()
    {
        return Err(ErrorCode::LimitExceeded);
    }
    Ok((added, removed))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{client, key, key_package, pair, storage_keys};

    #[test]
    fn alice_and_bob_exchange_messages() {
        let (alice, bob, group_id) = pair();
        assert_eq!(alice.member_count(&group_id).unwrap(), 2);
        assert_eq!(bob.member_count(&group_id).unwrap(), 2);

        let to_bob = alice.seal(&group_id, b"hi bob");
        assert_eq!(bob.process(&group_id, &to_bob).unwrap().unwrap(), b"hi bob");
        let to_alice = bob.seal(&group_id, b"hi alice");
        assert_eq!(
            alice.process(&group_id, &to_alice).unwrap().unwrap(),
            b"hi alice"
        );
    }

    #[test]
    fn third_member_joins_and_existing_member_processes_commit() {
        let (alice, bob, group_id) = pair();
        let carol = client("carol");

        let add_carol = alice.add_member(&group_id, &key_package(&carol)).unwrap();
        assert_eq!(bob.process(&group_id, &add_carol.commit).unwrap(), None);
        carol
            .join_from_welcome(&add_carol.welcome, &group_id)
            .unwrap();
        assert_eq!(bob.member_count(&group_id).unwrap(), 3);

        let msg = carol.seal(&group_id, b"hi all");
        assert_eq!(alice.process(&group_id, &msg).unwrap().unwrap(), b"hi all");
        assert_eq!(bob.process(&group_id, &msg).unwrap().unwrap(), b"hi all");
    }

    /// ADR-0006 B7: the full serialized message (what the server sees) has the same length for the
    /// smallest and the largest allowed content, from the group creator and from a joiner (padding
    /// is a local setting, so both configs must set it).
    #[test]
    fn every_allowed_content_length_gives_the_same_ciphertext_length() {
        let (alice, bob, group_id) = pair();
        let max = MAX_APPLICATION_CONTENT;
        let lengths: Vec<usize> = [(&alice, 1), (&alice, max), (&bob, 1), (&bob, max)]
            .iter()
            .map(|(sender, n)| sender.seal(&group_id, &vec![b'a'; *n]).len())
            .collect();
        assert!(lengths.iter().all(|&len| len == lengths[0]), "{lengths:?}");
        assert!(lengths[0] <= PADDING_SIZE + 100, "one block plus framing");
    }

    #[test]
    fn content_over_the_cap_is_rejected_by_the_sender() {
        let (alice, _bob, group_id) = pair();
        assert_eq!(
            alice.encrypt(&group_id, &vec![b'a'; MAX_APPLICATION_CONTENT + 1]),
            Err(ErrorCode::InvalidInput)
        );
    }

    /// SEC-M0-9: the authenticated data is cleartext for the server, so it must stay empty.
    #[test]
    fn application_messages_carry_no_authenticated_data() {
        let (alice, _bob, group_id) = pair();
        let message = alice.seal(&group_id, b"hi");
        let parsed = MlsMessageIn::tls_deserialize_exact(&message)
            .unwrap()
            .try_into_protocol_message()
            .unwrap();
        match parsed {
            ProtocolMessage::PrivateMessage(private) => assert!(private.aad().is_empty()),
            ProtocolMessage::PublicMessage(_) => panic!("expected a PrivateMessage"),
        }
    }

    #[test]
    fn restored_client_continues_the_conversation() {
        let (alice, bob, group_id) = pair();
        let first = alice.seal(&group_id, b"before restart");
        assert_eq!(
            bob.process(&group_id, &first).unwrap().unwrap(),
            b"before restart"
        );

        let snapshot = bob.export_state().unwrap();
        drop(bob);
        let bob = Client::restore(&snapshot, key()).unwrap();
        assert_eq!(bob.member_count(&group_id).unwrap(), 2);

        let second = alice.seal(&group_id, b"after restart");
        assert_eq!(
            bob.process(&group_id, &second).unwrap().unwrap(),
            b"after restart"
        );
        let reply = bob.seal(&group_id, b"still here");
        assert_eq!(
            alice.process(&group_id, &reply).unwrap().unwrap(),
            b"still here"
        );
    }

    /// #11 plan review SEC-8: after forgetting a group, the storage holds exactly what it held
    /// before the group existed (minus the KeyPackage a joiner used), even after a self-update
    /// and with a commit still pending. A search for the group ID alone would miss secrets that
    /// OpenMLS stores under a public key.
    #[test]
    fn forgetting_a_group_leaves_no_secrets_behind() {
        let alice = client("alice");
        let bob = client("bob");
        let alice_before = storage_keys(&alice);
        let bob_key_package = key_package(&bob);
        let bob_before = storage_keys(&bob);

        let group_id = alice.create_group().unwrap();
        let added = alice.add_member(&group_id, &bob_key_package).unwrap();
        bob.join_from_welcome(&added.welcome, &group_id).unwrap();
        {
            let mut groups = alice.lock_groups().unwrap();
            let group = groups.get_mut(&group_id).unwrap();
            let update = group
                .self_update(
                    &alice.provider,
                    &alice.signer,
                    LeafNodeParameters::default(),
                )
                .unwrap();
            group.merge_pending_commit(&alice.provider).unwrap();
            bob.process(
                &group_id,
                &update.commit().tls_serialize_detached().unwrap(),
            )
            .unwrap();
            // A second update stays pending.
            group
                .self_update(
                    &alice.provider,
                    &alice.signer,
                    LeafNodeParameters::default(),
                )
                .unwrap();
        }

        alice.forget_group(&group_id).unwrap();
        bob.forget_group(&group_id).unwrap();
        assert_eq!(storage_keys(&alice), alice_before);
        // Bob's KeyPackage was used by the Welcome, so it is gone as well.
        assert!(storage_keys(&bob).is_subset(&bob_before));
        assert_eq!(bob_before.len() - storage_keys(&bob).len(), 1);
        assert_eq!(alice.forget_group(&group_id), Err(ErrorCode::UnknownGroup));
    }

    #[test]
    fn rejects_garbage_input() {
        let alice = client("alice");
        let group_id = alice.create_group().unwrap();
        assert_eq!(
            alice.add_member(&group_id, &[1, 2, 3]).err(),
            Some(ErrorCode::InvalidKeyPackage)
        );
        assert_eq!(
            alice.process(&group_id, &[1, 2, 3]),
            Err(ErrorCode::InvalidInput)
        );
        assert_eq!(
            alice.encrypt(b"no such group", b"x"),
            Err(ErrorCode::UnknownGroup)
        );
    }

    // ---- ADR-0016 lifecycle ----

    use std::time::Duration;

    use openmls_basic_credential::SignatureKeyPair;
    use openmls_rust_crypto::RustCrypto;

    /// Runs `f` on the client's group, for tests that craft messages OpenMLS can make but our API never does.
    fn with_group<T>(
        client: &Client,
        group_id: &[u8],
        f: impl FnOnce(&mut MlsGroup, &Client) -> T,
    ) -> T {
        let mut groups = client.lock_groups().unwrap();
        f(groups.get_mut(group_id).unwrap(), client)
    }

    fn commit_bytes(message: &MlsMessageOut) -> Vec<u8> {
        message.tls_serialize_detached().unwrap()
    }

    fn staged_commit(incoming: Incoming) -> (u32, LeafInfo, Vec<LeafInfo>, Vec<LeafInfo>, bool) {
        match incoming {
            Incoming::Commit {
                token,
                sender,
                added,
                removed,
                self_removed,
                ..
            } => (token, sender, added, removed, self_removed),
            _ => panic!("expected a commit"),
        }
    }

    #[test]
    fn a_prepared_commit_is_pending_until_merged_and_clear_wipes_it() {
        let (alice, bob, group_id) = pair();
        let carol = client("carol");
        let before = storage_keys(&alice);
        let epoch = alice.epoch(&group_id).unwrap();

        let prepared = alice
            .prepare_add(&group_id, &[key_package(&carol)])
            .unwrap();
        assert_eq!(prepared.epoch, epoch);
        assert_eq!(alice.epoch(&group_id).unwrap(), epoch, "not applied yet");
        assert_eq!(alice.member_count(&group_id).unwrap(), 2);
        assert!(alice.has_pending_commit(&group_id).unwrap());
        assert_eq!(prepared.added.len(), 1);
        assert_eq!(prepared.added[0].username, "carol");
        assert_eq!(
            alice.prepare_update(&group_id).err(),
            Some(ErrorCode::PendingOperation),
            "one pending commit per group"
        );

        // A rejected commit: wiped, and the group is exactly as before (storage keys included).
        alice.clear_pending(&group_id).unwrap();
        assert!(!alice.has_pending_commit(&group_id).unwrap());
        assert_eq!(alice.clear_pending(&group_id), Err(ErrorCode::UnknownToken));
        assert_eq!(
            storage_keys(&alice),
            before,
            "clear_pending leaves no path secrets behind"
        );

        // An accepted one: merged, and the others can process it.
        let prepared = alice
            .prepare_add(&group_id, &[key_package(&carol)])
            .unwrap();
        assert_eq!(alice.merge_pending(&group_id).unwrap(), epoch + 1);
        assert_eq!(bob.process(&group_id, &prepared.commit).unwrap(), None);
        carol
            .join_from_welcome(&prepared.welcome.unwrap(), &group_id)
            .unwrap();
        assert_eq!(carol.member_count(&group_id).unwrap(), 3);
        assert_eq!(alice.merge_pending(&group_id), Err(ErrorCode::UnknownToken));
    }

    #[test]
    fn a_pending_commit_survives_an_export_and_can_still_be_merged() {
        let (alice, bob, group_id) = pair();
        let carol = client("carol");
        let prepared = alice
            .prepare_add(&group_id, &[key_package(&carol)])
            .unwrap();
        let restored = Client::restore(&alice.export_state().unwrap(), key()).unwrap();
        assert!(restored.has_pending_commit(&group_id).unwrap());
        restored.merge_pending(&group_id).unwrap();
        assert_eq!(bob.process(&group_id, &prepared.commit).unwrap(), None);
        assert_eq!(restored.member_count(&group_id).unwrap(), 3);
    }

    #[test]
    fn a_staged_commit_changes_nothing_until_merged_and_blocks_export() {
        let (alice, bob, group_id) = pair();
        let carol = client("carol");
        let prepared = alice
            .prepare_add(&group_id, &[key_package(&carol)])
            .unwrap();
        alice.merge_pending(&group_id).unwrap();

        let incoming = bob.stage_incoming(&group_id, &prepared.commit).unwrap();
        let (token, sender, added, removed, self_removed) = staged_commit(incoming);
        assert_eq!(sender.username, "alice");
        assert_eq!(added.len(), 1);
        assert_eq!(added[0].username, "carol");
        assert!(removed.is_empty() && !self_removed);
        assert_eq!(
            bob.member_count(&group_id).unwrap(),
            2,
            "staged, not applied"
        );
        assert_eq!(bob.export_state().err(), Some(ErrorCode::PendingOperation));
        assert_eq!(
            bob.prepare_update(&group_id).err(),
            Some(ErrorCode::PendingOperation)
        );

        bob.merge_staged(token).unwrap();
        assert_eq!(bob.member_count(&group_id).unwrap(), 3);
        assert!(bob.export_state().is_ok());
        assert_eq!(bob.merge_staged(token), Err(ErrorCode::UnknownToken));
    }

    #[test]
    fn a_discarded_commit_cannot_be_processed_again() {
        let (alice, bob, group_id) = pair();
        let carol = client("carol");
        let prepared = alice
            .prepare_add(&group_id, &[key_package(&carol)])
            .unwrap();
        alice.merge_pending(&group_id).unwrap();
        let (token, ..) = staged_commit(bob.stage_incoming(&group_id, &prepared.commit).unwrap());
        bob.discard_staged(token).unwrap();
        assert!(bob.export_state().is_ok(), "nothing staged any more");
        assert_eq!(bob.member_count(&group_id).unwrap(), 2);
        // The secret is used up: the same bytes do not process again (the caller treats the group as stuck).
        assert!(bob.stage_incoming(&group_id, &prepared.commit).is_err());
    }

    #[test]
    fn application_messages_name_their_mls_sender_and_carry_the_same_reference() {
        let (alice, bob, group_id) = pair();
        let sent = alice.encrypt(&group_id, b"hi bob").unwrap();
        match bob.stage_incoming(&group_id, &sent.message).unwrap() {
            Incoming::Application {
                plaintext,
                sender,
                message_ref,
            } => {
                assert_eq!(plaintext, b"hi bob");
                assert_eq!(sender.username, "alice");
                assert_eq!(
                    sender.signature_key,
                    alice.public_identity().signature_public_key
                );
                assert_eq!(message_ref, sent.message_ref, "sender and receiver agree");
            }
            _ => panic!("unexpected result"),
        }
        let mut data = b"GifItUp v1 message ref".to_vec();
        data.extend_from_slice(&sent.message);
        let expected = RustCrypto::default()
            .hash(HashType::Sha2_256, &data)
            .unwrap();
        assert_eq!(sent.message_ref, expected);
        assert_eq!(sent.message_ref.len(), 32);
    }

    /// ADR-0006 B9: re-serialising a parsed message gives exactly the input bytes, so a reference hashed from the
    /// parsed form is the one hashed from the wire (catches a tls_codec/OpenMLS change that drops the `mls` feature).
    #[test]
    fn a_parsed_message_serialises_back_to_the_same_bytes() {
        let (alice, _bob, group_id) = pair();
        let message = alice.seal(&group_id, b"x");
        let parsed = MlsMessageIn::tls_deserialize_exact(&message).unwrap();
        let again = parsed.tls_serialize_detached().unwrap();
        assert_eq!(again, message);
    }

    #[test]
    fn a_message_is_processed_once_and_an_old_epoch_is_refused() {
        let (alice, bob, group_id) = pair();
        let sent = alice.seal(&group_id, b"once");
        assert!(bob.stage_incoming(&group_id, &sent).is_ok());
        assert_eq!(
            bob.stage_incoming(&group_id, &sent).err(),
            Some(ErrorCode::Replay),
            "the secret is used up"
        );

        let old = alice.seal(&group_id, b"old epoch");
        alice.prepare_update(&group_id).unwrap();
        alice.merge_pending(&group_id).unwrap();
        assert_eq!(
            alice.stage_incoming(&group_id, &old).err(),
            Some(ErrorCode::WrongEpoch)
        );
    }

    #[test]
    fn handshake_messages_carry_no_authenticated_data() {
        let (alice, _bob, group_id) = pair();
        let carol = client("carol");
        let prepared = alice
            .prepare_add(&group_id, &[key_package(&carol)])
            .unwrap();
        match MlsMessageIn::tls_deserialize_exact(&prepared.commit)
            .unwrap()
            .try_into_protocol_message()
            .unwrap()
        {
            ProtocolMessage::PrivateMessage(private) => assert!(private.aad().is_empty()),
            ProtocolMessage::PublicMessage(_) => panic!("a commit must be a PrivateMessage"),
        }
    }

    #[test]
    fn a_commit_that_swaps_the_committers_name_or_key_is_refused_and_not_merged() {
        // Name swap: Bob puts the credential "alice" with his own key into his path leaf.
        let (alice, bob, group_id) = pair();
        let swapped_name = with_group(&bob, &group_id, |group, client| {
            let credential = CredentialWithKey {
                credential: BasicCredential::new(b"alice".to_vec()).into(),
                signature_key: client.signer.to_public_vec().into(),
            };
            let params = LeafNodeParameters::builder()
                .with_credential_with_key(credential)
                .build();
            let bundle = group
                .self_update(&client.provider, &client.signer, params)
                .unwrap();
            commit_bytes(bundle.commit())
        });
        assert_eq!(
            alice.stage_incoming(&group_id, &swapped_name).err(),
            Some(ErrorCode::Refused)
        );
        assert_eq!(alice.epoch(&group_id).unwrap(), 1, "nothing was merged");

        // Key swap: Bob signs the new leaf with a fresh key.
        let (alice, bob, group_id) = pair();
        let swapped_key = with_group(&bob, &group_id, |group, client| {
            let fresh = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).unwrap();
            fresh.store(client.provider.storage()).unwrap();
            let credential = CredentialWithKey {
                credential: BasicCredential::new(b"bob".to_vec()).into(),
                signature_key: fresh.to_public_vec().into(),
            };
            let bundle = group
                .self_update_with_new_signer(
                    &client.provider,
                    &client.signer,
                    NewSignerBundle {
                        signer: &fresh,
                        credential_with_key: credential,
                    },
                    LeafNodeParameters::default(),
                )
                .unwrap();
            commit_bytes(bundle.commit())
        });
        assert_eq!(
            alice.stage_incoming(&group_id, &swapped_key).err(),
            Some(ErrorCode::Refused)
        );
    }

    #[test]
    fn a_commit_with_a_proposal_other_than_add_or_remove_is_refused() {
        let (alice, bob, group_id) = pair();
        let gce = with_group(&bob, &group_id, |group, client| {
            let (commit, _welcome, _info) = group
                .update_group_context_extensions(
                    &client.provider,
                    Extensions::empty(),
                    &client.signer,
                )
                .unwrap();
            commit_bytes(&commit)
        });
        assert_eq!(
            alice.stage_incoming(&group_id, &gce).err(),
            Some(ErrorCode::Refused)
        );
        assert_eq!(alice.epoch(&group_id).unwrap(), 1);
    }

    /// SEC-16: the change limit holds when a commit is received, not only when we prepare one (OpenMLS itself refuses
    /// a repeated signature key on both sides).
    #[test]
    fn a_received_commit_over_the_change_limit_is_refused() {
        let (alice, bob, group_id) = pair();
        let eleven: Vec<Vec<u8>> = (0..11)
            .map(|i| key_package(&client(&format!("user{i:02}"))))
            .collect();
        let raw_add = |packages: &[Vec<u8>]| {
            with_group(&bob, &group_id, |group, client| {
                let kps: Vec<KeyPackage> = packages
                    .iter()
                    .map(|b| client.parse_key_package(b).unwrap())
                    .collect();
                let (commit, _w, _i) = group
                    .add_members(&client.provider, &client.signer, &kps)
                    .unwrap();
                let bytes = commit_bytes(&commit);
                group
                    .clear_pending_commit(client.provider.storage())
                    .unwrap();
                bytes
            })
        };
        let too_many = raw_add(&eleven);
        assert_eq!(
            alice.stage_incoming(&group_id, &too_many).err(),
            Some(ErrorCode::LimitExceeded)
        );
    }

    /// CR-3 and CR-6: a batch repeating a signature key is refused; forgetting a group drops its staged welcomes.
    #[test]
    fn a_repeated_key_in_a_batch_is_refused_and_forgetting_drops_staged_welcomes() {
        let (alice, _bob, group_id) = pair();
        let carol = client("carol");
        let kp = key_package(&carol);
        let kp2 = key_package(&carol);
        assert_eq!(
            alice.prepare_add(&group_id, &[kp, kp2]).err(),
            Some(ErrorCode::Refused)
        );
        assert!(!alice.has_pending_commit(&group_id).unwrap());
        let prepared = alice
            .prepare_add(&group_id, &[key_package(&carol)])
            .unwrap();
        alice.merge_pending(&group_id).unwrap();
        let welcome = prepared.welcome.unwrap();
        let info = carol.inspect_welcome(&welcome, &group_id).unwrap();
        assert_eq!(
            carol.forget_group(&group_id).err(),
            Some(ErrorCode::UnknownGroup)
        );
        assert_eq!(
            carol.join_welcome(info.token).err(),
            Some(ErrorCode::UnknownToken)
        );
    }

    /// CR-10: the leaf cap holds for a received commit too (ghosts count).
    #[test]
    fn a_received_commit_over_the_leaf_cap_is_refused() {
        let (alice, bob, group_id) = pair();
        let mut next = 0;
        while alice.member_count(&group_id).unwrap() < 50 {
            let room = 50 - alice.member_count(&group_id).unwrap() as usize;
            let batch: Vec<Vec<u8>> = (0..room.min(10))
                .map(|_| {
                    next += 1;
                    key_package(&client(&format!("user{next:02}")))
                })
                .collect();
            let prepared = alice.prepare_add(&group_id, &batch).unwrap();
            alice.merge_pending(&group_id).unwrap();
            bob.process(&group_id, &prepared.commit).unwrap();
        }
        let extra = key_package(&client("extra"));
        let over = with_group(&bob, &group_id, |group, client| {
            let kp = client.parse_key_package(&extra).unwrap();
            let (commit, _w, _i) = group
                .add_members(&client.provider, &client.signer, &[kp])
                .unwrap();
            let bytes = commit_bytes(&commit);
            group
                .clear_pending_commit(client.provider.storage())
                .unwrap();
            bytes
        });
        assert_eq!(
            alice.stage_incoming(&group_id, &over).err(),
            Some(ErrorCode::LimitExceeded)
        );
    }

    /// CR-1: a staged commit and our own pending commit never coexist, in either order.
    #[test]
    fn a_staged_commit_blocks_merging_ours() {
        let (alice, bob, group_id) = pair();
        let theirs = bob
            .prepare_add(&group_id, &[key_package(&client("carol"))])
            .unwrap();
        alice.stage_incoming(&group_id, &theirs.commit).unwrap();
        assert_eq!(
            alice.prepare_update(&group_id).err(),
            Some(ErrorCode::PendingOperation)
        );
        assert_eq!(
            alice.merge_pending(&group_id).err(),
            Some(ErrorCode::PendingOperation)
        );
    }

    /// SEC-14: while our own commit is pending, nothing incoming is staged.
    #[test]
    fn nothing_is_staged_while_our_own_commit_is_pending() {
        let (alice, bob, group_id) = pair();
        let theirs = bob
            .prepare_add(&group_id, &[key_package(&client("carol"))])
            .unwrap();
        alice
            .prepare_add(&group_id, &[key_package(&client("dave"))])
            .unwrap();
        assert_eq!(
            alice.stage_incoming(&group_id, &theirs.commit).err(),
            Some(ErrorCode::PendingOperation)
        );
        alice.clear_pending(&group_id).unwrap();
        assert!(alice.stage_incoming(&group_id, &theirs.commit).is_ok());
    }

    #[test]
    fn a_tampered_message_fails_cleanly_and_the_genuine_one_still_works_or_is_a_replay() {
        let (alice, bob, group_id) = pair();
        let sent = alice.seal(&group_id, b"genuine");
        let mut tampered = sent.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 0x01;
        assert!(bob.stage_incoming(&group_id, &tampered).is_err());
        assert_eq!(bob.member_count(&group_id).unwrap(), 2);
        // Records what OpenMLS does after a failed AEAD check: the genuine message either decrypts or is a replay
        // (the ratchet generation may be used up). Both are closed outcomes; neither panics.
        match bob.stage_incoming(&group_id, &sent) {
            Ok(Incoming::Application { plaintext, .. }) => assert_eq!(plaintext, b"genuine"),
            Err(ErrorCode::Replay | ErrorCode::Mls) => {}
            _ => panic!("unexpected result"),
        }
    }

    #[test]
    fn removing_a_member_reports_who_goes_and_the_removed_device_learns_it() {
        let (alice, bob, group_id) = pair();
        let carol = client("carol");
        let added = alice.add_member(&group_id, &key_package(&carol)).unwrap();
        bob.process(&group_id, &added.commit).unwrap();
        carol.join_from_welcome(&added.welcome, &group_id).unwrap();

        let carol_leaf = alice
            .members(&group_id)
            .unwrap()
            .into_iter()
            .find(|l| l.username == "carol")
            .unwrap();
        assert_eq!(
            alice.prepare_remove(&group_id, &[0]).err(),
            Some(ErrorCode::InvalidInput),
            "never our own leaf"
        );
        let prepared = alice
            .prepare_remove(&group_id, &[carol_leaf.leaf_index])
            .unwrap();
        assert_eq!(prepared.removed, vec![carol_leaf]);
        alice.merge_pending(&group_id).unwrap();

        let (_, _, _, removed, self_removed) =
            staged_commit(bob.stage_incoming(&group_id, &prepared.commit).unwrap());
        assert_eq!(removed.len(), 1);
        assert!(!self_removed);
        let (token, _, _, _, self_removed) =
            staged_commit(carol.stage_incoming(&group_id, &prepared.commit).unwrap());
        assert!(self_removed, "the removed member sees that it is removed");
        carol.merge_staged(token).unwrap();
        carol.forget_group(&group_id).unwrap();
    }

    #[test]
    fn welcomes_are_inspected_once_and_join_only_for_the_group_they_were_fetched_for() {
        let alice = client("alice");
        let group_id = alice.create_group().unwrap();
        let bob = client("bob");
        let welcome = alice
            .prepare_add(&group_id, &[key_package(&bob)])
            .unwrap()
            .welcome
            .unwrap();
        alice.merge_pending(&group_id).unwrap();

        // Another group id than the one the welcome belongs to is refused.
        let other = vec![9; 16];
        assert_eq!(
            bob.inspect_welcome(&welcome, &other).err(),
            Some(ErrorCode::Refused)
        );
        // The first inspection consumed the KeyPackage: the right id no longer works either.
        assert_eq!(
            bob.inspect_welcome(&welcome, &group_id).err(),
            Some(ErrorCode::Mls)
        );

        let carol = client("carol");
        let welcome = alice
            .prepare_add(&group_id, &[key_package(&carol)])
            .unwrap()
            .welcome
            .unwrap();
        alice.merge_pending(&group_id).unwrap();
        let info = carol.inspect_welcome(&welcome, &group_id).unwrap();
        assert_eq!(info.group_id, group_id);
        assert_eq!(info.sender.username, "alice");
        let names: Vec<_> = info.members.iter().map(|m| m.username.as_str()).collect();
        assert_eq!(names.len(), 3);
        assert!(names.contains(&"carol") && names.contains(&"bob") && names.contains(&"alice"));
        assert_eq!(
            carol.member_count(&group_id),
            Err(ErrorCode::UnknownGroup),
            "not joined yet"
        );
        assert_eq!(
            carol.export_state().err(),
            Some(ErrorCode::PendingOperation)
        );
        assert_eq!(carol.join_welcome(info.token).unwrap(), group_id);
        assert_eq!(carol.join_welcome(info.token), Err(ErrorCode::UnknownToken));
        assert!(carol.export_state().is_ok());

        // A group of that id already exists here: refused, and nothing is overwritten.
        assert_eq!(
            alice.inspect_welcome(&welcome, &group_id).err(),
            Some(ErrorCode::Refused)
        );
        assert_eq!(alice.member_count(&group_id).unwrap(), 3);
    }

    #[test]
    fn a_discarded_welcome_joins_nothing() {
        let alice = client("alice");
        let group_id = alice.create_group().unwrap();
        let bob = client("bob");
        let welcome = alice
            .prepare_add(&group_id, &[key_package(&bob)])
            .unwrap()
            .welcome
            .unwrap();
        let info = bob.inspect_welcome(&welcome, &group_id).unwrap();
        bob.discard_welcome(info.token).unwrap();
        assert_eq!(bob.join_welcome(info.token), Err(ErrorCode::UnknownToken));
        assert_eq!(bob.member_count(&group_id), Err(ErrorCode::UnknownGroup));
        assert!(bob.export_state().is_ok());
    }

    #[test]
    fn the_limits_of_adr_0015_are_enforced_when_a_commit_is_prepared() {
        let alice = client("alice");
        let group_id = alice.create_group().unwrap();
        assert_eq!(
            alice.prepare_add(&group_id, &[]).err(),
            Some(ErrorCode::LimitExceeded)
        );
        let eleven: Vec<Vec<u8>> = (0..11)
            .map(|i| key_package(&client(&format!("user{i:02}"))))
            .collect();
        assert_eq!(
            alice.prepare_add(&group_id, &eleven).err(),
            Some(ErrorCode::LimitExceeded)
        );
        // Fill the group to its 50 leaves in batches of ten; the next member does not fit, ghosts included.
        let mut next = 0;
        while alice.member_count(&group_id).unwrap() < 50 {
            let room = 50 - alice.member_count(&group_id).unwrap() as usize;
            let batch: Vec<Vec<u8>> = (0..room.min(10))
                .map(|_| {
                    next += 1;
                    key_package(&client(&format!("user{next:02}")))
                })
                .collect();
            alice.prepare_add(&group_id, &batch).unwrap();
            alice.merge_pending(&group_id).unwrap();
        }
        assert_eq!(
            alice
                .prepare_add(&group_id, &[key_package(&client("extra"))])
                .err(),
            Some(ErrorCode::LimitExceeded)
        );
        assert!(!alice.has_pending_commit(&group_id).unwrap());
    }

    /// #22 plan review SEC-9: an old group whose members' leaves have expired must still take new members; only
    /// the added KeyPackages and our own count.
    #[test]
    fn a_group_with_expired_member_leaves_still_takes_new_members() {
        let alice = client("alice");
        let group_id = alice.create_group().unwrap();
        let bob = client("bob");
        let short = KeyPackage::builder()
            .key_package_lifetime(Lifetime::new(2))
            .build(
                CIPHERSUITE,
                &bob.provider,
                &bob.signer,
                bob.credential.clone(),
            )
            .unwrap();
        // Valid now: bob is added and joins.
        let kp = MlsMessageOut::from(short.key_package().clone())
            .tls_serialize_detached()
            .unwrap();
        let prepared = alice.prepare_add(&group_id, &[kp]).unwrap();
        alice.merge_pending(&group_id).unwrap();
        bob.join_from_welcome(&prepared.welcome.unwrap(), &group_id)
            .unwrap();

        std::thread::sleep(Duration::from_secs(4));

        let carol = client("carol");
        let prepared = alice
            .prepare_add(&group_id, &[key_package(&carol)])
            .unwrap();
        alice.merge_pending(&group_id).unwrap();
        carol
            .join_from_welcome(&prepared.welcome.unwrap(), &group_id)
            .unwrap();
        assert_eq!(carol.member_count(&group_id).unwrap(), 3);
    }
}
