//! Shared helpers for the crate's unit tests.

use std::collections::HashSet;

use zeroize::Zeroizing;

use crate::client::Client;
use crate::error::ErrorCode;
use crate::group::Incoming;
use crate::snapshot::{KEY_LEN, SnapshotKey};

pub(crate) fn key() -> SnapshotKey {
    Zeroizing::new([7; KEY_LEN])
}

pub(crate) fn client(username: &str) -> Client {
    Client::new(username.into(), key()).unwrap()
}

pub(crate) fn key_package(client: &Client) -> Vec<u8> {
    client
        .create_key_packages(1)
        .unwrap()
        .pop()
        .unwrap()
        .key_package
}

/// Result of the test helper [`Client::add_member`].
pub(crate) struct AddMemberResult {
    pub(crate) commit: Vec<u8>,
    pub(crate) welcome: Vec<u8>,
}

impl Client {
    /// Test shortcut: prepares an add and merges it right away, as if the Delivery Service had accepted it.
    pub(crate) fn add_member(
        &self,
        group_id: &[u8],
        key_package: &[u8],
    ) -> Result<AddMemberResult, ErrorCode> {
        let prepared = self.prepare_add(group_id, &[key_package.to_vec()])?;
        self.merge_pending(group_id)?;
        Ok(AddMemberResult {
            commit: prepared.commit,
            welcome: prepared.welcome.unwrap(),
        })
    }

    /// Test shortcut: inspects a welcome for `group_id` and joins it.
    pub(crate) fn join_from_welcome(
        &self,
        welcome: &[u8],
        group_id: &[u8],
    ) -> Result<Vec<u8>, ErrorCode> {
        let info = self.inspect_welcome(welcome, group_id)?;
        self.join_welcome(info.token)
    }

    /// Test shortcut: stages a message and merges a commit right away. `None` for a commit.
    pub(crate) fn process(
        &self,
        group_id: &[u8],
        message: &[u8],
    ) -> Result<Option<Vec<u8>>, ErrorCode> {
        match self.stage_incoming(group_id, message)? {
            Incoming::Application { plaintext, .. } => Ok(Some(plaintext)),
            Incoming::Commit { token, .. } => self.merge_staged(token).map(|_| None),
        }
    }

    /// Test shortcut: the ciphertext only.
    pub(crate) fn seal(&self, group_id: &[u8], plaintext: &[u8]) -> Vec<u8> {
        self.encrypt(group_id, plaintext).unwrap().message
    }
}

/// Alice creates a group and adds Bob, who joins from the Welcome.
pub(crate) fn pair() -> (Client, Client, Vec<u8>) {
    let alice = client("alice");
    let bob = client("bob");
    let group_id = alice.create_group().unwrap();
    let added = alice.add_member(&group_id, &key_package(&bob)).unwrap();
    assert_eq!(
        bob.join_from_welcome(&added.welcome, &group_id).unwrap(),
        group_id
    );
    (alice, bob, group_id)
}

pub(crate) fn storage_keys(client: &Client) -> HashSet<Vec<u8>> {
    client
        .provider
        .storage
        .values
        .read()
        .unwrap()
        .keys()
        .cloned()
        .collect()
}
