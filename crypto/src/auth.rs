//! Device authentication towards the server (#12, ADR-0009): the device signs the server's challenge with its MLS
//! signature key, under its own signature label, so the signature can never be mistaken for an MLS signature.
//!
//! Signed bytes (TLS presentation language, RFC 9420 §2.1 / §5.1.2 `SignWithLabel`; `<V>` is the MLS variable-length
//! vector with a minimal RFC 9000 varint length):
//!
//! ```text
//! struct {
//!     opaque label<V>   = "MLS 1.0 GifItUp auth v1";
//!     opaque content<V> = AuthContent;
//! } SignContent;
//!
//! struct {
//!     uint8  purpose;       // 1 = register, 2 = login (closed set)
//!     opaque challenge<V>;  // the server's challenge, opaque to the client
//!     opaque origin<V>;     // the client's own configured server origin, canonical form
//!     opaque subject<V>;    // register: this client's username; login: the server-assigned device id
//! } AuthContent;
//! ```
//!
//! Every field is built here from typed, validated parameters: there is no way to sign arbitrary bytes through the
//! FFI. The server rebuilds the same bytes from what it knows and verifies (PR D of #12).

use openmls::prelude::tls_codec::{Serialize, VLBytes};
use openmls_traits::signatures::Signer;

use crate::client::Client;
use crate::error::ErrorCode;
use crate::identity::is_valid_username;

/// `"MLS 1.0 "` plus our own label: no MLS label is the same, so no auth signature is a valid MLS signature and no
/// MLS signature is a valid auth signature (RFC 9420 §5.1.2).
pub const AUTH_SIGNATURE_LABEL: &[u8] = b"MLS 1.0 GifItUp auth v1";

/// Bounds on the server's challenge. It is opaque here; the server defines its layout (ADR-0009).
pub const MIN_CHALLENGE_LEN: usize = 16;
pub const MAX_CHALLENGE_LEN: usize = 64;
/// Longest accepted origin, in bytes.
pub const MAX_ORIGIN_LEN: usize = 256;

/// What a signature authorizes. A closed set: an unknown purpose can't be signed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Enum))]
pub enum AuthPurpose {
    /// Registers this device's key for this client's username (the subject).
    Register,
    /// Logs in as the device with the given id (the subject).
    Login,
}

impl AuthPurpose {
    /// The purpose byte in the signed content; also how the web bindings pass it (a closed set, no default).
    pub fn wire(self) -> u8 {
        match self {
            AuthPurpose::Register => 1,
            AuthPurpose::Login => 2,
        }
    }

    pub fn from_wire(byte: u8) -> Result<Self, ErrorCode> {
        match byte {
            1 => Ok(AuthPurpose::Register),
            2 => Ok(AuthPurpose::Login),
            _ => Err(ErrorCode::InvalidInput),
        }
    }

    /// How the web binding passes the purpose: exactly `"register"` or `"login"`. A string, because wasm-bindgen
    /// truncates JS numbers to the integer parameter's width (257 would become 1; #70 CR-1, SEC-26).
    pub fn from_name(name: &str) -> Result<Self, ErrorCode> {
        match name {
            "register" => Ok(AuthPurpose::Register),
            "login" => Ok(AuthPurpose::Login),
            _ => Err(ErrorCode::InvalidInput),
        }
    }
}

impl Client {
    /// Signs a server challenge with this device's signature key (ADR-0009).
    ///
    /// - `challenge`: the server's challenge, [`MIN_CHALLENGE_LEN`]..=[`MAX_CHALLENGE_LEN`] bytes.
    /// - `origin`: the server origin the app is configured with, never one the server supplies. Canonical form only:
    ///   `https://` + lowercase host + optional `:port` (not 443), no path, no trailing slash ([`is_canonical_origin`]).
    /// - `subject`: for [`AuthPurpose::Register`], exactly this client's own username; for [`AuthPurpose::Login`], a
    ///   lowercase canonical UUID ([`is_canonical_uuid`]).
    ///
    /// Returns the 64-byte Ed25519 signature. Any rule violation is [`ErrorCode::InvalidInput`].
    pub fn sign_auth(
        &self,
        purpose: AuthPurpose,
        challenge: &[u8],
        origin: &str,
        subject: &str,
    ) -> Result<Vec<u8>, ErrorCode> {
        let subject_ok = match purpose {
            AuthPurpose::Register => is_valid_username(subject) && subject == self.username,
            AuthPurpose::Login => is_canonical_uuid(subject),
        };
        if !(MIN_CHALLENGE_LEN..=MAX_CHALLENGE_LEN).contains(&challenge.len())
            || !is_canonical_origin(origin)
            || !subject_ok
        {
            return Err(ErrorCode::InvalidInput);
        }
        let bytes = auth_sign_content(purpose, challenge, origin, subject)?;
        self.signer.sign(&bytes).map_err(|_| ErrorCode::Internal)
    }
}

/// The exact bytes that are signed (see the module documentation). Public for the test vectors and so the
/// encoding is documented in one place; inputs are not validated here.
pub fn auth_sign_content(
    purpose: AuthPurpose,
    challenge: &[u8],
    origin: &str,
    subject: &str,
) -> Result<Vec<u8>, ErrorCode> {
    let mut content = vec![purpose.wire()];
    for field in [challenge, origin.as_bytes(), subject.as_bytes()] {
        content.extend(vl_bytes(field)?);
    }
    let mut sign_content = vl_bytes(AUTH_SIGNATURE_LABEL)?;
    sign_content.extend(vl_bytes(&content)?);
    Ok(sign_content)
}

fn vl_bytes(bytes: &[u8]) -> Result<Vec<u8>, ErrorCode> {
    VLBytes::new(bytes.to_vec())
        .tls_serialize_detached()
        .map_err(|_| ErrorCode::Internal)
}

/// `https://` + host + optional port, nothing else. The host is lowercase ASCII letters, digits, `-` and `.`, with no
/// empty labels and no leading or trailing `-` or `.`. The port is 1..=65535 without leading zeros and never 443 (the
/// default is written by omitting it). At most [`MAX_ORIGIN_LEN`] bytes. One origin has exactly one accepted spelling,
/// so client and server always sign and verify the same bytes.
pub fn is_canonical_origin(origin: &str) -> bool {
    if origin.len() > MAX_ORIGIN_LEN {
        return false;
    }
    let Some(authority) = origin.strip_prefix("https://") else {
        return false;
    };
    let (host, port) = match authority.split_once(':') {
        Some((host, port)) => (host, Some(port)),
        None => (authority, None),
    };
    let host_ok = !host.is_empty()
        && host.split('.').all(|label| {
            !label.is_empty()
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        });
    let port_ok = match port {
        None => true,
        Some(p) => {
            !p.is_empty()
                && !p.starts_with('0')
                && p.bytes().all(|b| b.is_ascii_digit())
                && p.parse::<u16>().is_ok_and(|n| n != 443)
        }
    };
    host_ok && port_ok
}

/// A lowercase, hyphenated UUID (`8-4-4-4-12` hex digits), the form the server assigns device ids in.
pub fn is_canonical_uuid(id: &str) -> bool {
    let bytes = id.as_bytes();
    bytes.len() == 36
        && bytes.iter().enumerate().all(|(i, &b)| match i {
            8 | 13 | 18 | 23 => b == b'-',
            _ => b.is_ascii_digit() || (b'a'..=b'f').contains(&b),
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::client;

    const CHALLENGE: [u8; 32] = [0x42; 32];
    const ORIGIN: &str = "https://gifitup.example.org";
    const DEVICE: &str = "123e4567-e89b-12d3-a456-426614174000";

    #[test]
    fn register_signature_verifies_and_is_64_bytes() {
        let alice = client("alice");
        let signature = alice
            .sign_auth(AuthPurpose::Register, &CHALLENGE, ORIGIN, "alice")
            .unwrap();
        assert_eq!(signature.len(), 64);
        let bytes = auth_sign_content(AuthPurpose::Register, &CHALLENGE, ORIGIN, "alice").unwrap();
        verify(
            &alice.public_identity().signature_public_key,
            &bytes,
            &signature,
        );
    }

    #[test]
    fn signature_depends_on_every_field() {
        let alice = client("alice");
        let base = alice
            .sign_auth(AuthPurpose::Login, &CHALLENGE, ORIGIN, DEVICE)
            .unwrap();
        let mut other_challenge = CHALLENGE;
        other_challenge[31] ^= 1;
        for other in [
            alice.sign_auth(AuthPurpose::Login, &other_challenge, ORIGIN, DEVICE),
            alice.sign_auth(
                AuthPurpose::Login,
                &CHALLENGE,
                "https://gifitup.example.com",
                DEVICE,
            ),
            alice.sign_auth(
                AuthPurpose::Login,
                &CHALLENGE,
                ORIGIN,
                "123e4567-e89b-12d3-a456-426614174001",
            ),
            alice.sign_auth(AuthPurpose::Register, &CHALLENGE, ORIGIN, "alice"),
        ] {
            assert_ne!(other.unwrap(), base);
        }
    }

    #[test]
    fn purpose_wire_values_are_closed() {
        for purpose in [AuthPurpose::Register, AuthPurpose::Login] {
            assert_eq!(AuthPurpose::from_wire(purpose.wire()), Ok(purpose));
        }
        for byte in [0, 3, 255] {
            assert_eq!(AuthPurpose::from_wire(byte), Err(ErrorCode::InvalidInput));
        }
        assert_eq!(
            AuthPurpose::from_name("register"),
            Ok(AuthPurpose::Register)
        );
        assert_eq!(AuthPurpose::from_name("login"), Ok(AuthPurpose::Login));
        for name in ["", "Register", "LOGIN", "login ", "1", "registerx"] {
            assert_eq!(
                AuthPurpose::from_name(name),
                Err(ErrorCode::InvalidInput),
                "{name}"
            );
        }
    }

    #[test]
    fn register_subject_must_be_this_clients_username() {
        let alice = client("alice");
        for subject in ["bob", "Alice", "alice ", ""] {
            assert_eq!(
                alice.sign_auth(AuthPurpose::Register, &CHALLENGE, ORIGIN, subject),
                Err(ErrorCode::InvalidInput),
                "{subject}"
            );
        }
    }

    #[test]
    fn login_subject_must_be_a_canonical_uuid() {
        let alice = client("alice");
        for subject in [
            "123E4567-E89B-12D3-A456-426614174000",
            "123e4567e89b12d3a456426614174000",
            "{123e4567-e89b-12d3-a456-426614174000}",
            "123e4567-e89b-12d3-a456-42661417400",
            "alice",
        ] {
            assert_eq!(
                alice.sign_auth(AuthPurpose::Login, &CHALLENGE, ORIGIN, subject),
                Err(ErrorCode::InvalidInput),
                "{subject}"
            );
        }
    }

    #[test]
    fn challenge_length_is_bounded() {
        let alice = client("alice");
        for len in [0, MIN_CHALLENGE_LEN - 1, MAX_CHALLENGE_LEN + 1, 4096] {
            assert_eq!(
                alice.sign_auth(AuthPurpose::Login, &vec![1; len], ORIGIN, DEVICE),
                Err(ErrorCode::InvalidInput),
                "{len}"
            );
        }
        for len in [MIN_CHALLENGE_LEN, MAX_CHALLENGE_LEN] {
            assert!(
                alice
                    .sign_auth(AuthPurpose::Login, &vec![1; len], ORIGIN, DEVICE)
                    .is_ok()
            );
        }
    }

    #[test]
    fn only_canonical_origins_are_accepted() {
        // The same cases as the server's CanonicalOriginTest (#71 SEC-33): one shared file, so the rules can't drift.
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../server/src/test/resources/origin-rule-cases.txt");
        let cases = std::fs::read_to_string(path).expect("shared origin cases");
        let mut checked = 0;
        for line in cases
            .lines()
            .filter(|l| !l.is_empty() && !l.starts_with('#'))
        {
            let (sign, hex) = line.split_at(1);
            let hex = hex.trim();
            let bytes: Vec<u8> = (0..hex.len())
                .step_by(2)
                .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
                .collect();
            let origin = String::from_utf8(bytes).unwrap();
            assert_eq!(is_canonical_origin(&origin), sign == "+", "{origin:?}");
            checked += 1;
        }
        assert!(checked >= 20, "the shared file lost cases");
    }

    #[test]
    fn auth_label_is_not_an_mls_label() {
        // RFC 9420 labels used by OpenMLS 0.9 for SignWithLabel; ours must differ from every one of them.
        for mls_label in [
            "FramedContentTBS",
            "LeafNodeTBS",
            "KeyPackageTBS",
            "GroupInfoTBS",
        ] {
            assert_ne!(
                AUTH_SIGNATURE_LABEL,
                format!("MLS 1.0 {mls_label}").as_bytes()
            );
        }
        assert!(AUTH_SIGNATURE_LABEL.starts_with(b"MLS 1.0 "));
    }

    /// Cross-language vectors (ADR-0009): the server's verifier (PR D of #12) checks the same file. The crate compares
    /// its own output with the committed file, so encoding and file can't drift apart. Regenerate after a deliberate
    /// format change with `GIFITUP_WRITE_AUTH_VECTORS=1 cargo test auth::tests::vectors_match_the_committed_file`.
    #[test]
    fn vectors_match_the_committed_file() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../server/src/test/resources/auth-signature-vectors.json");
        let generated = vectors_json();
        if std::env::var_os("GIFITUP_WRITE_AUTH_VECTORS").is_some() {
            std::fs::write(&path, &generated).unwrap();
        }
        let committed = std::fs::read_to_string(&path).expect("vector file exists");
        // A Windows checkout may have CRLF line endings.
        assert_eq!(committed.replace("\r\n", "\n"), generated);
    }

    /// RFC 8032 §7.1, TEST 1: a published key, so the signatures are reproducible anywhere (Ed25519 is
    /// deterministic). The third case's 64-byte challenge needs a 2-byte varint length.
    fn vectors_json() -> String {
        use openmls_basic_credential::SignatureKeyPair;
        use openmls_traits::types::SignatureScheme;
        let secret = hex_decode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        let public = hex_decode("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        let signer = SignatureKeyPair::from_raw(SignatureScheme::ED25519, secret, public.clone());
        let origin = "https://gifitup.example.org";
        let cases: [(AuthPurpose, Vec<u8>, &str, &str); 3] = [
            (AuthPurpose::Register, (0..32).collect(), origin, "alice"),
            (AuthPurpose::Login, vec![0xab; 16], origin, DEVICE),
            (
                AuthPurpose::Login,
                (0..64).rev().collect(),
                "https://localhost:8443",
                DEVICE,
            ),
        ];
        let entries: Vec<String> = cases
            .iter()
            .map(|(purpose, challenge, origin, subject)| {
                let content = auth_sign_content(*purpose, challenge, origin, subject).unwrap();
                vector_json(
                    *purpose,
                    challenge,
                    origin,
                    subject,
                    &content,
                    &signer.sign(&content).unwrap(),
                )
            })
            .collect();
        let label = String::from_utf8(AUTH_SIGNATURE_LABEL.to_vec()).unwrap();
        [
            "{".to_string(),
            "  \"comment\": \"Generated by crypto/src/auth.rs (vectors_match_the_committed_file); ADR-0009. Do not edit by hand.\",".to_string(),
            format!("  \"label\": \"{label}\","),
            format!("  \"publicKey\": \"{}\",", hex(&public)),
            "  \"vectors\": [".to_string(),
            entries.join(",\n"),
            "  ]".to_string(),
            "}".to_string(),
            String::new(),
        ]
        .join("\n")
    }

    fn vector_json(
        purpose: AuthPurpose,
        challenge: &[u8],
        origin: &str,
        subject: &str,
        content: &[u8],
        signature: &[u8],
    ) -> String {
        let name = match purpose {
            AuthPurpose::Register => "register",
            AuthPurpose::Login => "login",
        };
        [
            "    {".to_string(),
            format!("      \"purpose\": \"{name}\","),
            format!("      \"challenge\": \"{}\",", hex(challenge)),
            format!("      \"origin\": \"{origin}\","),
            format!("      \"subject\": \"{subject}\","),
            format!("      \"signContent\": \"{}\",", hex(content)),
            format!("      \"signature\": \"{}\"", hex(signature)),
            "    }".to_string(),
        ]
        .join("\n")
    }

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    fn hex_decode(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    fn verify(public_key: &[u8], message: &[u8], signature: &[u8]) {
        use openmls_rust_crypto::RustCrypto;
        use openmls_traits::crypto::OpenMlsCrypto;
        use openmls_traits::types::SignatureScheme;
        RustCrypto::default()
            .verify_signature(SignatureScheme::ED25519, message, public_key, signature)
            .expect("signature verifies");
    }
}
