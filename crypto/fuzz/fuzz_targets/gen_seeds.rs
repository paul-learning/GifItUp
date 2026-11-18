//! Writes valid seed objects (test keys only) into `corpus/<target>/` for the raw-byte targets. Run by `seed.sh`.
fn main() {
    for (i, (target, bytes)) in gifitup_crypto::fuzz_support::seeds().into_iter().enumerate() {
        let dir = std::path::Path::new("corpus").join(target);
        std::fs::create_dir_all(&dir).expect("create the corpus directory");
        std::fs::write(dir.join(format!("seed-{i}")), bytes).expect("write a seed");
    }
}
