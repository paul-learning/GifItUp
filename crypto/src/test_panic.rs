//! Test-only panic trigger (`test-panic` feature) for the panic and trap tests in `:mls` (ADR-0007).
//!
//! It adds no FFI function, so the uniffi bindings and their checksums stay the same with and
//! without the feature. Instead, [`maybe_panic`] makes an existing call panic on a reserved
//! username. The feature can't be built with the release profile (the `compile_error!` below), and
//! CI scans every release artifact for the name of [`gifitup_test_panic_marker`] (`:mls:verifyNoTestPanic`).

#[cfg(not(debug_assertions))]
compile_error!("the test-panic feature must never be built with the release profile (ADR-0007)");

/// Calling `inspect_key_package` with this username panics in a `test-panic` build.
pub(crate) const PANIC_USERNAME: &str = "__panic__";

/// Exported only so release artifacts can be scanned for it; never called.
#[unsafe(no_mangle)]
pub extern "C" fn gifitup_test_panic_marker() -> u32 {
    0x7e57
}

pub(crate) fn maybe_panic(username: &str) {
    if username == PANIC_USERNAME {
        panic!("test-panic: deliberate panic for the panic and trap tests");
    }
}
