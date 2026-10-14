# Dependencies: adding, updating, verifying

The build pins and verifies every dependency (#33).

| Mechanism | File(s) | Protects against |
|---|---|---|
| **Version catalog** | `gradle/libs.versions.toml` | Scattered versions. The catalog is the single source of declared versions (CLAUDE.md). |
| **Dependency locking** | `gradle.lockfile` per project, `buildscript-gradle.lockfile`, `settings-gradle.lockfile` | Silent version drift through transitive dependencies or dynamic versions. Every configuration that **has** lock state must match it exactly. Gradle's default lock mode is used, not `STRICT`: a configuration *without* lock state (a new module, or one only the IDE or a rarely run task resolves) resolves unlocked without warning. `STRICT` would also fail Android Studio's sync on configurations we can't pre-lock. Dependency verification still applies to those configurations, so unlocked never means unverified. |
| **Dependency verification** | `gradle/verification-metadata.xml` | Tampered or swapped artifacts. Every downloaded artifact (jar, aar, klib, pom, module, plugin, Node/Yarn/Binaryen tool archive) must match a recorded SHA-256. |
| **Repository filtering** | `settings.gradle.kts` | Dependency confusion. Projects can't add repositories (`FAIL_ON_PROJECT_REPOS`). Google Maven only serves `androidx`/`com.android`/`com.google`. The Gradle Plugin Portal exclusively serves the ktlint plugin, and that plugin only comes from there. The Node.js/Yarn/Binaryen repositories each exclusively serve exactly one module. |
| **npm lockfiles** | `kotlin-js-store/wasm/yarn.lock` (+ `yarn.spikes.lock`) | Same for the npm packages used by the Kotlin/Wasm build (webpack, karma, …). A mismatch fails the build. |
| **Rust** | `crypto/Cargo.lock`, `crypto/rust-toolchain.toml` | Cargo verifies crate checksums from the lockfile; CI uses `--locked`. |

CI fails if a lockfile or the verification metadata is out of date. There's no separate check job for this: resolution itself fails.

## Adding or updating a Gradle dependency
1. Change `gradle/libs.versions.toml` (and the build file, if it's a new dependency).
2. **Before regenerating, check the new version itself:** the release exists in the publisher's changelog, and at least one artifact's checksum matches the publisher's (see step 3). Regenerating executes the new plugin and library code. Run it where a compromise costs little (#38 SEC-3): ideally a throwaway VM or container, at least a shell without GitHub tokens, SSH agent or signing keys loaded.
   Then regenerate the lockfiles and checksums with an **empty Gradle user home** (`-g`), covering every module and both spike modules:

   ```sh
   G=/tmp/gradle-fresh-home; rm -rf "$G"
   ./gradlew -g "$G" -Pgifitup.rust=true --write-locks --write-verification-metadata sha256 \
     check :androidApp:assembleDebug :androidApp:assembleRelease \
     :composeApp:wasmJsBrowserDistribution :spikes:mls-android:assembleDebugAndroidTest :server:installDist
   ./gradlew -g "$G" -Pgifitup.rust=true --write-locks --write-verification-metadata sha256 \
     :composeApp:wasmJsBrowserDevelopmentExecutableDistribution
   ```

   - **Why the empty home:** Gradle only records checksums for files it actually reads. With a warm cache it skips metadata files a fresh machine downloads (e.g. a BOM's `.module` instead of its `.pom`), and CI then fails. The fresh home also re-downloads Node.js/Yarn/Binaryen, so their checksums get recorded. It takes a few minutes and a few GB of disk; delete the directory afterwards.
   - **Separate run for the dev web bundle:** a KGP task-wiring issue when both web bundles build in one invocation.
   - **PowerShell:** use `.\gradlew.bat`, put everything on one line, and pass a Windows path to `-g`.
3. **Review the diff** of `gradle/verification-metadata.xml` and the lockfiles:
   - Only the dependencies you meant to change (and their expected transitives) should appear.
   - **New checksums are trust-on-first-use.** For a new direct dependency, compare at least one SHA-256 against the publisher's own checksum (Maven Central `.sha256`/`.sha1`, GitHub release digest) and name the source in the PR.
   - **Red flag: a changed or additional checksum for an artifact whose version did not change**, or any `<also-trust>` entry. Same coordinates must mean same bytes. Stop and investigate instead of committing. Also a red flag: anything that weakens verification, i.e. `<verify-metadata>` not `true`, a `<trust>` rule beyond the IDE sources/javadoc one, or a verification-mode override (`org.gradle.dependency.verification`, `--dependency-verification`/`-F`). The `Gradle check` CI job has a text-based tripwire for the common forms of these, plus `<also-trust>` entries and a missing metadata file. It catches accidental weakening, not a deliberate bypass: equivalent spellings (other properties-file syntax, `-Foff`, other whitespace, XML comments, JVM options, `verify-signatures` with an added `<trusted-key>`) get past it. Reviewing the diff of `verification-metadata.xml`, `gradle.properties`, `gradlew*` and `.github/` is the real control.
   - Remove entries the build no longer uses (Gradle doesn't prune the verification file; see "Pruning" below).
4. Commit `libs.versions.toml`, the lockfiles and `verification-metadata.xml` together, and justify the dependency in the PR (CLAUDE.md rule 9).

**Platform-specific artifacts.** A few artifacts differ per OS:
- Android's `aapt2` (`…:aapt2:<ver>:windows|linux|osx`)
- the Node.js, Yarn and Binaryen tool archives

The metadata must contain the **Windows** variant (dev machine) and the **Linux** variant (CI). A run on one OS only adds its own variant. If CI fails with "Dependency verification failed … not found in the verification metadata", the Linux checksum is missing:
- Download the failing CI job's `reports` artifact: `build/reports/dependency-verification/` lists the missing artifacts.
- Add the Linux entries, preferably by running the step-2 command once on Linux (WSL or a Linux container with JDK 21 + Android SDK).
- Or compute the SHA-256 of the exact artifact from the publisher (Google Maven, nodejs.org `SHASUMS256.txt`, GitHub release digest) and add it by hand.

## Dependabot PRs
Dependabot updates `libs.versions.toml` (and `Cargo.toml`/`Cargo.lock`, which is fine as is) but **not** the Gradle lockfiles or the verification metadata. So its Gradle PRs fail CI by design. To land one:
1. Check out the Dependabot branch.
2. Run step 2 above, in a throwaway environment: Dependabot PRs are exactly the untrusted-new-code case.
3. Review the diff as in step 3.
4. Push.

An agent can do this on request. No automation writes to Dependabot branches: that would need a workflow with write permissions, which we don't want (CLAUDE.md rule 8).

## Rust
- `cargo update -p <crate>` (or edit `Cargo.toml`), then `cargo test --locked`.
- **`wasm-bindgen` is pinned exactly** (`=0.2.129`). When bumping it, also bump `WASM_BINDGEN_VERSION` and `WASM_BINDGEN_SHA256` in `.github/workflows/ci.yml`, and reinstall the local CLI (`cargo install wasm-bindgen-cli --version <v> --locked`).
- Toolchain bumps go through `crypto/rust-toolchain.toml`. The Android NDK version is `android-ndk` in the version catalog. `cargo-ndk` is pinned in `ci.yml`.

## Pruning stale checksums
Gradle only adds entries to `verification-metadata.xml`. After removing or upgrading dependencies, the old entries stay. They're harmless but clutter reviews. To prune:
1. Move the file aside.
2. Regenerate with step 2 on Windows (empty Gradle home).
3. Re-add the Linux-only entries listed in the old file (`aapt2` `linux` classifier, `node-…-linux-x64`, `binaryen-…-x86_64-linux`).
4. Review the diff.

## Trust model
Checksums are **trust-on-first-use**: `verify-signatures` is `false`, so the first download of a version defines its "correct" bytes. The cross-check against publisher checksums in step 3 is the only defence against a version that was already malicious on first fetch. PGP signature verification (e.g. for Kotlin and Ktor, which sign their artifacts) is a possible later hardening step (#38 SEC-4).

## IDE
Android Studio / IntelliJ download `-sources.jar` and `-javadoc.jar` files during sync. These aren't on the compile classpath. `verification-metadata.xml` trusts them by pattern (`<trust file=".*-(sources|javadoc)[.]jar" regex="true"/>`; Gradle rewrites the file on regeneration, so the rule carries no XML comment), so the IDE doesn't fail verification. Everything else is verified.
