#!/usr/bin/env bash
# Repo hygiene checks for review findings a tool can catch (see docs/review-lessons.md).
# Runs in CI (Gradle check job) and locally before opening a PR: `bash scripts/check-repo-hygiene.sh`
# (Git Bash on Windows). Run from the repo root.
set -euo pipefail

status=0
fail() { echo "::error::$1"; status=1; }

# Greps the NUL-separated file names on stdin for an extended regex and prints the matching lines. Succeeds on a match
# and also when grep itself fails (an unreadable file: status 2), so a check that uses it fails closed (#102 SEC-13);
# xargs would turn that error into the same status as "no match".
grep_files() {
  local files=() rc=0
  mapfile -d '' files || return 0 # bash < 4.4 has no -d: fail closed (#102 SEC-20)
  [ "${#files[@]}" -gt 0 ] || return 1
  grep -nE -- "$1" "${files[@]}" || rc=$?
  [ "$rc" -ne 1 ] || return 1
  [ "$rc" -eq 0 ] || echo "grep failed with status $rc"
  return 0
}

# IDE/Windows artifacts (#35 CR-1): scripts must stay executable; Windows checkouts drop the bit.
while read -r mode path; do
  [ "$mode" = 100755 ] || fail "$path has mode $mode, expected 100755 (git update-index --chmod=+x $path)"
done < <(git ls-files -s -- gradlew '*.sh' '.githooks/*' | awk '{print $1, $4}')

# IDE artifacts (#35 CR-2/SEC-1, #36 CR-4): Android Studio regenerates the daemon-JVM file with JDK download URLs.
daemon=gradle/gradle-daemon-jvm.properties
if [ -f "$daemon" ] && grep -nE "^[[:space:]]*toolchainUrl" "$daemon"; then
  fail "$daemon contains toolchainUrl entries; it is hand-maintained, see its header"
fi

# Pins that must match (#37 CR-3): wasm-bindgen crate (exact) = Cargo.lock = CLI pinned in ci.yml and deploy.yml.
toml=$(sed -nE 's/^wasm-bindgen = "=([0-9.]+)".*/\1/p' crypto/Cargo.toml)
lock=$(awk '/^name = "wasm-bindgen"$/ { getline; gsub(/[^0-9.]/, ""); print }' crypto/Cargo.lock)
cli=$(sed -nE 's/^ *WASM_BINDGEN_VERSION: *([0-9.]+).*/\1/p' .github/workflows/ci.yml)
deploy_wb=$(sed -nE 's/^ *WASM_BINDGEN_VERSION: *([0-9.]+).*/\1/p' .github/workflows/deploy.yml)
if [ -z "$toml" ]; then
  fail "crypto/Cargo.toml: wasm-bindgen must be an exact pin (\"=x.y.z\")"
elif [ "$toml" != "$lock" ] || [ "$toml" != "$cli" ] || [ "$toml" != "$deploy_wb" ]; then
  fail "wasm-bindgen versions differ: Cargo.toml=$toml Cargo.lock=$lock ci.yml=$cli deploy.yml=$deploy_wb"
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
# Every [profile.release...] table counts, including per-package overrides and indented headers (#67 CR-9,
# SEC-11); awk prints the offending Cargo.toml line number.
if awk '/^[[:space:]]*\[/ { rel = ($0 ~ /^[[:space:]]*\[profile\.release[].]/) }
        rel && /^[[:space:]]*debug-assertions[[:space:]]*=[[:space:]]*true/ { print FNR ": " $0; found = 1 }
        END { exit !found }' crypto/Cargo.toml; then
  fail "crypto/Cargo.toml: the release profile must not enable debug-assertions (test-panic guard)"
fi

# No signing oracle across the FFI (ADR-0009, round-0 SEC-14 on #68): the bindings may export only the typed sign_auth,
# never a function that signs caller-supplied bytes. Text checks: (1) a function with a `sign` name segment in the
# binding files (`sign_bytes`, `raw_sign`, generics like `sign<T>`); (2) the word `wasm_bindgen` or `uniffi::export`
# (any spacing, inside `cfg_attr` too) anywhere outside the two binding files, so a new export can't hide in another
# module (#70 CR-3, SEC-24, SEC-27). Not caught: a signing function under a name without `sign`, or an export reached
# through a macro or a `use` alias; review covers those.
if grep -nE 'fn ([a-z0-9]+_)*sign(_[a-z0-9]+)*[[:space:]]*[(<]' crypto/src/uniffi_api.rs crypto/src/wasm_api.rs | grep -vE 'fn sign_auth[[:space:]]*\('; then
  fail "crypto bindings export a signing function other than sign_auth (ADR-0009)"
fi

if grep -rnE 'wasm_bindgen|uniffi[[:space:]]*::[[:space:]]*export' crypto/src --include='*.rs' | grep -vE '^crypto/src/(uniffi_api|wasm_api)\.rs:'; then
  fail "FFI exports belong in crypto/src/uniffi_api.rs or wasm_api.rs only (ADR-0009)"
fi

# The unchecked auth encoder bypasses the typed subject and origin (#71 SEC-31, SEC-32): only tests may use it. Route
# code must go through authSignContent(challenge, CanonicalOrigin, AuthSubject).
if grep -rln 'authSignContentUnchecked' server/src/main --include='*.kt' | grep -v 'server/src/main/kotlin/app/gifitup/server/auth/AuthSignContent.kt'; then
  fail "authSignContentUnchecked is for tests only (ADR-0009)"
fi

# The dev-only loopback endpoint (#94 SEC-14, SEC-17): ApiEndpoint.loopback sends to another place than the origin it
# signs. (1) Its opt-in marker may appear only in its own file, the shared module's tests, the dev CLI and the
# end-to-end test, so neither a module-wide opt-in (build files, gradle.properties) nor a wrapper in another module can
# hand it to the app. (2) The app modules don't call loopback at all. Text checks of tracked files; the opt-in
# annotation (level ERROR) is the compile-time control.
devonly_allowed='^(shared/src/commonMain/kotlin/app/gifitup/shared/data/network/ApiEndpoint\.kt|shared/src/[A-Za-z]*Test/|cli/|e2e/)'
if git ls-files -z -- '*.kt' '*.kts' '*.properties' | grep -zvE "$devonly_allowed" | grep_files 'DevOnlyEndpoint'; then
  fail "DevOnlyEndpoint is mentioned outside ApiEndpoint.kt, shared's tests, cli/ and e2e/ (#94 SEC-17)"
fi
if grep -rnE --include='*.kt' --include='*.kts' --exclude-dir=build 'ApiEndpoint\.loopback|\.loopback[[:space:]]*\(' composeApp androidApp; then
  fail "the app modules must not use ApiEndpoint.loopback (#94 SEC-14)"
fi

# The dev CLI and the end-to-end test are dev/test tools (AGENTS.md "Dev CLI"; #79 round-0 SEC-11): no build file
# other than settings.gradle.kts and the end-to-end test's own (it runs the installed CLI; #79 PR C) may refer to
# them, so no module (app or library) can depend on them, and nothing can depend on :e2e. The
# DevOnlyEndpoint allowlist above relies on this. A text check of the usual spellings: type-safe accessors, project
# paths and names in either quote ("cli", ':e2e', also built up as rootProject.path + "cli"), and paths into the tool
# dirs (files("../cli/build/libs/…"), srcDir("../cli/src/…")), #102 SEC-13. Not caught: a name assembled from pieces
# or read from a property. CI also scans the shipped APKs and web bundle for the CLI's code (ci.yml).
build_refs='projects\.(cli|e2e)\b|["'"'"']:?(cli|e2e)["'"'"']|(^|[^A-Za-z0-9_.-])(\.\./)*(cli|e2e)/'
if git ls-files -z -- '*.gradle.kts' '*.gradle' | grep -zvxE 'settings\.gradle\.kts|e2e/build\.gradle\.kts' | grep_files "$build_refs"; then
  fail "a build file refers to :cli or :e2e or their dirs; only settings.gradle.kts and e2e/ may (#79 SEC-11, #102 SEC-13)"
fi

# The CLI's output and CI logs get no log lines (#79 plan SEC-9): its runtime classpath has no slf4j provider other
# than slf4j-nop. An allowlist of the groups and slf4j artifacts on it (#103 CR-4, SEC-15), checked on the lock state,
# which the build must match: a new runtime dependency fails here until someone checks it brings no logging backend
# and adds it.
cli_runtime_allowed='^(io\.ktor|net\.java\.dev\.jna|org\.jetbrains|org\.jetbrains\.kotlin|org\.jetbrains\.kotlinx):|^org\.slf4j:slf4j-(api|nop):'
if [ ! -f cli/gradle.lockfile ]; then
  fail "cli/gradle.lockfile is missing (#79 SEC-9)"
elif ! grep -qE '^org\.slf4j:slf4j-nop:.*[=,]runtimeClasspath(,|$)' cli/gradle.lockfile; then
  fail "cli/gradle.lockfile has no slf4j-nop on runtimeClasspath, so the allowlist below checks nothing (#103 SEC-19)"
elif grep -E '[=,]runtimeClasspath(,|$)' cli/gradle.lockfile | grep -vE "$cli_runtime_allowed"; then
  fail "cli's runtime classpath has an artifact outside the allowlist (no slf4j provider but slf4j-nop, #79 SEC-9)"
fi

# The VM's Caddy sends a request to the server only when its path is in the site block's API list (#27 SEC-8); everything
# else is the web app. A route the server gains without being listed would be answered by the web app's fallback, so
# every route prefix in the server's *_PATH constants must appear in the list.
caddy=deploy/caddy/gifitup.caddy
api_line=$(grep -E '^[[:space:]]*@api path ' "$caddy" || true)
[ -n "$api_line" ] || fail "$caddy has no '@api path' matcher (#27 SEC-8)"
# Exact paths, not prefixes (#27: "/welcomes/*" does not cover GET "/welcomes"; the web app answered it with index.html and no
# client could fetch its invitations). A route constant of one segment (/welcomes) must be listed as itself, one with a deeper
# path (/auth/challenge) as its "/segment/*".
# A constant the pattern below can't read (a digit, an uppercase letter or a {parameter} in the path) would be skipped without a
# word, so every literal route constant must match it, and at least one must be found (#255 SEC-1).
route_literals=$(grep -rhoE 'const val [A-Z_]+_PATH = "[^"$]*"' server/src/main --include='*.kt' || true)
route_readable=$(grep -cE '"/[a-z-]+(/[a-z-]+)*"$' <<<"$route_literals" || true)
[ "$(grep -c . <<<"$route_literals")" -gt 0 ] || fail "no *_PATH route constants found in server/src/main (the Caddy route guard would check nothing, #255)"
[ "$(grep -c . <<<"$route_literals")" -eq "$route_readable" ] || fail "a *_PATH route constant has characters the Caddy route guard can't read (digit, uppercase, {param}): update scripts/check-repo-hygiene.sh (#255)"
while read -r name route; do
  [ -n "$route" ] || continue
  rest=${route#/}
  if [[ "$rest" == */* ]]; then
    wants=("/${rest%%/*}/*")
  else
    wants=("$route")
    # A route the server also serves below ($NAME/...: /welcomes/{id}/ack, /invites/{id}) needs its "/route/*" too (#255 CR-1).
    if grep -rqF "\$$name/" server/src/main --include='*.kt'; then wants+=("$route/*"); fi
  fi
  for want in "${wants[@]}"; do
    case " $api_line " in
      *" $want "*) ;;
      *) fail "server route $route needs '$want' in the @api list of $caddy (#27 SEC-8)" ;;
    esac
  done
done < <(grep -rhoE 'const val [A-Z_]+_PATH = "/[a-z-]+(/[a-z-]+)*"' server/src/main --include='*.kt' | sed -E 's/const val ([A-Z_]+) = "(.*)"/\1 \2/' | sort -u)

# The web app downloads GIFs with fetch() from exactly the hosts GifReference accepts, and the Content-Security-Policy
# of the web handle must allow them in connect-src (#27: with only api.klipy.com every GIF tile failed in the browser).
# If the host pattern in the client changes, this fails until the policy and this list are updated together.
gif_ref=shared/src/commonMain/kotlin/app/gifitup/shared/data/content/GifReference.kt
if ! grep -qF 'static[12]?\\.klipy\\.com' "$gif_ref"; then
  fail "the GIF host pattern in $gif_ref changed: update connect-src in $caddy and the host list in scripts/check-repo-hygiene.sh (#27)"
fi
web_csp=$(grep -E 'Content-Security-Policy "default-src .none.; script-src' "$caddy" || true)
connect=$(sed -nE "s/.*connect-src ([^;]*);.*/\1/p" <<<"$web_csp")
img=$(sed -nE "s/.*img-src ([^;]*);.*/\1/p" <<<"$web_csp")
# The exact lists, not "contains": a host added to the policy, or one put back into img-src, must be a deliberate change
# here too (#254 review).
want_connect="'self' https://{host} wss://{host} https://api.klipy.com https://static.klipy.com https://static1.klipy.com https://static2.klipy.com"
[ "$connect" = "$want_connect" ] || fail "connect-src of the web policy in $caddy is not exactly the expected list (GIFs are fetched from the hosts GifReference accepts, #27): $connect"
[ "$img" = "'self' blob:" ] || fail "img-src of the web policy in $caddy must be exactly 'self' blob: (the client shows GIFs from decoded bytes, #27): $img"

[ "$status" -eq 0 ] && echo "Repo hygiene checks passed."
exit "$status"
