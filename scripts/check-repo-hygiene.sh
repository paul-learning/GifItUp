#!/usr/bin/env bash
# Repo hygiene checks for review findings a tool can catch (see docs/review-lessons.md).
# Runs in CI (Gradle check job) and locally before opening a PR: `bash scripts/check-repo-hygiene.sh`
# (Git Bash on Windows). Run from the repo root.
set -euo pipefail

status=0
fail() { echo "::error::$1"; status=1; }

# IDE/Windows artifacts (#35 CR-1): scripts must stay executable; Windows checkouts drop the bit.
while read -r mode path; do
  [ "$mode" = 100755 ] || fail "$path has mode $mode, expected 100755 (git update-index --chmod=+x $path)"
done < <(git ls-files -s -- gradlew '*.sh' '.githooks/*' | awk '{print $1, $4}')

# IDE artifacts (#35 CR-2/SEC-1, #36 CR-4): Android Studio regenerates the daemon-JVM file with JDK download URLs.
daemon=gradle/gradle-daemon-jvm.properties
if [ -f "$daemon" ] && grep -nE "^[[:space:]]*toolchainUrl" "$daemon"; then
  fail "$daemon contains toolchainUrl entries; it is hand-maintained, see its header"
fi

# Pins that must match (#37 CR-3): wasm-bindgen crate (exact) = Cargo.lock = CLI pinned in ci.yml.
toml=$(sed -nE 's/^wasm-bindgen = "=([0-9.]+)".*/\1/p' crypto/Cargo.toml)
lock=$(awk '/^name = "wasm-bindgen"$/ { getline; gsub(/[^0-9.]/, ""); print }' crypto/Cargo.lock)
cli=$(sed -nE 's/^ *WASM_BINDGEN_VERSION: *([0-9.]+).*/\1/p' .github/workflows/ci.yml)
if [ -z "$toml" ]; then
  fail "crypto/Cargo.toml: wasm-bindgen must be an exact pin (\"=x.y.z\")"
elif [ "$toml" != "$lock" ] || [ "$toml" != "$cli" ]; then
  fail "wasm-bindgen versions differ: Cargo.toml=$toml Cargo.lock=$lock ci.yml WASM_BINDGEN_VERSION=$cli"
fi

# Server never sees message content (ADR-0006): the encrypted content schema lives in :shared, so neither :server nor
# :protocol (the server's only project dependency) may depend on it. A text check: catches type-safe accessors and
# any ":shared" project path, not every build-script construct.
for build in server/build.gradle.kts protocol/build.gradle.kts; do
  if grep -nE 'projects\.shared|":shared"' "$build"; then
    fail "$build references :shared; the server must never get the content schema (ADR-0006)"
  fi
done

# The test-panic guard in crypto/src/test_panic.rs keys on debug_assertions, so the release profile must never
# turn them on (ADR-0007, #67 SEC-5). A text check of the common spelling only; per-package overrides, custom
# profiles or RUSTFLAGS get past it (#67 SEC-7). The CI scans of the shipped files are the real control.
# Every [profile.release...] table counts, including per-package overrides (#67 CR-9).
if awk '/^\[/ { rel = ($0 ~ /^\[profile\.release[].]/) } rel' crypto/Cargo.toml | grep -nE '^[[:space:]]*debug-assertions[[:space:]]*=[[:space:]]*true'; then
  fail "crypto/Cargo.toml: the release profile must not enable debug-assertions (test-panic guard)"
fi

[ "$status" -eq 0 ] && echo "Repo hygiene checks passed."
exit "$status"
