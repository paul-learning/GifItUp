//! Identity rules and KeyPackages (ADR-0007): the username rule, this device's own KeyPackages
//! (create, track, prune) and inspection of someone else's KeyPackage before it is used.

use openmls::prelude::tls_codec::{Deserialize, Serialize};
use openmls::prelude::*;
use openmls::treesync::errors::LifetimeError;
use openmls_traits::OpenMlsProvider;
use openmls_traits::storage::StorageProvider;

use crate::client::{CIPHERSUITE, Client};
use crate::error::{ErrorCode, mls};

/// Most KeyPackages one call may create (the directory refills in batches, #14).
pub(crate) const MAX_KEY_PACKAGES_PER_CALL: u32 = 100;
/// Most unused own KeyPackages held at once: each keeps two private keys until used or pruned.
pub(crate) const MAX_KEY_PACKAGES_HELD: usize = 200;
/// Size cap before parsing someone else's KeyPackage (ours are about 300 bytes).
const MAX_KEY_PACKAGE_BYTES: usize = 16 * 1024;

/// Owner decision 2026-10-15 (ADR-0007): lowercase ASCII letters, digits and `_`, 3 to 32 bytes.
/// The server (#12) uses the same rule, byte for byte.
pub fn is_valid_username(username: &str) -> bool {
    (3..=32).contains(&username.len())
        && username
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}

/// What others may know about this device: the username in its credential and its signature key.
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Record))]
pub struct PublicIdentity {
    pub username: String,
    pub signature_public_key: Vec<u8>,
}

/// A new own KeyPackage: the bytes to upload, its reference and its expiry (Unix seconds), so the
/// directory (#14) gets its metadata without parsing KeyPackages in Kotlin.
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Record))]
pub struct CreatedKeyPackage {
    pub key_package: Vec<u8>,
    pub key_package_ref: Vec<u8>,
    pub not_after: u64,
}

impl Client {
    pub fn public_identity(&self) -> PublicIdentity {
        PublicIdentity {
            username: self.username.clone(),
            signature_public_key: self.signer.to_public_vec(),
        }
    }

    /// Creates `count` single-use KeyPackages (OpenMLS default lifetime, not last-resort). Their
    /// private keys stay in the client state until a Welcome uses them or they are pruned.
    pub fn create_key_packages(&self, count: u32) -> Result<Vec<CreatedKeyPackage>, ErrorCode> {
        if !(1..=MAX_KEY_PACKAGES_PER_CALL).contains(&count) {
            return Err(ErrorCode::InvalidInput);
        }
        let mut refs = self.lock_key_package_refs()?;
        // Forget only refs a Welcome provably used; a failed lookup keeps the ref tracked.
        refs.retain(|r| !matches!(self.stored_key_package(r), Ok(None)));
        if refs.len() + count as usize > MAX_KEY_PACKAGES_HELD {
            return Err(ErrorCode::InvalidInput);
        }
        (0..count)
            .map(|_| {
                let (created, key_package_ref) = self.create_key_package()?;
                refs.push(key_package_ref);
                Ok(created)
            })
            .collect()
    }

    fn create_key_package(&self) -> Result<(CreatedKeyPackage, KeyPackageRef), ErrorCode> {
        let bundle = KeyPackage::builder()
            .build(
                CIPHERSUITE,
                &self.provider,
                &self.signer,
                self.credential.clone(),
            )
            .map_err(mls)?;
        let key_package = bundle.key_package();
        let key_package_ref = key_package.hash_ref(self.provider.crypto()).map_err(mls)?;
        let created = CreatedKeyPackage {
            key_package: MlsMessageOut::from(key_package.clone())
                .tls_serialize_detached()
                .map_err(mls)?,
            key_package_ref: key_package_ref.as_slice().to_vec(),
            not_after: key_package.life_time().not_after(),
        };
        Ok((created, key_package_ref))
    }

    /// Deletes the private keys of own KeyPackages whose `not_after` has passed, and forgets the
    /// ones a Welcome already used. Returns how many were deleted. Only "expired" counts: a
    /// KeyPackage that looks not-yet-valid because the clock went back is kept. On an error the
    /// tracked list stays as it was, so no ref is lost while its keys are still stored.
    pub fn prune_expired_key_packages(&self) -> Result<u32, ErrorCode> {
        let mut refs = self.lock_key_package_refs()?;
        let mut kept = Vec::with_capacity(refs.len());
        let mut deleted = 0;
        for r in refs.iter() {
            match self.stored_key_package(r) {
                Ok(None) => {}
                Ok(Some(kp)) if is_expired(kp.key_package().life_time()) => {
                    self.provider.storage().delete_key_package(r).map_err(mls)?;
                    deleted += 1;
                }
                _ => kept.push(r.clone()),
            }
        }
        *refs = kept;
        Ok(deleted)
    }

    fn stored_key_package(
        &self,
        key_package_ref: &KeyPackageRef,
    ) -> Result<Option<KeyPackageBundle>, ErrorCode> {
        self.provider
            .storage()
            .key_package(key_package_ref)
            .map_err(mls)
    }

    /// Checks someone else's KeyPackage before it is used: exact parse, signatures, protocol
    /// version, expiry, the maximum lifetime range OpenMLS doesn't check itself, our ciphersuite,
    /// a Basic credential naming `expected_username` (which must follow the username rule), and
    /// no last-resort KeyPackages in v1 (#14 decides). `expected_username` comes from the
    /// untrusted server, so this is a consistency check; TOFU on the key (#18) authenticates.
    pub fn inspect_key_package(
        &self,
        key_package: &[u8],
        expected_username: &str,
    ) -> Result<PublicIdentity, ErrorCode> {
        #[cfg(feature = "test-panic")]
        crate::test_panic::maybe_panic(expected_username);
        if key_package.len() > MAX_KEY_PACKAGE_BYTES || !is_valid_username(expected_username) {
            return Err(ErrorCode::InvalidInput);
        }
        let key_package = match MlsMessageIn::tls_deserialize_exact(key_package)
            .map_err(|_| ErrorCode::InvalidKeyPackage)?
            .extract()
        {
            MlsMessageBodyIn::KeyPackage(kp) => kp
                .validate(self.provider.crypto(), ProtocolVersion::Mls10)
                .map_err(|_| ErrorCode::InvalidKeyPackage)?,
            _ => return Err(ErrorCode::InvalidKeyPackage),
        };
        check_key_package(&key_package, expected_username)?;
        let leaf = key_package.leaf_node();
        Ok(PublicIdentity {
            username: expected_username.to_owned(),
            signature_public_key: leaf.signature_key().as_slice().to_vec(),
        })
    }
}

fn is_expired(lifetime: &Lifetime) -> bool {
    matches!(lifetime.validate(), Err(LifetimeError::Expired { .. }))
}

fn check_key_package(key_package: &KeyPackage, expected_username: &str) -> Result<(), ErrorCode> {
    let leaf = key_package.leaf_node();
    let identity = BasicCredential::try_from(leaf.credential().clone())
        .map_err(|_| ErrorCode::InvalidKeyPackage)?;
    let acceptable = key_package.ciphersuite() == CIPHERSUITE
        && key_package.life_time().has_acceptable_range()
        && !key_package.last_resort()
        && identity.identity() == expected_username.as_bytes();
    if acceptable {
        Ok(())
    } else {
        Err(ErrorCode::InvalidKeyPackage)
    }
}

#[cfg(test)]
mod tests {
    use std::time::{SystemTime, UNIX_EPOCH};

    use openmls_basic_credential::SignatureKeyPair;

    use super::*;
    use crate::provider::Provider;
    use crate::test_support::{client, key_package};

    const YEAR: u64 = 365 * 24 * 60 * 60;

    fn now() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs()
    }

    /// Builds a KeyPackage outside our own client, so every field can be chosen.
    fn foreign_key_package(
        username: &str,
        ciphersuite: Ciphersuite,
        lifetime: Option<Lifetime>,
        last_resort: bool,
    ) -> Vec<u8> {
        let provider = Provider::default();
        let signer = SignatureKeyPair::new(ciphersuite.signature_algorithm()).unwrap();
        let credential = CredentialWithKey {
            credential: BasicCredential::new(username.as_bytes().to_vec()).into(),
            signature_key: signer.to_public_vec().into(),
        };
        let mut builder = KeyPackage::builder();
        if let Some(lifetime) = lifetime {
            builder = builder.key_package_lifetime(lifetime);
        }
        if last_resort {
            builder = builder.mark_as_last_resort();
        }
        let bundle = builder
            .build(ciphersuite, &provider, &signer, credential)
            .unwrap();
        MlsMessageOut::from(bundle.key_package().clone())
            .tls_serialize_detached()
            .unwrap()
    }

    #[test]
    fn username_rule() {
        for ok in ["abc", "alice", "bob_42", "a".repeat(32).as_str(), "___"] {
            assert!(is_valid_username(ok), "{ok}");
        }
        let too_long = "a".repeat(33);
        for bad in [
            "",
            "ab",
            too_long.as_str(),
            "Alice",
            "al ice",
            "al-ice",
            "al.ice",
            "älice",
            "alice\n",
            "аlice",
        ] {
            assert!(!is_valid_username(bad), "{bad:?}");
        }
        assert_eq!(
            Client::new("Alice".into(), crate::test_support::key()).err(),
            Some(ErrorCode::InvalidUsername)
        );
    }

    #[test]
    fn public_identity_names_the_user_and_key() {
        let alice = client("alice");
        let identity = alice.public_identity();
        assert_eq!(identity.username, "alice");
        assert_eq!(identity.signature_public_key, alice.signer.to_public_vec());
    }

    #[test]
    fn creates_key_packages_within_the_limits() {
        let alice = client("alice");
        assert_eq!(
            alice.create_key_packages(0).err(),
            Some(ErrorCode::InvalidInput)
        );
        assert_eq!(
            alice
                .create_key_packages(MAX_KEY_PACKAGES_PER_CALL + 1)
                .err(),
            Some(ErrorCode::InvalidInput)
        );
        let created = alice.create_key_packages(3).unwrap();
        assert_eq!(created.len(), 3);
        let expected_expiry = now() + 84 * 24 * 60 * 60;
        for kp in &created {
            assert_eq!(kp.key_package_ref.len(), 32);
            assert!(kp.not_after.abs_diff(expected_expiry) < 2 * 60 * 60);
            assert!(alice.inspect_key_package(&kp.key_package, "alice").is_ok());
        }
    }

    #[test]
    fn caps_the_number_of_unused_key_packages_held() {
        let alice = client("alice");
        alice
            .create_key_packages(MAX_KEY_PACKAGES_PER_CALL)
            .unwrap();
        alice
            .create_key_packages(MAX_KEY_PACKAGES_PER_CALL)
            .unwrap();
        assert_eq!(
            alice.create_key_packages(1).err(),
            Some(ErrorCode::InvalidInput)
        );
    }

    /// Creates an own KeyPackage with the given lifetime and tracks it like the others.
    fn tracked_key_package(client: &Client, lifetime: Lifetime) -> KeyPackageRef {
        let bundle = KeyPackage::builder()
            .key_package_lifetime(lifetime)
            .build(
                CIPHERSUITE,
                &client.provider,
                &client.signer,
                client.credential.clone(),
            )
            .unwrap();
        let r = bundle
            .key_package()
            .hash_ref(client.provider.crypto())
            .unwrap();
        client.lock_key_package_refs().unwrap().push(r.clone());
        r
    }

    #[test]
    fn prunes_expired_and_forgets_used_key_packages() {
        let alice = client("alice");
        let bob = client("bob");
        let used = key_package(&bob);
        bob.create_key_packages(1).unwrap();
        let expired = tracked_key_package(&bob, Lifetime::init(now() - 200, now() - 100));
        // Looks not yet valid, as after the clock jumped back: must survive (CR-2 on #65).
        let future = tracked_key_package(&bob, Lifetime::init(now() + 100, now() + 200));

        let group_id = alice.create_group().unwrap();
        let welcome = alice.add_member(&group_id, &used).unwrap().welcome;
        bob.join_from_welcome(&welcome).unwrap();

        assert_eq!(bob.prune_expired_key_packages().unwrap(), 1);
        let left = bob.lock_key_package_refs().unwrap().len();
        assert_eq!(left, 2, "the unused valid one and the not-yet-valid one");
        assert!(bob.stored_key_package(&expired).unwrap().is_none());
        assert!(bob.stored_key_package(&future).unwrap().is_some());
    }

    #[test]
    fn inspect_rejects_what_must_not_be_used() {
        let alice = client("alice");
        let suite = CIPHERSUITE;
        let valid = foreign_key_package("bob", suite, None, false);
        assert_eq!(
            alice.inspect_key_package(&valid, "bob").unwrap().username,
            "bob"
        );
        let cases: [(&str, Vec<u8>, &str); 6] = [
            ("wrong username", valid.clone(), "carol"),
            ("not a key package", vec![0, 1, 0, 5], "bob"),
            ("trailing bytes", [valid.clone(), vec![0]].concat(), "bob"),
            (
                "ten-year lifetime (SEC-1)",
                foreign_key_package(
                    "bob",
                    suite,
                    Some(Lifetime::init(now() - 60, now() + 10 * YEAR)),
                    false,
                ),
                "bob",
            ),
            (
                "expired",
                foreign_key_package(
                    "bob",
                    suite,
                    Some(Lifetime::init(now() - 200, now() - 100)),
                    false,
                ),
                "bob",
            ),
            (
                "last resort",
                foreign_key_package("bob", suite, None, true),
                "bob",
            ),
        ];
        for (name, bytes, username) in cases {
            assert_eq!(
                alice.inspect_key_package(&bytes, username).err(),
                Some(ErrorCode::InvalidKeyPackage),
                "{name}"
            );
        }
    }

    #[test]
    fn inspect_rejects_other_ciphersuites_and_bad_arguments() {
        let alice = client("alice");
        let valid = foreign_key_package("bob", CIPHERSUITE, None, false);
        let other_suite = foreign_key_package(
            "bob",
            Ciphersuite::MLS_128_DHKEMP256_AES128GCM_SHA256_P256,
            None,
            false,
        );
        assert_eq!(
            alice.inspect_key_package(&other_suite, "bob").err(),
            Some(ErrorCode::InvalidKeyPackage)
        );
        assert_eq!(
            alice.inspect_key_package(&valid, "Bob").err(),
            Some(ErrorCode::InvalidInput)
        );
        assert_eq!(
            alice
                .inspect_key_package(&vec![0; MAX_KEY_PACKAGE_BYTES + 1], "bob")
                .err(),
            Some(ErrorCode::InvalidInput)
        );
    }
}
