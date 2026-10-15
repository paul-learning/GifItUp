//! Shared helpers for the crate's unit tests.

use std::collections::HashSet;

use zeroize::Zeroizing;

use crate::client::Client;
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

/// Alice creates a group and adds Bob, who joins from the Welcome.
pub(crate) fn pair() -> (Client, Client, Vec<u8>) {
    let alice = client("alice");
    let bob = client("bob");
    let group_id = alice.create_group().unwrap();
    let added = alice.add_member(&group_id, &key_package(&bob)).unwrap();
    assert_eq!(bob.join_from_welcome(&added.welcome).unwrap(), group_id);
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
