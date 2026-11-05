# ADR-0019: Release pipeline and signing key

- **Status:** accepted
- **Date:** 2026-11-05
- **Issue/PR:** #27 (PR 2). Round-0 plan review by the security reviewer (SEC-1 to SEC-4, SEC-10, SEC-11). Owner decisions in chat, 2026-11-04: "CI signs with an environment secret" (key custody), "Public GitHub Release" (APK access), "Yes, tag-driven (Recommended)" (versioning).

## Context
Friends install the Android app as an APK from a public GitHub Release (no store). The APK must be signed by one stable key, and a released version must be reproducible from a commit of `main`. The repository is public and agents work with the owner's token, so the release path must not hand build code the signing key.

## Options considered
1. **The owner builds and signs locally.** The key never enters GitHub, but every release depends on one machine and a manual process. Offered; not chosen.
2. **One CI job builds and signs (Gradle signing config from the environment).** The keystore sits next to Gradle's third-party plugin code. Rejected (SEC-1).
3. **Three jobs: build unsigned (read-only token, no secrets), sign (environment `release`, no Gradle), publish (write token, no secrets).** Chosen.

## Decision
- `.github/workflows/release.yml`, triggered by a pushed tag `v*`, `permissions: {}` at the top, actions pinned by SHA, no caches, a concurrency group, a **draft** release the owner publishes by hand.
- **Version from the tag:** only `vMAJOR.MINOR.PATCH` (digits, no leading zeros, minor and patch up to 99, major up to 200); `versionCode = major*10000 + minor*100 + patch`, greater than that of every other release tag (`scripts/release-version.sh`, tested). The tag reaches the shell only through the environment.
- **Gate in front of the key:** the tagged commit must be an ancestor of `origin/main` (checked in the build job and again in the sign job); the owner sets up a tag ruleset for `v*` and the `release` environment (tags `v*` only, required reviewer, admin bypass off). These are owner steps in `docs/ops/release.md`.
- **Signing:** `apksigner` on the unsigned APK, passwords through environment variables, the keystore in a private file removed in an `if: always()` step; afterwards `apksigner verify` must show exactly one signer whose SHA-256 equals the environment variable `RELEASE_CERT_SHA256`, which is also the one fingerprint in `assetlinks.json`.
- **Assertions on what is public** (`scripts/assert-release-apk.sh`, also run in CI on every PR's release build): package, versionCode and versionName, not debuggable, `allowBackup=false`, no cleartext, the one `INTERNET` permission, the invite link host is the build's host, no placeholder host, no build-machine paths, no mapping or debug files. Only the APK and its `SHA256SUMS` are attached.
- The host baked into the app is the repository variable `GIFITUP_PUBLIC_URL`, not a secret.

## Consequences
- The key's protection against an agent acting as the owner is policy, not a technical barrier (threat model, "Release signing key in CI"). Loss of the key means a new key and a reinstall for everyone.
- A release needs the owner's approval of the `release` environment, one click per tag.
- No new dependency, no new third-party action.
