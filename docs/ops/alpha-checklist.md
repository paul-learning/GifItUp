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
- [ ] React on both sides; the chip appears for both; the panel lists who reacted; change it; remove it (tap your own emoji again). Android: no React button, long press only.
- [ ] Compare the 12 emojis and names on a call and save the key.
- [ ] Make a group of three and rename it.
- [ ] **Live updates** (needs the release that has them): with the app open on both devices, send a GIF and a reaction each way:
  it shows within a second or two on the other side, not after 15 s. Put one device into the background for a minute and bring
  it back: the chat is current again. Switch one device to flight mode and back: it catches up without a restart.
- [ ] **Android socket check (owner, once, before the first alpha tag, #27 SEC-4 / M2 review):** the release build checks
  the network rules in the built file (`scripts/assert-release-apk.sh`: system store only, no user CA, no cleartext), and the
  socket uses the platform's trust store like every other request (ADR-0020), but no automated test runs on a phone. Do this
  on the release build, with the app open on a chat so the socket is connected:
  1. *User-added CA refused.* Run a TLS proxy you control (for example `mitmproxy`) on your laptop, install its CA certificate
     on the phone (Settings, Security, Encryption and credentials, Install a certificate, CA certificate), and set the phone's
     Wi-Fi proxy to the laptop. Open the app: the chats must **not** load (the app shows its network problem), and the proxy
     must show **no** decrypted request to your server and **no** `/ws` upgrade. If it does, stop and tell Claude Code: the
     app trusts a user CA.
  2. *Plain `ws://` / `http://` refused.* In the app's custom server field (Profile, Server; not on the web) enter
     `http://<laptop-ip>:8080` while a local server listens there: the app must not accept the address or connect to it, and
     nothing may reach the laptop (the address check is the app's own; the network rules would block it anyway).
  3. Undo: remove the CA, the proxy setting and the custom server; reopen the app: the chats load and the socket connects
     (a message from the other device shows within a second or two).
  Write the result (date, phone, Android version, pass/fail) under the checklist's results or on #27.
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
- [ ] Diagnostics (menu → About and diagnostics): the version is right; Android: Share opens the chooser, Save to file writes `gifitup-log.txt` (also over an existing longer file), Copy works; web: Save downloads the file; Clear log leaves one "log cleared" line; kill the app and reopen: the log is still there; sign out/wipe: it is gone.
