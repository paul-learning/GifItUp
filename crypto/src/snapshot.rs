//! Encrypted state snapshot (ADR-0007): the whole client state (identity, group IDs, own
//! KeyPackage refs, OpenMLS storage) as one blob, encrypted inside Rust.
//!
//! Format v2: `magic ‖ version ‖ OpenMLS storage version (u16-BE) ‖ nonce[12] ‖ AES-256-GCM(inner)`.
//! The header is the AEAD's associated data, so it can't be swapped. The AEAD comes from the
//! OpenMLS crypto provider (nothing hand-rolled) with a fresh random nonce per export. The inner
//! plaintext is only ever decoded after decryption succeeded: there is no plaintext restore path.
//!
//! Inner layout: username, signature public key, group IDs, own KeyPackage refs, storage entries;
//! every variable-length field is u32-BE length-prefixed.

use std::collections::{HashMap, HashSet};

use openmls_traits::crypto::OpenMlsCrypto;
use openmls_traits::random::OpenMlsRand;
use openmls_traits::storage::CURRENT_VERSION as STORAGE_VERSION;
use openmls_traits::types::{AeadType, CryptoError};
use zeroize::{Zeroize, Zeroizing};

use crate::error::ErrorCode;

const MAGIC: &[u8; 4] = b"GIUS";
const VERSION: u8 = 2;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;
const HEADER_LEN: usize = 4 + 1 + 2 + NONCE_LEN;
/// Size cap before any parsing or decryption (a few small groups need well under 1 MiB).
pub(crate) const MAX_SNAPSHOT_BYTES: usize = 64 * 1024 * 1024;
pub(crate) const KEY_LEN: usize = 32;

/// The snapshot key, wiped from memory when dropped.
pub(crate) type SnapshotKey = Zeroizing<[u8; KEY_LEN]>;

/// Copies a caller-supplied key and wipes the caller's buffer (which Rust owns at this point).
pub(crate) fn take_key(mut key: Vec<u8>) -> Result<SnapshotKey, ErrorCode> {
    let result = <[u8; KEY_LEN]>::try_from(key.as_slice())
        .map(Zeroizing::new)
        .map_err(|_| ErrorCode::InvalidInput);
    key.zeroize();
    result
}

/// Decoded snapshot contents. Secret values are wiped when it is dropped, and `Debug` never
/// prints them (#65 SEC-3).
#[derive(PartialEq, Eq)]
pub(crate) struct Snapshot {
    pub(crate) username: String,
    pub(crate) signature_public_key: Vec<u8>,
    pub(crate) group_ids: Vec<Vec<u8>>,
    pub(crate) key_package_refs: Vec<Vec<u8>>,
    pub(crate) values: HashMap<Vec<u8>, Vec<u8>>,
}

impl std::fmt::Debug for Snapshot {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Snapshot")
            .field("username", &self.username)
            .field("group_ids", &self.group_ids.len())
            .field("key_package_refs", &self.key_package_refs.len())
            .field("values", &format_args!("<{} redacted>", self.values.len()))
            .finish_non_exhaustive()
    }
}

impl Drop for Snapshot {
    fn drop(&mut self) {
        for value in self.values.values_mut() {
            value.zeroize();
        }
    }
}

impl Snapshot {
    /// Encrypts the snapshot under `key` with a fresh random nonce.
    pub(crate) fn seal(
        &self,
        key: &SnapshotKey,
        crypto: &(impl OpenMlsCrypto + OpenMlsRand),
    ) -> Result<Vec<u8>, ErrorCode> {
        // Never write a snapshot that `open` would refuse (CR-3 on #65).
        if HEADER_LEN + self.inner_len() + TAG_LEN > MAX_SNAPSHOT_BYTES {
            return Err(ErrorCode::Internal);
        }
        let nonce: [u8; NONCE_LEN] = crypto.random_array().map_err(|_| ErrorCode::Internal)?;
        let mut out = header(&nonce);
        let inner = self.encode_inner()?;
        let sealed = crypto
            .aead_encrypt(AeadType::Aes256Gcm, key.as_slice(), &inner, &nonce, &out)
            .map_err(|_| ErrorCode::Internal)?;
        out.extend_from_slice(&sealed);
        Ok(out)
    }

    /// Checks the header and length, then decrypts and decodes. Nothing is parsed or passed to
    /// the provider before the length checks (a short nonce would make it panic).
    pub(crate) fn open(
        bytes: &[u8],
        key: &SnapshotKey,
        crypto: &impl OpenMlsCrypto,
    ) -> Result<Self, ErrorCode> {
        if bytes.len() > MAX_SNAPSHOT_BYTES || bytes.len() < HEADER_LEN + TAG_LEN {
            return Err(ErrorCode::SnapshotInvalid);
        }
        let (head, sealed) = bytes.split_at(HEADER_LEN);
        if head[..4] != MAGIC[..] {
            return Err(ErrorCode::SnapshotInvalid);
        }
        if head[4] != VERSION || head[5..7] != STORAGE_VERSION.to_be_bytes() {
            return Err(ErrorCode::SnapshotVersion);
        }
        let nonce: [u8; NONCE_LEN] = head[7..]
            .try_into()
            .map_err(|_| ErrorCode::SnapshotInvalid)?;
        let inner = crypto
            .aead_decrypt(AeadType::Aes256Gcm, key.as_slice(), sealed, &nonce, head)
            .map_err(|e| match e {
                CryptoError::AeadDecryptionError => ErrorCode::SnapshotAuth,
                _ => ErrorCode::Internal,
            })?;
        Self::decode_inner(&Zeroizing::new(inner))
    }

    /// Exact encoded size, so the buffer is allocated once: a growing `Vec` would leave
    /// unwiped copies of key material behind on every reallocation (CR-4 on #65).
    fn inner_len(&self) -> usize {
        let list = |items: &[Vec<u8>]| 4 + items.iter().map(|i| 4 + i.len()).sum::<usize>();
        let values: usize = self.values.iter().map(|(k, v)| 8 + k.len() + v.len()).sum();
        8 + self.username.len()
            + self.signature_public_key.len()
            + list(&self.group_ids)
            + list(&self.key_package_refs)
            + 4
            + values
    }

    pub(crate) fn encode_inner(&self) -> Result<Zeroizing<Vec<u8>>, ErrorCode> {
        let mut out = Zeroizing::new(Vec::with_capacity(self.inner_len()));
        put(&mut out, self.username.as_bytes())?;
        put(&mut out, &self.signature_public_key)?;
        put_list(&mut out, &self.group_ids)?;
        put_list(&mut out, &self.key_package_refs)?;
        put_len(&mut out, self.values.len())?;
        for (k, v) in &self.values {
            put(&mut out, k)?;
            put(&mut out, v)?;
        }
        Ok(out)
    }

    pub(crate) fn decode_inner(bytes: &[u8]) -> Result<Self, ErrorCode> {
        let mut r = Reader(bytes);
        let username =
            String::from_utf8(r.field()?.to_vec()).map_err(|_| ErrorCode::SnapshotInvalid)?;
        let signature_public_key = r.field()?.to_vec();
        let group_ids = r.unique_list()?;
        let key_package_refs = r.unique_list()?;
        // Built before the trailing-bytes check, so a rejected snapshot is wiped on drop too.
        let snapshot = Self {
            username,
            signature_public_key,
            group_ids,
            key_package_refs,
            values: r.values()?,
        };
        if r.0.is_empty() {
            Ok(snapshot)
        } else {
            Err(ErrorCode::SnapshotInvalid)
        }
    }
}

fn header(nonce: &[u8; NONCE_LEN]) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_LEN);
    out.extend_from_slice(MAGIC);
    out.push(VERSION);
    out.extend_from_slice(&STORAGE_VERSION.to_be_bytes());
    out.extend_from_slice(nonce);
    out
}

fn put_len(out: &mut Vec<u8>, len: usize) -> Result<(), ErrorCode> {
    let len = u32::try_from(len).map_err(|_| ErrorCode::Internal)?;
    out.extend_from_slice(&len.to_be_bytes());
    Ok(())
}

fn put(out: &mut Vec<u8>, bytes: &[u8]) -> Result<(), ErrorCode> {
    put_len(out, bytes.len())?;
    out.extend_from_slice(bytes);
    Ok(())
}

fn put_list(out: &mut Vec<u8>, items: &[Vec<u8>]) -> Result<(), ErrorCode> {
    put_len(out, items.len())?;
    items.iter().try_for_each(|item| put(out, item))
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], ErrorCode> {
        let (head, tail) = self
            .0
            .split_at_checked(n)
            .ok_or(ErrorCode::SnapshotInvalid)?;
        self.0 = tail;
        Ok(head)
    }

    fn len(&mut self) -> Result<usize, ErrorCode> {
        let bytes: [u8; 4] = self
            .take(4)?
            .try_into()
            .map_err(|_| ErrorCode::SnapshotInvalid)?;
        usize::try_from(u32::from_be_bytes(bytes)).map_err(|_| ErrorCode::SnapshotInvalid)
    }

    fn field(&mut self) -> Result<&'a [u8], ErrorCode> {
        let n = self.len()?;
        self.take(n)
    }

    fn unique_list(&mut self) -> Result<Vec<Vec<u8>>, ErrorCode> {
        let mut items = Vec::new();
        let mut seen = HashSet::new();
        for _ in 0..self.len()? {
            let item = self.field()?;
            if !seen.insert(item) {
                return Err(ErrorCode::SnapshotInvalid);
            }
            items.push(item.to_vec());
        }
        Ok(items)
    }

    /// Storage entries; on an error, everything decoded so far is wiped before it is dropped.
    fn values(&mut self) -> Result<HashMap<Vec<u8>, Vec<u8>>, ErrorCode> {
        let mut values = HashMap::new();
        let result = self.read_values(&mut values);
        if result.is_err() {
            values.values_mut().for_each(Zeroize::zeroize);
        }
        result.map(|()| values)
    }

    fn read_values(&mut self, values: &mut HashMap<Vec<u8>, Vec<u8>>) -> Result<(), ErrorCode> {
        for _ in 0..self.len()? {
            let k = self.field()?.to_vec();
            if let Some(mut duplicate) = values.insert(k, self.field()?.to_vec()) {
                duplicate.zeroize();
                return Err(ErrorCode::SnapshotInvalid);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use openmls_rust_crypto::RustCrypto;

    use super::*;

    fn sample() -> Snapshot {
        Snapshot {
            username: "alice".into(),
            signature_public_key: vec![1, 2, 3],
            group_ids: vec![vec![9], vec![8, 7]],
            key_package_refs: vec![vec![5; 32]],
            values: HashMap::from([(vec![1], vec![2]), (vec![3], vec![])]),
        }
    }

    fn key(byte: u8) -> SnapshotKey {
        Zeroizing::new([byte; KEY_LEN])
    }

    #[test]
    fn round_trips() {
        let crypto = RustCrypto::default();
        let sealed = sample().seal(&key(1), &crypto).unwrap();
        assert_eq!(Snapshot::open(&sealed, &key(1), &crypto).unwrap(), sample());
    }

    #[test]
    fn same_state_seals_differently_each_time() {
        let crypto = RustCrypto::default();
        assert_ne!(
            sample().seal(&key(1), &crypto).unwrap(),
            sample().seal(&key(1), &crypto).unwrap()
        );
    }

    #[test]
    fn wrong_key_and_every_flipped_byte_fail_authentication_or_header_checks() {
        let crypto = RustCrypto::default();
        let sealed = sample().seal(&key(1), &crypto).unwrap();
        assert_eq!(
            Snapshot::open(&sealed, &key(2), &crypto),
            Err(ErrorCode::SnapshotAuth)
        );
        for i in 0..sealed.len() {
            let mut tampered = sealed.clone();
            tampered[i] ^= 0x01;
            let expected = match i {
                0..4 => ErrorCode::SnapshotInvalid,
                4..7 => ErrorCode::SnapshotVersion,
                _ => ErrorCode::SnapshotAuth,
            };
            assert_eq!(
                Snapshot::open(&tampered, &key(1), &crypto),
                Err(expected),
                "byte {i}"
            );
        }
    }

    /// #11 plan review SEC-6: a short input must never reach the provider (a short nonce panics).
    #[test]
    fn every_truncation_is_rejected_without_panicking() {
        let crypto = RustCrypto::default();
        let sealed = sample().seal(&key(1), &crypto).unwrap();
        for len in 0..sealed.len() {
            assert!(
                Snapshot::open(&sealed[..len], &key(1), &crypto).is_err(),
                "length {len}"
            );
        }
    }

    /// #11 plan review SEC-11: the unencrypted spike format (version 1) is never accepted.
    #[test]
    fn spike_format_is_rejected() {
        let crypto = RustCrypto::default();
        let mut spike = b"GIUS\x01".to_vec();
        spike.extend_from_slice(&STORAGE_VERSION.to_be_bytes());
        spike.extend_from_slice(&sample().encode_inner().unwrap());
        assert_eq!(
            Snapshot::open(&spike, &key(1), &crypto),
            Err(ErrorCode::SnapshotVersion)
        );
    }

    #[test]
    fn oversized_input_is_rejected_before_parsing() {
        let crypto = RustCrypto::default();
        let huge = vec![0u8; MAX_SNAPSHOT_BYTES + 1];
        assert_eq!(
            Snapshot::open(&huge, &key(1), &crypto),
            Err(ErrorCode::SnapshotInvalid)
        );
    }

    #[test]
    fn debug_output_redacts_stored_values() {
        let mut snapshot = sample();
        snapshot.values.insert(b"k".to_vec(), b"MARKER".to_vec());
        let printed = format!("{snapshot:?}");
        // Neither the text nor its byte values ("77, 65, 82" = "MAR") may appear.
        assert!(!printed.contains("MARKER") && !printed.contains("77, 65, 82"));
    }

    #[test]
    fn encoding_allocates_exactly_once() {
        let inner = sample().encode_inner().unwrap();
        assert_eq!(inner.len(), sample().inner_len());
        assert_eq!(inner.capacity(), inner.len());
    }

    #[test]
    fn refuses_to_seal_what_it_could_not_open() {
        let crypto = RustCrypto::default();
        let mut big = sample();
        big.values.insert(vec![0], vec![0; MAX_SNAPSHOT_BYTES]);
        assert_eq!(big.seal(&key(1), &crypto), Err(ErrorCode::Internal));
    }

    #[test]
    fn keys_must_be_32_bytes() {
        assert!(take_key(vec![7; KEY_LEN]).is_ok());
        assert_eq!(take_key(vec![7; 16]).err(), Some(ErrorCode::InvalidInput));
        assert_eq!(take_key(vec![7; 33]).err(), Some(ErrorCode::InvalidInput));
    }

    #[test]
    fn inner_rejects_truncation_trailing_bytes_and_duplicates() {
        let inner = sample().encode_inner().unwrap();
        for len in 0..inner.len() {
            assert!(
                Snapshot::decode_inner(&inner[..len]).is_err(),
                "length {len}"
            );
        }
        let mut trailing = inner.to_vec();
        trailing.push(0);
        assert_eq!(
            Snapshot::decode_inner(&trailing),
            Err(ErrorCode::SnapshotInvalid)
        );
        let mut duplicate = sample();
        duplicate.group_ids = vec![vec![9], vec![9]];
        let encoded = duplicate.encode_inner().unwrap();
        assert_eq!(
            Snapshot::decode_inner(&encoded),
            Err(ErrorCode::SnapshotInvalid)
        );
    }
}
