//! State snapshot codec: the whole client state (identity, group IDs, OpenMLS storage) as one blob.
//!
//! **Still plaintext secret material.** PR 2 of #11 encrypts it inside Rust (ADR-0007); until then
//! the only callers are the spike FFI exports.
//!
//! Format: magic, snapshot version, OpenMLS storage version (u16-BE), username, signature public
//! key, group IDs, storage entries; every variable-length field is u32-BE length-prefixed.

use std::collections::{HashMap, HashSet};

use openmls_traits::storage::CURRENT_VERSION as STORAGE_VERSION;

use crate::error::ErrorCode;

const MAGIC: &[u8; 4] = b"GIUS";
const VERSION: u8 = 1;

/// Decoded snapshot contents. Encoding and decoding are pure; [`crate::client::Client`] turns a
/// snapshot into a live client.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Snapshot {
    pub(crate) username: String,
    pub(crate) signature_public_key: Vec<u8>,
    pub(crate) group_ids: Vec<Vec<u8>>,
    pub(crate) values: HashMap<Vec<u8>, Vec<u8>>,
}

impl Snapshot {
    pub(crate) fn encode(&self) -> Result<Vec<u8>, ErrorCode> {
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        out.push(VERSION);
        out.extend_from_slice(&STORAGE_VERSION.to_be_bytes());
        put(&mut out, self.username.as_bytes())?;
        put(&mut out, &self.signature_public_key)?;
        put_len(&mut out, self.group_ids.len())?;
        for id in &self.group_ids {
            put(&mut out, id)?;
        }
        put_len(&mut out, self.values.len())?;
        for (k, v) in &self.values {
            put(&mut out, k)?;
            put(&mut out, v)?;
        }
        Ok(out)
    }

    pub(crate) fn decode(bytes: &[u8]) -> Result<Self, ErrorCode> {
        let mut r = Reader(bytes);
        if r.take(4)? != MAGIC {
            return Err(ErrorCode::SnapshotInvalid);
        }
        // An unknown snapshot or OpenMLS storage version must be migrated explicitly, never read as-is.
        if r.take(1)? != [VERSION] || r.take(2)? != STORAGE_VERSION.to_be_bytes() {
            return Err(ErrorCode::SnapshotVersion);
        }
        let username =
            String::from_utf8(r.field()?.to_vec()).map_err(|_| ErrorCode::SnapshotInvalid)?;
        let signature_public_key = r.field()?.to_vec();
        let group_ids = r.group_ids()?;
        let values = r.values()?;
        if !r.0.is_empty() {
            return Err(ErrorCode::SnapshotInvalid);
        }
        Ok(Self {
            username,
            signature_public_key,
            group_ids,
            values,
        })
    }
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

    fn group_ids(&mut self) -> Result<Vec<Vec<u8>>, ErrorCode> {
        let mut ids: Vec<Vec<u8>> = Vec::new();
        let mut seen = HashSet::new();
        for _ in 0..self.len()? {
            let id = self.field()?;
            if !seen.insert(id) {
                return Err(ErrorCode::SnapshotInvalid);
            }
            ids.push(id.to_vec());
        }
        Ok(ids)
    }

    fn values(&mut self) -> Result<HashMap<Vec<u8>, Vec<u8>>, ErrorCode> {
        let mut values = HashMap::new();
        for _ in 0..self.len()? {
            let k = self.field()?.to_vec();
            if values.insert(k, self.field()?.to_vec()).is_some() {
                return Err(ErrorCode::SnapshotInvalid);
            }
        }
        Ok(values)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Snapshot {
        Snapshot {
            username: "alice".into(),
            signature_public_key: vec![1, 2, 3],
            group_ids: vec![vec![9], vec![8, 7]],
            values: HashMap::from([(vec![1], vec![2]), (vec![3], vec![])]),
        }
    }

    #[test]
    fn round_trips() {
        let encoded = sample().encode().unwrap();
        assert_eq!(Snapshot::decode(&encoded).unwrap(), sample());
    }

    #[test]
    fn every_truncation_is_rejected() {
        let encoded = sample().encode().unwrap();
        for len in 0..encoded.len() {
            assert!(Snapshot::decode(&encoded[..len]).is_err(), "length {len}");
        }
    }

    #[test]
    fn rejects_bad_magic_versions_and_trailing_bytes() {
        let encoded = sample().encode().unwrap();
        let mut bad_magic = encoded.clone();
        bad_magic[0] ^= 0xff;
        assert_eq!(
            Snapshot::decode(&bad_magic),
            Err(ErrorCode::SnapshotInvalid)
        );
        let mut other_version = encoded.clone();
        other_version[4] = 2;
        assert_eq!(
            Snapshot::decode(&other_version),
            Err(ErrorCode::SnapshotVersion)
        );
        // A different OpenMLS storage version (bytes 5..7 of the header).
        let mut other_storage = encoded.clone();
        other_storage[6] ^= 0xff;
        assert_eq!(
            Snapshot::decode(&other_storage),
            Err(ErrorCode::SnapshotVersion)
        );
        let mut trailing = encoded;
        trailing.push(0);
        assert_eq!(Snapshot::decode(&trailing), Err(ErrorCode::SnapshotInvalid));
    }

    #[test]
    fn rejects_duplicate_group_ids() {
        let mut duplicate = sample();
        duplicate.group_ids = vec![vec![9], vec![9]];
        let encoded = duplicate.encode().unwrap();
        assert_eq!(Snapshot::decode(&encoded), Err(ErrorCode::SnapshotInvalid));
    }
}
