//! Key fingerprint (ADR-0014): 12 indices into the 256-entry emoji table that the app holds, derived from a signature
//! public key, so two people can compare what their phones show for the same key.
//!
//! ```text
//! digest = SHA-256( "gifitup/fingerprint/v1" || 0x00 || key )     // key: exactly 32 bytes (Ed25519)
//! output = digest[0..12]                                          // each byte is an index into the table
//! ```
//!
//! 96 bits: an attacker who wants a forged key whose fingerprint equals a given one needs about 2^96 key generations,
//! **if the whole fingerprint is compared**; matching only the first k bytes costs about 2^(8k) (ADR-0014). The input
//! is a public key and there is no secret, so there is no iteration, no randomness and nothing to wipe. The label names
//! the format: the emoji table (`fingerprint-emojis-v1.tsv`) is part of it, and a new table or a different key type
//! is a new label (`v2`), never a silent change. The crate returns indices only; the glyphs live in the app.

use openmls::prelude::HashType;
use openmls_traits::crypto::OpenMlsCrypto;

use crate::error::ErrorCode;

/// Domain separation and format version. The `0x00` after it ends the label, so no key can run into it.
pub(crate) const LABEL: &[u8] = b"gifitup/fingerprint/v1\0";

/// An Ed25519 public key, the only key type of ADR-0002's ciphersuite.
pub(crate) const KEY_LEN: usize = 32;

/// Indices in a fingerprint (one byte each, 96 bits).
pub(crate) const FINGERPRINT_LEN: usize = 12;

/// The fingerprint of `key`: [`FINGERPRINT_LEN`] bytes, or `InvalidInput` unless `key` is exactly [`KEY_LEN`] bytes.
pub(crate) fn fingerprint(crypto: &impl OpenMlsCrypto, key: &[u8]) -> Result<Vec<u8>, ErrorCode> {
    if key.len() != KEY_LEN {
        return Err(ErrorCode::InvalidInput);
    }
    let mut data = Vec::with_capacity(LABEL.len() + KEY_LEN);
    data.extend_from_slice(LABEL);
    data.extend_from_slice(key);
    let digest = crypto
        .hash(HashType::Sha2_256, &data)
        .map_err(|_| ErrorCode::Internal)?;
    digest
        .get(..FINGERPRINT_LEN)
        .map(<[u8]>::to_vec)
        .ok_or(ErrorCode::Internal)
}

#[cfg(test)]
mod tests {
    use openmls_rust_crypto::RustCrypto;

    use super::*;

    /// Vectors made outside this crate: `hashlib.sha256(b"gifitup/fingerprint/v1\0" + key).digest()[:12]` in Python,
    /// so a wrong label, separator or length would not agree with itself here.
    const VECTORS: [(&str, [u8; 32], &str); 4] = [
        ("all zero", [0; 32], "202f30067a34946fb860eeb9"),
        ("high bit", [0x80; 32], "fcb427388f0d94f10ff19869"),
        (
            "ascending",
            [
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
                23, 24, 25, 26, 27, 28, 29, 30, 31,
            ],
            "1dc540e603af69e24ae30a6e",
        ),
        ("all ff", [0xff; 32], "67a951281dedf1d856b7b9c7"),
    ];

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    #[test]
    fn known_answers_from_an_independent_implementation() {
        for (name, key, expected) in VECTORS {
            let got = fingerprint(&RustCrypto::default(), &key).unwrap();
            assert_eq!(hex(&got), expected, "{name}");
        }
    }

    #[test]
    fn indices_cover_the_whole_byte_range() {
        // Bytes >= 0x80 must stay valid indices (the Kotlin side reads them unsigned).
        let all: Vec<u8> = VECTORS
            .iter()
            .flat_map(|(_, key, _)| fingerprint(&RustCrypto::default(), key).unwrap())
            .collect();
        assert!(all.iter().any(|b| *b >= 0x80));
        assert!(all.iter().any(|b| *b < 0x80));
    }

    #[test]
    fn only_a_32_byte_key_is_accepted() {
        let crypto = RustCrypto::default();
        for len in [0, 1, 31, 33, 64, 1000] {
            assert_eq!(
                fingerprint(&crypto, &vec![7; len]),
                Err(ErrorCode::InvalidInput),
                "{len}"
            );
        }
    }

    #[test]
    fn deterministic_and_different_keys_differ() {
        let crypto = RustCrypto::default();
        let a = fingerprint(&crypto, &[1; 32]).unwrap();
        assert_eq!(a, fingerprint(&crypto, &[1; 32]).unwrap());
        assert_eq!(a.len(), FINGERPRINT_LEN);
        let mut other = [1; 32];
        other[31] ^= 1;
        assert_ne!(a, fingerprint(&crypto, &other).unwrap());
    }

    /// The emoji table is part of the format. Its text is pinned here; the app's table is compared with this file by a
    /// Kotlin test, so neither can change alone (ADR-0014). Changing the table means a new format and label.
    #[test]
    fn the_emoji_table_file_is_pinned() {
        const TABLE: &str = include_str!("../fingerprint-emojis-v1.tsv");
        let digest = RustCrypto::default()
            .hash(HashType::Sha2_256, TABLE.as_bytes())
            .unwrap();
        assert_eq!(
            hex(&digest),
            "15de3616641790dfc8b8de19fbc8fd048d81a16e15543aa2c0a0b0d97e01160e"
        );
        let entries: Vec<&str> = TABLE.lines().filter(|l| !l.starts_with('#')).collect();
        assert_eq!(entries.len(), 256);
        assert!(
            LABEL.ends_with(b"v1\0"),
            "the label and the table version change together"
        );
    }
}
