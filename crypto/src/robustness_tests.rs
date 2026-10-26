//! Deterministic mutation tests of every parse path that takes untrusted bytes (#11 plan review
//! SEC-16): valid seeds with a few random byte flips, truncations or insertions must give an
//! error, never a panic. Coverage-guided fuzzing comes with #64.

use openmls_rust_crypto::RustCrypto;

use crate::client::Client;
use crate::error::catch;
use crate::snapshot::Snapshot;
use crate::test_support::{client, key, key_package, pair};

const ITERATIONS: usize = 2_500;

/// xorshift64*: a fixed seed makes every failure reproducible. Test-only, not used for crypto.
struct Mutator(u64);

impl Mutator {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn below(&mut self, n: usize) -> usize {
        usize::try_from(self.next() % n as u64).unwrap()
    }

    fn mutate(&mut self, seed: &[u8]) -> Vec<u8> {
        let mut bytes = seed.to_vec();
        for _ in 0..=self.below(4) {
            match self.below(4) {
                0 if !bytes.is_empty() => bytes.truncate(self.below(bytes.len())),
                1 => {
                    let at = self.below(bytes.len() + 1);
                    bytes.insert(at, self.next() as u8);
                }
                _ if !bytes.is_empty() => {
                    let at = self.below(bytes.len());
                    bytes[at] ^= (self.next() as u8) | 1;
                }
                _ => {}
            }
        }
        bytes
    }
}

/// `stream` picks a different, fixed mutation sequence per test (not cryptographic).
fn check(seed: &[u8], stream: u64, mut parse: impl FnMut(&[u8]) -> bool) {
    let mut mutator = Mutator(0x9E37_79B9_7F4A_7C15 ^ stream);
    for _ in 0..ITERATIONS {
        let mutated = mutator.mutate(seed);
        if mutated != seed {
            // Returning (Ok or Err) is the property; a panic fails the test.
            let _ = parse(&mutated);
        }
    }
}

#[test]
fn mutated_key_packages_never_panic() {
    let alice = client("alice");
    let seed = key_package(&client("bob"));
    check(&seed, 1, |bytes| {
        alice.inspect_key_package(bytes, "bob").is_ok()
    });
}

#[test]
fn mutated_welcomes_never_panic() {
    let alice = client("alice");
    let bob = client("bob");
    let group_id = alice.create_group().unwrap();
    let seed = alice
        .add_member(&group_id, &key_package(&bob))
        .unwrap()
        .welcome;
    check(&seed, 2, |bytes| {
        bob.join_from_welcome(bytes, &group_id).is_ok()
    });
}

#[test]
fn mutated_messages_never_panic() {
    let (alice, bob, group_id) = pair();
    let seed = alice.seal(&group_id, b"hello");
    check(&seed, 3, |bytes| bob.process(&group_id, bytes).is_ok());
}

#[test]
fn mutated_snapshots_never_panic() {
    let (_alice, bob, _group_id) = pair();
    let sealed = bob.export_state().unwrap();
    // Outer format: almost every mutation fails authentication.
    check(&sealed, 4, |bytes| Client::restore(bytes, key()).is_ok());
    // Inner format, re-sealed with the right key so mutations reach the decoder and the loader.
    // Only a key holder or our own bug can produce such data (it is authenticated). The decoder
    // never panics, but `openmls_memory_storage` unwraps malformed stored values, so loading can:
    // the FFI constructors run `restore` inside `catch` (-> `Poisoned`), which is what this checks.
    // On the web the same panic is a trap; #16 breaks the reload loop (ADR-0007).
    let inner = Snapshot::open(&sealed, &key(), &RustCrypto::default())
        .unwrap()
        .encode_inner()
        .unwrap();
    check(&inner, 5, |bytes| {
        Snapshot::decode_inner(bytes).is_ok_and(|snapshot| {
            let resealed = snapshot.seal(&key(), &RustCrypto::default()).unwrap();
            catch(|| Client::restore(&resealed, key())).is_ok()
        })
    });
}
