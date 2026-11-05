# Releasing the Android app (owner)

The workflow `.github/workflows/release.yml` builds, signs and drafts a GitHub Release when you push a tag. Design and limits: ADR-0019 and the threat model ("Release signing key in CI"). Nothing here is done by an agent: the keystore, secrets, rules and tags are yours.

## One-time setup

1. **The keystore** (on your own machine, not in the repo, not in a synced folder):
   ```bash
   keytool -genkeypair -v -keystore gifitup-release.keystore -alias gifitup -keyalg RSA -keysize 4096 -validity 10000
   ```
   Use a long passphrase and give the key the same one as the keystore (a PKCS12 keystore, which `keytool` makes by default, ignores a separate key password and `apksigner` then fails). Then print the certificate's SHA-256:
   ```bash
   keytool -list -v -keystore gifitup-release.keystore -alias gifitup | grep 'SHA256:'
   ```
   `RELEASE_CERT_SHA256` wants it as 64 lowercase hex digits without colons: `echo 'AB:CD:…' | tr -d ':' | tr 'A-F' 'a-f'`. Keep the colon form with capitals for `assetlinks.json` (step 6).
2. **Back it up now.** Copy the keystore file and both passphrases to two places you trust (for example a password manager's file attachment and an offline USB drive). **If you lose the key, nobody can update the app: you make a new key and everyone uninstalls and reinstalls (their local data goes with it).** If you think the key leaked, do the same.
3. **Repository variables** (*Settings → Secrets and variables → Actions → Variables*): `GIFITUP_PUBLIC_URL` (the server's canonical origin; the app link host is derived from it; not secret).
4. **Environment `release`** (*Settings → Environments → New environment*):
   - **Required reviewers:** you. Leave "Prevent self-review" off.
   - **Turn off "Allow administrators to bypass configured protection rules".**
   - **Deployment branches and tags:** selected tags, pattern `v*`.
   - **Environment secrets:** `RELEASE_KEYSTORE_BASE64` (`base64 -w0 gifitup-release.keystore`), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`gifitup`), `RELEASE_KEY_PASSWORD`.
   - **Environment variable:** `RELEASE_CERT_SHA256` = the 64 lowercase hex digits from step 1.
   Then delete the base64 text from your clipboard and shell history.
5. **Tag ruleset** (*Settings → Rules → Rulesets → New tag ruleset*): target tags `v*`, rules *restrict creations*, *restrict updates* and *restrict deletions*. Put yourself on the bypass list (the *Repository admin* role), or nobody can create a tag. The limit is the one in the threat model: the token agents run with has the same rights, so the ruleset keeps out everyone else and is policy, not a barrier, for an agent acting as you.
6. **Immutable releases** (*Settings → General → Releases → Enable release immutability*, if your plan has it): a published release's assets can't be replaced or deleted afterwards. Without it, "never replace the APK of a version" is a rule, not a barrier (#251 SEC-18).
7. **`assetlinks.json` on the VM** (the Caddy site block, `deploy/caddy/gifitup.caddy`): replace `REPLACE_WITH_RELEASE_SHA256` with the certificate's SHA-256 **with colons, uppercase** (Android's format), and make sure it is the **only** fingerprint. Reload Caddy, then run `bash scripts/check-web-headers.sh https://<host> --assetlinks <that fingerprint>` from your machine.

## Making a release

1. Merge what you want to ship to `main`; wait for CI.
2. Pick the next version `vMAJOR.MINOR.PATCH` (minor and patch up to 99; it must be higher than every earlier tag).
3. Tag that commit of `main` and push the tag:
   ```bash
   git tag v0.1.0 <commit> && git push origin v0.1.0
   ```
4. *Actions → Release*: the build runs, then the `sign` job waits for your approval of the `release` environment. **This approval is the real gate**: the workflow's own checks (the commit is on `main`, the tag is well-formed) are in the file of the tagged commit, so a tag on an edited workflow would simply leave them out. Before approving, on your own machine: `git fetch origin && git merge-base --is-ancestor <commit> origin/main` must succeed (the commit is on `main`, which is what a tag on an edited workflow would not be), and `git diff --stat <previous release tag>..<commit>` must show only what you meant to release (for the first release, compare with the commit you tested). The workflow file the run uses is the one at that commit, so a commit that is on `main` has been through review and CI like any merge. Only then approve.
5. The workflow makes a **draft** release with `gifitup-v0.1.0.apk` and `SHA256SUMS`. Download the APK, install it on a phone, and check the certificate on a second path: `apksigner verify --print-certs gifitup-v0.1.0.apk` must show the SHA-256 you wrote down in step 1. Then publish the draft.

## If something is wrong
- The workflow stops with a message: the tag is not `vMAJOR.MINOR.PATCH`, not newer than the earlier tags, or its commit is not on `main`. Delete the tag (`git push --delete origin <tag>`, `git tag -d <tag>`) and tag again correctly.
- A release is bad after publishing: publish a newer version; never replace the APK of a version.
- Key lost or leaked: see step 2. Remove the old fingerprint from `assetlinks.json` and add the new one.
