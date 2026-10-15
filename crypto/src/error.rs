//! Closed error codes and panic containment: the only error information that crosses the FFI
//! (ADR-0007). OpenMLS errors are mapped to a code and dropped, so no key material, plaintext or
//! internal state can reach Kotlin, JS or logs (CLAUDE.md, security rule 4).

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};

/// Every error the crate reports. Fieldless on purpose: `Display` is the code name and nothing else.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[cfg_attr(not(target_arch = "wasm32"), derive(uniffi::Error))]
pub enum ErrorCode {
    #[error("InvalidInput")]
    InvalidInput,
    #[error("InvalidUsername")]
    InvalidUsername,
    #[error("InvalidKeyPackage")]
    InvalidKeyPackage,
    #[error("UnknownGroup")]
    UnknownGroup,
    #[error("Mls")]
    Mls,
    #[error("SnapshotInvalid")]
    SnapshotInvalid,
    #[error("SnapshotVersion")]
    SnapshotVersion,
    #[error("SnapshotAuth")]
    SnapshotAuth,
    /// A panic or a poisoned lock: the client is unusable and must be dropped (ADR-0007).
    #[error("Poisoned")]
    Poisoned,
    #[error("Internal")]
    Internal,
}

/// Maps an OpenMLS failure to [`ErrorCode::Mls`], dropping its details.
pub(crate) fn mls<E>(_: E) -> ErrorCode {
    ErrorCode::Mls
}

/// Maps a failure to parse untrusted input to [`ErrorCode::InvalidInput`], dropping its details.
pub(crate) fn input<E>(_: E) -> ErrorCode {
    ErrorCode::InvalidInput
}

/// Replaces Rust's default panic hook, which prints the panic message (possibly containing input
/// or internal state) to stderr, with a silent one. Called by the FFI constructors.
pub(crate) fn install_silent_panic_hook() {
    static HOOK: OnceLock<()> = OnceLock::new();
    HOOK.get_or_init(|| std::panic::set_hook(Box::new(|_| {})));
}

/// Runs `f` and turns a panic into [`ErrorCode::Poisoned`]. The panic payload is dropped, so it
/// never crosses the FFI (uniffi would otherwise forward it to Kotlin as an exception message).
pub(crate) fn catch<T>(f: impl FnOnce() -> Result<T, ErrorCode>) -> Result<T, ErrorCode> {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(Err(ErrorCode::Poisoned))
}

/// Marks a client dead after its first panic or poisoned lock. From then on every call, including
/// state export, fails with [`ErrorCode::Poisoned`]: half-updated state must never be persisted.
#[derive(Default)]
pub(crate) struct PanicGuard {
    dead: AtomicBool,
}

impl PanicGuard {
    pub(crate) fn run<T>(&self, f: impl FnOnce() -> Result<T, ErrorCode>) -> Result<T, ErrorCode> {
        if self.dead.load(Ordering::SeqCst) {
            return Err(ErrorCode::Poisoned);
        }
        let result = catch(f);
        if result.as_ref().err() == Some(&ErrorCode::Poisoned) {
            self.dead.store(true, Ordering::SeqCst);
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_is_the_code_name_only() {
        assert_eq!(ErrorCode::SnapshotAuth.to_string(), "SnapshotAuth");
        assert_eq!(format!("{:?}", ErrorCode::Mls), "Mls");
        assert_eq!(mls("secret detail").to_string(), "Mls");
        assert_eq!(input(vec![0xde, 0xad]).to_string(), "InvalidInput");
    }

    #[test]
    fn panic_becomes_poisoned_and_payload_is_dropped() {
        let result: Result<(), ErrorCode> = catch(|| panic!("secret payload"));
        assert_eq!(result, Err(ErrorCode::Poisoned));
    }

    #[test]
    fn guard_stays_dead_after_a_panic() {
        let guard = PanicGuard::default();
        assert_eq!(guard.run(|| Ok(1)), Ok(1));
        assert_eq!(guard.run::<()>(|| panic!("boom")), Err(ErrorCode::Poisoned));
        // Every later call fails, even one that would succeed (e.g. exporting state).
        assert_eq!(guard.run(|| Ok(2)), Err(ErrorCode::Poisoned));
    }

    #[test]
    fn guard_dies_on_poisoned_lock_error() {
        let guard = PanicGuard::default();
        assert_eq!(
            guard.run::<()>(|| Err(ErrorCode::Poisoned)),
            Err(ErrorCode::Poisoned)
        );
        assert_eq!(guard.run(|| Ok(())), Err(ErrorCode::Poisoned));
    }
}
