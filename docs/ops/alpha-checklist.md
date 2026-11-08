# Owner checklist for the friends alpha (#27)

For the person running the server. Each step says where it is described in detail. Releases are **immutable** (already turned on
for this repo): a published release cannot be changed, only replaced by a newer version, so check the draft before publishing.

## 1. Before the first friend

- [ ] All reaction PRs (#262 to #266) and the earlier ones are merged and `main` is deployed (Actions: Deploy, approve the
  `production` environment). The CI jobs on `main` are green, including `Gradle check` (it runs `:e2e:test`).
- [ ] **Tag the release** (`docs/ops/release.md`): `git tag vX.Y.Z <commit-on-main> && git push origin vX.Y.Z`. Use the next free
  number (`v0.1.5`). `v0.1.4` stays an unpublished draft: its tag name was used by the empty release made by hand, and GitHub
  would not publish it. Never create the release by hand on GitHub: the workflow makes
  the draft with the APK.
- [ ] Actions: Release. Approve the `release` environment when asked.
- [ ] Open the **draft** release. Download `gifitup-vX.Y.Z.apk`, install it on your own phone, and check the certificate
  fingerprint equals your release key's (`apksigner verify --print-certs`).
- [ ] Only then **publish** the draft.
- [ ] `assetlinks.json` on the VM carries the release certificate's fingerprint (colons, capitals, the only one) and
  `bash scripts/check-web-headers.sh https://<host> --assetlinks <fingerprint>` passes (`docs/ops/release.md`, step 7).
- [ ] Open the web app in a browser: it loads, the reaction dialog shows emojis as pictures (the heart too), and note the
  first-load time (about 2.5 MB of font).

## 2. Smoke test on real devices (two accounts, Android and web)

- [ ] Register with an invite on each; the invite link opens the app on Android.
- [ ] Find each other, start a chat, send a GIF with a caption each way.
- [ ] The chat list appears at once when you open the app.
- [ ] React on both sides; the chip appears for both; the dialog lists who reacted; change it; remove it.
- [ ] Compare the 12 emojis and names on a call and save the key.
- [ ] Make a group of three and rename it.
- [ ] **Live updates** (needs the release that has them): with the app open on both devices, send a GIF and a reaction each way:
  it shows within a second or two on the other side, not after 15 s. Put one device into the background for a minute and bring
  it back: the chat is current again. Switch one device to flight mode and back: it catches up without a restart.
- [ ] **Android socket check (owner, once, #27 SEC-4):** with the release build on a phone, add a user CA (Settings, Security,
  Encryption and credentials, Install a certificate) that signs a test host and check the app refuses to talk to a server
  that uses it; and check a plain `http://` or `ws://` server address is refused. The socket uses the platform's trust store
  like every other request (ADR-0020); this is the manual proof, as no automated test runs on a device.
- [ ] Try the limits: nothing should say something is broken when it is only the server asking you to slow down.

## 3. Inviting friends

- [ ] Create invites in the app (Create invite). One invite per person: single-use, and they expire.
- [ ] Send each friend, in two separate messages: the invite link, and the release's certificate fingerprint. Also send them the
  guide (`docs/alpha-guide.md` on GitHub): it says what is private and what is not. Invite links never go into the repo.
- [ ] Tell them to compare the emojis with you on a call.

## 4. While the alpha runs

- [ ] Watch the server (`docs/ops/deploy.md`): health, disk, backups run (`docs/ops/`), deploy approvals.
- [ ] Open issues are labelled `later` and are not in the milestone without your OK; the web-module tests are #260.
- [ ] Fixing a bug in a shipped APK means a new version: tag the next number, test the draft, publish.
