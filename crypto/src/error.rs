//! Closed error codes and panic containment: the only error information that crosses the FFI
//! (ADR-0007). OpenMLS errors are mapped to a code and dropped, so no key material, plaintext or
//! internal state can reach Kotlin, JS or logs (CLAUDE.md, security rule 4).

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};

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
///
/// Calls on one client are serialized, so no call on another thread can slip in between a panic
/// and the moment the client is marked dead (e.g. an export of half-updated state).
#[derive(Default)]
pub(crate) struct PanicGuard {
    call: Mutex<()>,
    dead: AtomicBool,
}

impl PanicGuard {
    pub(crate) fn run<T>(&self, f: impl FnOnce() -> Result<T, ErrorCode>) -> Result<T, ErrorCode> {
        module_call(|| {
            // `catch` runs inside the lock, so this mutex is never poisoned by a panic in `f`.
            let _call = self.call.lock().map_err(|_| ErrorCode::Poisoned)?;
            if self.dead.load(Ordering::SeqCst) {
                return Err(ErrorCode::Poisoned);
            }
            let result = catch(f);
            if result.as_ref().err() == Some(&ErrorCode::Poisoned) {
                self.dead.store(true, Ordering::SeqCst);
            }
            result
        })
        .inspect_err(|code| {
            if *code == ErrorCode::Poisoned {
                self.dead.store(true, Ordering::SeqCst);
            }
        })
    }
}

/// Detects a wasm trap after the fact. wasm32 can't unwind, so a panic there traps out of the
/// call without running any Rust code, and [`catch`] never sees it. A call that finds the previous
/// one still marked as running knows that call trapped: the whole module instance is dead and
/// every later call, constructors and state export included, fails with [`ErrorCode::Poisoned`].
/// Single-threaded by construction (wasm32 without threads; no callbacks into Rust).
#[cfg(any(target_arch = "wasm32", test))]
pub(crate) struct TrapDetector {
    in_call: AtomicBool,
    dead: AtomicBool,
}

#[cfg(any(target_arch = "wasm32", test))]
impl TrapDetector {
    pub(crate) const fn new() -> Self {
        Self {
            in_call: AtomicBool::new(false),
            dead: AtomicBool::new(false),
        }
    }

    fn enter(&self) -> Result<(), ErrorCode> {
        if self.dead.load(Ordering::SeqCst) || self.in_call.swap(true, Ordering::SeqCst) {
            self.dead.store(true, Ordering::SeqCst);
            return Err(ErrorCode::Poisoned);
        }
        Ok(())
    }

    fn exit(&self) {
        self.in_call.store(false, Ordering::SeqCst);
    }

    pub(crate) fn call<T>(&self, f: impl FnOnce() -> Result<T, ErrorCode>) -> Result<T, ErrorCode> {
        self.enter()?;
        let result = f();
        self.exit();
        result
    }
}

/// The module instance's trap detector (wasm32 only: native targets unwind into [`catch`]).
#[cfg(target_arch = "wasm32")]
static MODULE: TrapDetector = TrapDetector::new();

/// Every FFI entry, constructors included, runs through this: on wasm32 it checks and updates
/// [`MODULE`]; on native targets it is a plain call.
pub(crate) fn module_call<T>(f: impl FnOnce() -> Result<T, ErrorCode>) -> Result<T, ErrorCode> {
    #[cfg(target_arch = "wasm32")]
    return MODULE.call(f);
    #[cfg(not(target_arch = "wasm32"))]
    f()
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

    /// SEC-18 on #63: a wasm trap skips `catch`, so the next call must notice the unfinished one.
    #[test]
    fn trap_detector_kills_the_module_after_an_unfinished_call() {
        let module = TrapDetector::new();
        assert_eq!(module.call(|| Ok(1)), Ok(1));
        // Simulates a trap: entered, never exited.
        module.enter().unwrap();
        assert_eq!(module.call(|| Ok(2)), Err(ErrorCode::Poisoned));
        // Stays dead, even for a call that would export state.
        assert_eq!(module.call(|| Ok(3)), Err(ErrorCode::Poisoned));
    }

    #[test]
    fn trap_detector_passes_errors_through_without_dying() {
        let module = TrapDetector::new();
        assert_eq!(
            module.call::<()>(|| Err(ErrorCode::InvalidInput)),
            Err(ErrorCode::InvalidInput)
        );
        assert_eq!(module.call(|| Ok(1)), Ok(1));
    }

    /// SEC-24 on #63: a call that starts while another one is panicking waits for it and then
    /// fails, instead of running (e.g. exporting state) between the panic and the dead flag.
    #[test]
    fn concurrent_call_during_a_panic_is_poisoned() {
        use std::sync::{Arc, mpsc};
        use std::thread;
        use std::time::Duration;

        let guard = Arc::new(PanicGuard::default());
        let (started_tx, started_rx) = mpsc::channel();
        let panicking = {
            let guard = Arc::clone(&guard);
            thread::spawn(move || {
                guard.run::<()>(|| {
                    started_tx.send(()).unwrap();
                    thread::sleep(Duration::from_millis(200));
                    panic!("boom")
                })
            })
        };
        started_rx.recv().unwrap();
        // Starts while the first call is still inside the guard.
        assert_eq!(guard.run(|| Ok("exported")), Err(ErrorCode::Poisoned));
        assert_eq!(panicking.join().unwrap(), Err(ErrorCode::Poisoned));
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
