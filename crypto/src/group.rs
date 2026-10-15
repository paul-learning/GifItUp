//! Group operations from the spikes (#3, #4).
//!
//! **SPIKE QUALITY, not part of the v1 facade** (ADR-0007; owner decision: group ops come with #22).
//! Callable only through the SPIKE-labelled FFI exports until PR 3 of #11 deletes the spikes; then
//! test-only until #22 adds member validation, staged commits checked against the DS envelope and a
//! typed `process()` result (see the checklist in #22). Commits are merged right away here.

use openmls::prelude::tls_codec::{Deserialize, Serialize};
use openmls::prelude::*;
use openmls_traits::OpenMlsProvider;

use crate::client::{CIPHERSUITE, Client};
use crate::error::{ErrorCode, input, mls};

/// MLS padding block size (ADR-0006 B7). Must be set in both the create and the join config.
pub(crate) const PADDING_SIZE: usize = 1024;

/// Largest application content that still fits one padding block with this ciphersuite:
/// 2 + 896 + 2 + 64 + 16 = 980 <= 1024 (ADR-0006 B7). Recompute if the ciphersuite changes.
pub(crate) const MAX_APPLICATION_CONTENT: usize = 896;

/// Result of adding a member: the Commit goes to existing members, the Welcome to the new one.
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Record))]
pub struct AddMemberResult {
    pub commit: Vec<u8>,
    pub welcome: Vec<u8>,
}

impl Client {
    /// Creates a new group with this client as the only member. Returns the group ID.
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

    /// Adds the owner of `key_package` to the group and merges the commit locally (SPIKE: without
    /// waiting for the Delivery Service, and without ADR-0002 member validation).
    pub fn add_member(
        &self,
        group_id: &[u8],
        key_package: &[u8],
    ) -> Result<AddMemberResult, ErrorCode> {
        let key_package = match MlsMessageIn::tls_deserialize_exact(key_package)
            .map_err(input)?
            .extract()
        {
            MlsMessageBodyIn::KeyPackage(kp) => kp
                .validate(self.provider.crypto(), ProtocolVersion::Mls10)
                .map_err(|_| ErrorCode::InvalidKeyPackage)?,
            _ => return Err(ErrorCode::InvalidInput),
        };
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(ErrorCode::UnknownGroup)?;
        let (commit, welcome, _group_info) = group
            .add_members(&self.provider, &self.signer, &[key_package])
            .map_err(mls)?;
        group.merge_pending_commit(&self.provider).map_err(mls)?;
        Ok(AddMemberResult {
            commit: commit.tls_serialize_detached().map_err(mls)?,
            welcome: welcome.tls_serialize_detached().map_err(mls)?,
        })
    }

    /// Joins a group from a Welcome message. Returns the group ID.
    pub fn join_from_welcome(&self, welcome: &[u8]) -> Result<Vec<u8>, ErrorCode> {
        let welcome = match MlsMessageIn::tls_deserialize_exact(welcome)
            .map_err(input)?
            .extract()
        {
            MlsMessageBodyIn::Welcome(w) => w,
            _ => return Err(ErrorCode::InvalidInput),
        };
        let join_config = MlsGroupJoinConfig::builder()
            .wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
            .padding_size(PADDING_SIZE)
            .use_ratchet_tree_extension(true)
            .build();
        let group = StagedWelcome::new_from_welcome(&self.provider, &join_config, welcome, None)
            .map_err(mls)?
            .into_group(&self.provider)
            .map_err(mls)?;
        let id = group.group_id().as_slice().to_vec();
        self.lock_groups()?.insert(id.clone(), group);
        Ok(id)
    }

    /// Encrypts an application message (at most [`MAX_APPLICATION_CONTENT`] bytes) for the group.
    /// The MLS authenticated data stays empty: it is sent in the clear (ADR-0006 B6).
    pub fn encrypt(&self, group_id: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, ErrorCode> {
        if plaintext.len() > MAX_APPLICATION_CONTENT {
            return Err(ErrorCode::InvalidInput);
        }
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(ErrorCode::UnknownGroup)?;
        group
            .create_message(&self.provider, &self.signer, plaintext)
            .map_err(mls)?
            .tls_serialize_detached()
            .map_err(mls)
    }

    /// Processes an incoming message. Returns the plaintext for application messages, or `None`
    /// for handshake messages (SPIKE: a Commit is merged immediately).
    pub fn process(&self, group_id: &[u8], message: &[u8]) -> Result<Option<Vec<u8>>, ErrorCode> {
        let protocol_message = MlsMessageIn::tls_deserialize_exact(message)
            .map_err(input)?
            .try_into_protocol_message()
            .map_err(input)?;
        let mut groups = self.lock_groups()?;
        let group = groups.get_mut(group_id).ok_or(ErrorCode::UnknownGroup)?;
        let processed = group
            .process_message(&self.provider, protocol_message)
            .map_err(mls)?;
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => Ok(Some(app.into_bytes())),
            ProcessedMessageContent::StagedCommitMessage(staged) => {
                group
                    .merge_staged_commit(&self.provider, *staged)
                    .map_err(mls)?;
                Ok(None)
            }
            _ => Ok(None),
        }
    }

    /// Number of members in the group (for tests and UI).
    pub fn member_count(&self, group_id: &[u8]) -> Result<u32, ErrorCode> {
        let groups = self.lock_groups()?;
        let group = groups.get(group_id).ok_or(ErrorCode::UnknownGroup)?;
        Ok(u32::try_from(group.members().count()).unwrap_or(u32::MAX))
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

    /// ADR-0006 B7: the full serialized message (what the server sees) has the same length for the
    /// smallest and the largest allowed content, from the group creator and from a joiner (padding
    /// is a local setting, so both configs must set it).
    #[test]
    fn every_allowed_content_length_gives_the_same_ciphertext_length() {
        let (alice, bob, group_id) = pair();
        let max = MAX_APPLICATION_CONTENT;
        let lengths: Vec<usize> = [(&alice, 1), (&alice, max), (&bob, 1), (&bob, max)]
            .iter()
            .map(|(sender, n)| sender.encrypt(&group_id, &vec![b'a'; *n]).unwrap().len())
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
        let message = alice.encrypt(&group_id, b"hi").unwrap();
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
    }

    #[test]
    fn rejects_garbage_input() {
        let alice = Client::new("alice".into()).unwrap();
        let group_id = alice.create_group().unwrap();
        assert_eq!(
            alice.add_member(&group_id, &[1, 2, 3]).err(),
            Some(ErrorCode::InvalidInput)
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
}
