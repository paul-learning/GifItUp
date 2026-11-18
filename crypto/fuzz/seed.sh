#!/usr/bin/env bash
# Writes valid seed objects (test keys only) into crypto/fuzz/corpus/<target>/ (gitignored). No network, no secrets.
# Run from anywhere; the pinned nightly of rust-toolchain.toml is used.
set -euo pipefail
cd "$(dirname "$0")"
RUSTFLAGS="--cfg fuzzing" cargo run --release --bin gen_seeds
