//! Entry points for the cargo-fuzz targets (`crypto/fuzz`, #64). Compiled only under `--cfg fuzzing`, which only
//! cargo-fuzz sets, so no shipped build contains this module (`scripts/check-repo-hygiene.sh` guards the cfg).
//!
//! Everything here works with fixed **test** keys and throwaway identities; nothing takes real key material. A panic
//! in any function is a finding (the fuzz binary aborts on it), except the one documented case in
//! [`inner_snapshot`].

use std::sync::OnceLock;

use openmls::prelude::tls_codec::Serialize;
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::RustCrypto;
use zeroize::Zeroizing;

use crate::client::{CIPHERSUITE, Client};
use crate::error::catch;
use crate::group::Incoming;
use crate::history::HistoryCipher;
use crate::list::{decode_byte_lists, decode_u32_list};
use crate::snapshot::{KEY_LEN, Snapshot, SnapshotKey};

const TEST_ORIGIN: &str = "https://gifitup.test";
const RECORD_NAME: [u8; 32] = [3; 32];

fn key() -> SnapshotKey {
    Zeroizing::new([7; KEY_LEN])
}

fn client(name: &str) -> Client {
    Client::new(name.into(), key()).expect("test client")
}

fn history() -> HistoryCipher {
    HistoryCipher::new(vec![9; KEY_LEN], TEST_ORIGIN).expect("test history cipher")
}

/// A reader over the fuzzer's bytes that never fails: past the end it yields zeros.
struct Bytes<'a>(&'a [u8]);

impl Bytes<'_> {
    fn u8(&mut self) -> u8 {
        let (first, rest) = self.0.split_first().map_or((0, self.0), |(b, r)| (*b, r));
        self.0 = rest;
        first
    }

    fn u16(&mut self) -> u16 {
        u16::from_be_bytes([self.u8(), self.u8()])
    }

    fn u64(&mut self) -> u64 {
        (0..8).fold(0, |acc, _| acc << 8 | u64::from(self.u8()))
    }

    /// Up to `max` bytes, the length taken from the input.
    fn blob(&mut self, max: usize) -> Vec<u8> {
        let len = usize::from(self.u8()) % (max + 1);
        let (head, rest) = self.0.split_at(len.min(self.0.len()));
        self.0 = rest;
        head.to_vec()
    }

    fn list<T>(&mut self, max: usize, mut item: impl FnMut(&mut Self) -> Option<T>) -> Vec<T> {
        let count = usize::from(self.u8()) % (max + 1);
        (0..count).filter_map(|_| item(self)).collect()
    }
}

/// Applies an edit script to `seed`: every 3 input bytes are (operation, position, value). The seed is built in this
/// process, so valid objects reach the parsers however the keys change between runs.
fn edit(seed: &[u8], script: &[u8]) -> Vec<u8> {
    let mut out = seed.to_vec();
    for step in script.as_chunks::<3>().0.iter().take(16) {
        let (op, at, val) = (step[0] % 4, usize::from(step[1]), step[2]);
        let at = at * 7 % (out.len() + 1);
        match op {
            0 => out.truncate(at),
            1 => out.insert(at, val),
            2 if at < out.len() => out[at] ^= val | 1,
            3 if at < out.len() => out[at] = val,
            _ => {}
        }
    }
    out
}

/// A group of alice and bob (bob joined), plus bob's sealed state before the interesting message and alice's
/// valid messages for him. Built once per process.
struct Fixture {
    group_id: Vec<u8>,
    bob_state: Vec<u8>,
    message: Vec<u8>,
    commit: Vec<u8>,
    carol_state: Vec<u8>,
    welcome: Vec<u8>,
}

fn fixture() -> &'static Fixture {
    static FIXTURE: OnceLock<Fixture> = OnceLock::new();
    FIXTURE.get_or_init(|| {
        let (alice, bob, carol) = (client("alice"), client("bob"), client("carol"));
        let group_id = alice.create_group().unwrap();
        let kp = |c: &Client| c.create_key_packages(1).unwrap().pop().unwrap().key_package;
        let added = alice.prepare_add(&group_id, &[kp(&bob)]).unwrap();
        alice.merge_pending(&group_id).unwrap();
        let info = bob
            .inspect_welcome(&added.welcome.unwrap(), &group_id)
            .unwrap();
        bob.join_welcome(info.token).unwrap();
        let bob_state = bob.export_state().unwrap();
        let message = alice.encrypt(&group_id, b"hello").unwrap().message;
        let add_carol = alice.prepare_add(&group_id, &[kp(&carol)]).unwrap();
        // `commit` is for bob's epoch; `message` was sealed before it, in the same epoch.
        let commit = add_carol.commit.clone();
        let welcome = add_carol.welcome.unwrap();
        let carol_state = carol.export_state().unwrap();
        Fixture {
            group_id,
            bob_state,
            message,
            commit,
            carol_state,
            welcome,
        }
    })
}

fn restored(state: &[u8]) -> Client {
    Client::restore(state, key()).expect("fixture state")
}

/// Raw bytes as someone else's KeyPackage.
pub fn key_package_bytes(data: &[u8]) {
    let _ = client("alice").inspect_key_package(data, "bob");
}

/// Mutated valid Welcome for carol (an edit script over the fixture's Welcome).
pub fn welcome_edit(script: &[u8]) {
    let fx = fixture();
    let carol = restored(&fx.carol_state);
    if let Ok(info) = carol.inspect_welcome(&edit(&fx.welcome, script), &fx.group_id) {
        let _ = carol.join_welcome(info.token);
    }
}

/// Mutated valid application message and commit for bob.
pub fn message_edit(script: &[u8]) {
    let fx = fixture();
    for seed in [&fx.message, &fx.commit] {
        let bob = restored(&fx.bob_state);
        if let Ok(Incoming::Commit { token, .. }) =
            bob.stage_incoming(&fx.group_id, &edit(seed, script))
        {
            let _ = bob.merge_staged(token);
        }
    }
}

/// Raw bytes as a group-scoped incoming message (group id fixed, so the group lookup succeeds).
pub fn message_bytes(data: &[u8]) {
    let fx = fixture();
    let _ = restored(&fx.bob_state).stage_incoming(&fx.group_id, data);
}

/// Raw bytes as an incoming Welcome.
pub fn welcome_bytes(data: &[u8]) {
    let fx = fixture();
    let _ = restored(&fx.carol_state).inspect_welcome(data, &fx.group_id);
}

/// Raw bytes as a sealed snapshot (outer format).
pub fn restore_outer(data: &[u8]) {
    let _ = Client::restore(data, key());
}

/// Raw bytes as the *decrypted* inner snapshot. The decoder must never panic. A snapshot it accepts is re-sealed
/// with the test key and restored inside `catch`: `openmls_memory_storage` unwraps malformed stored values, so a
/// panic from that crate is the documented, known case (only a key holder or our own bug can produce such a
/// snapshot; Android/JVM: `Poisoned`, web: trap, threat model *Crafted MLS input*). Any panic from elsewhere
/// aborts the fuzzer.
pub fn inner_snapshot(data: &[u8], v2: bool) {
    let Ok(snapshot) = Snapshot::decode_inner(data, v2) else {
        return;
    };
    let Ok(resealed) = snapshot.seal(&key(), &RustCrypto::default()) else {
        return;
    };
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(|info| {
        let known = info
            .location()
            .is_some_and(|l| l.file().contains("openmls_memory_storage"));
        if !known {
            // The fuzzer's own hook (restored below on the normal path) never runs here, so abort the way it does.
            std::process::abort();
        }
    }));
    let _ = catch(|| Client::restore(&resealed, key()));
    std::panic::set_hook(previous);
}

/// Raw bytes as a sealed history record (framing and authentication).
pub fn history_open(data: &[u8]) {
    let _ = history().open(&RECORD_NAME, data);
}

/// Raw bytes as the padded inner layout of a record that authenticates, so `open` reaches the unpadding.
pub fn history_open_padded(data: &[u8]) {
    let cipher = history();
    if let Ok(sealed) = cipher.seal_raw_padded(&RECORD_NAME, data) {
        let _ = cipher.open(&RECORD_NAME, &sealed);
    }
}

/// The web binding's list decoders.
pub fn lists(data: &[u8]) {
    let _ = decode_byte_lists(data);
    let _ = decode_u32_list(data);
}

/// A KeyPackage with attacker-chosen capabilities, extensions, lifetime and credential identity, **correctly
/// signed** with `owner`'s throwaway key (what a malicious member can upload, ADR-0012). `data` picks the fields.
fn hostile_key_package(owner: &Client, data: &mut Bytes) -> Option<Vec<u8>> {
    let capabilities = Capabilities::new(
        Some(&data.list(3, |d| Some(ProtocolVersion::from(d.u16())))),
        Some(&data.list(4, |d| Ciphersuite::try_from(d.u16()).ok())),
        Some(&data.list(6, |d| Some(ExtensionType::from(d.u16())))),
        Some(&data.list(6, |d| Some(ProposalType::from(d.u16())))),
        Some(&data.list(4, |d| Some(CredentialType::from(d.u16())))),
    );
    let extensions = |d: &mut Bytes| -> Vec<Extension> {
        d.list(3, |d| {
            let kind = d.u16();
            let bytes = d.blob(48);
            Some(match kind % 3 {
                0 => Extension::ApplicationId(ApplicationIdExtension::new(&bytes)),
                _ => Extension::Unknown(kind, UnknownExtension(bytes)),
            })
        })
    };
    let kp_extensions = Extensions::from_vec(extensions(data)).ok()?;
    let leaf_extensions = Extensions::from_vec(extensions(data)).ok()?;
    let lifetime = Lifetime::init(data.u64(), data.u64());
    let identity = if data.u8().is_multiple_of(4) {
        data.blob(40)
    } else {
        owner.username.as_bytes().to_vec()
    };
    let signer: &SignatureKeyPair = &owner.signer;
    let credential = CredentialWithKey {
        credential: BasicCredential::new(identity).into(),
        signature_key: signer.to_public_vec().into(),
    };
    let mut builder = KeyPackage::builder()
        .leaf_node_capabilities(capabilities)
        .key_package_extensions(kp_extensions)
        .leaf_node_extensions(leaf_extensions)
        .key_package_lifetime(lifetime);
    if data.u8().is_multiple_of(8) {
        builder = builder.mark_as_last_resort();
    }
    let bundle = builder
        .build(CIPHERSUITE, &owner.provider, signer, credential)
        .ok()?;
    MlsMessageOut::from(bundle.key_package().clone())
        .tls_serialize_detached()
        .ok()
}

/// A hostile signed KeyPackage goes through the whole path of a new member: the adder inspects it and commits an
/// Add, an existing member stages that commit, the new member inspects and joins from the Welcome.
pub fn hostile_member(data: &[u8]) {
    let mut bytes = Bytes(data);
    let (alice, bob, carol) = (client("alice"), client("bob"), client("carol"));
    let group_id = alice.create_group().unwrap();
    let bob_kp = bob
        .create_key_packages(1)
        .unwrap()
        .pop()
        .unwrap()
        .key_package;
    let added = alice.prepare_add(&group_id, &[bob_kp]).unwrap();
    alice.merge_pending(&group_id).unwrap();
    let info = bob
        .inspect_welcome(&added.welcome.unwrap(), &group_id)
        .unwrap();
    bob.join_welcome(info.token).unwrap();

    let Some(hostile) = hostile_key_package(&carol, &mut bytes) else {
        return;
    };
    if alice.inspect_key_package(&hostile, "carol").is_err() {
        return;
    }
    let Ok(prepared) = alice.prepare_add(&group_id, &[hostile]) else {
        return;
    };
    if let Ok(Incoming::Commit { token, .. }) = bob.stage_incoming(&group_id, &prepared.commit) {
        let _ = bob.merge_staged(token);
    }
    if let Some(welcome) = prepared.welcome
        && let Ok(info) = carol.inspect_welcome(&welcome, &group_id)
    {
        let _ = carol.join_welcome(info.token);
    }
}

/// The hostile KeyPackage alone, through `inspect_key_package`.
pub fn hostile_key_package_inspect(data: &[u8]) {
    let (alice, carol) = (client("alice"), client("carol"));
    if let Some(kp) = hostile_key_package(&carol, &mut Bytes(data)) {
        let _ = alice.inspect_key_package(&kp, "carol");
    }
}

/// Valid objects for the raw-byte targets, as `(target, bytes)`, for the seed corpora (`gen_seeds`). They hold test
/// keys only.
pub fn seeds() -> Vec<(&'static str, Vec<u8>)> {
    let fx = fixture();
    let bob = restored(&fx.bob_state);
    let sealed = bob.export_state().unwrap();
    let inner = Snapshot::open(&sealed, &key(), &RustCrypto::default())
        .unwrap()
        .encode_inner()
        .unwrap();
    let mut inner_v3 = vec![1];
    inner_v3.extend_from_slice(&inner);
    let cipher = history();
    let record = cipher.seal(&RECORD_NAME, b"hello").unwrap();
    vec![
        (
            "key_package_bytes",
            client("bob")
                .create_key_packages(1)
                .unwrap()
                .pop()
                .unwrap()
                .key_package,
        ),
        ("welcome_bytes", fx.welcome.clone()),
        ("message_bytes", fx.message.clone()),
        ("message_bytes", fx.commit.clone()),
        ("restore_outer", sealed),
        ("inner_snapshot", inner_v3),
        ("history_open", record),
        ("lists", vec![0, 0, 0, 2, 7, 8, 0, 0, 0, 0]),
    ]
}
