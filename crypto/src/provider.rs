//! OpenMLS provider: RustCrypto for crypto and randomness, in-memory key-value storage that is
//! exported as one snapshot (option A in `docs/spikes/openmls-android.md`, ADR-0007).

use openmls_memory_storage::MemoryStorage;
use openmls_rust_crypto::RustCrypto;
use openmls_traits::OpenMlsProvider;

#[derive(Default)]
pub(crate) struct Provider {
    pub(crate) crypto: RustCrypto,
    pub(crate) storage: MemoryStorage,
}

impl OpenMlsProvider for Provider {
    type CryptoProvider = RustCrypto;
    type RandProvider = RustCrypto;
    type StorageProvider = MemoryStorage;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        &self.crypto
    }

    fn rand(&self) -> &Self::RandProvider {
        &self.crypto
    }
}
