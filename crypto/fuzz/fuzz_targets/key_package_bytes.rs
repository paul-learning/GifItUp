#![no_main]
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    gifitup_crypto::fuzz_support::key_package_bytes(data);
});
