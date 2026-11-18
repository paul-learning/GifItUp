#![no_main]
use libfuzzer_sys::fuzz_target;


fuzz_target!(|data: &[u8]| {
    if let Some((first, rest)) = data.split_first() {
        gifitup_crypto::fuzz_support::inner_snapshot(rest, first % 2 == 0);
    }
});
