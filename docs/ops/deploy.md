# Deploying the server

How a merge to `main` reaches the VM, and the one-time setup the owner does. VM layout: [vm-setup.md](vm-setup.md). Design and threat analysis: round-0 security review on #7, [ADR-0005](../adr/0005-deployment-shared-vm.md).

## Flow
[`.github/workflows/deploy.yml`](../../.github/workflows/deploy.yml) runs on every push to `main`:

1. **`build`** (read-only token): `./gradlew :server:installDist` with dependency verification, **without** any Actions or Gradle build cache. The release build never reuses outputs that other jobs could have written.
2. **`push`** (package token; no Gradle): builds [`server/Dockerfile`](../../server/Dockerfile) (distroless Java 21, non-root) and pushes `ghcr.io/paul-learning/gifitup-server:<commit>`. Then it attests the pushed digest with GitHub build provenance.
3. **`deploy`** (environment **`production`**): **waits for the owner's approval**, then:
   - verifies the provenance: the image was built by `deploy.yml` from `main` at exactly this commit
   - SSHes to the VM as `deploy`

   The SSH key can only run [`deploy/deploy.sh`](../../deploy/deploy.sh), and the script only accepts `<commit> sha256:<digest>`. It then:
   - checks the commit is on `main` and checks it out (detached), so compose and script come from **that** commit
   - checks the two secret files (exist, non-empty, world-readable; [vm-setup.md](vm-setup.md))
   - runs the one-shot `migrate` container with the **new** image (roles, app password, Flyway; ADR-0008). If that fails, the deploy stops and the running server is untouched
   - writes the digest into `.env` and restarts `server`
   - checks that the container runs that image and that `https://…/health` returns 200
   - if the check fails, it **rolls back automatically** to the last good commit and digest

   It prints only status lines, because Actions logs are public. Details go to the VM journal: `journalctl -t gifitup-deploy`.

**What approving a deploy means:** that commit's `docker-compose.yml` and `deploy.sh` run on the shared VM with the `deploy` user's rights. Those are root-equivalent through Docker (ADR-0005). Approve only commits you merged yourself.

**Agents never dispatch, re-run or approve deploys** (AGENTS.md hard rule; deny list in `.claude/settings.json`).

## Invites and accounts (admin CLI, ADR-0010)
On the VM, as `deploy`, from `/opt/gifitup/deploy`. Always with `--rm`: the `admin` service has Docker logging off, and `--rm` removes the container afterwards, so an invite link isn't kept on disk.

- **Create an invite** (the first one for yourself, or any time for someone):
  ```bash
  docker compose --profile admin run --rm admin create-invite
  ```
  It prints the link once, plus its expiry (24 h). Open it on the phone or browser that should get the account, or send it over a channel you trust. Each link works once. Don't paste it anywhere shared: whoever opens it first gets the account.
- **Remove an account** (its devices, sessions and invites; people it invited keep their accounts, but are cut off from the invite tree, so a later `--with-invitees` on someone above won't reach them; to remove a whole branch, use the next command instead):
  ```bash
  docker compose --profile admin run --rm admin delete-account <username>
  ```
- **Remove an account and everyone it invited** (contains a leaked or misused link):
  ```bash
  docker compose --profile admin run --rm admin delete-account --with-invitees <username>
  ```
  Both print how many accounts were removed and exit 1 if the username doesn't exist. If a command fails with a database error and printed no result (e.g. a registration was in flight at the same moment and the database aborted the delete), nothing was changed: run it again (#74 SEC-60). If a link or a "deleted" line was printed before the error, that result stands: a printed invite link stays valid, so don't create a second one (#74 SEC-62).

## Android App Links (ADR-0013, #17 PR 4)
So that tapping `https://<your host>/invite#<code>` opens the app instead of the browser. Three parts, all yours; nothing here is secret, but the host and the fingerprint stay out of the repo.

1. **Caddy serves the verification file.** `deploy/caddy/gifitup.caddy` has a `handle /.well-known/assetlinks.json` block. In the site block on the VM, replace `REPLACE_WITH_RELEASE_SHA256` with the SHA-256 fingerprint of the **release** signing certificate (`keytool -list -v -keystore <release keystore> -alias <alias>`, the `SHA256:` line, with its colons). To test with a debug build, add the debug key's fingerprint as a second entry in the same list (`"sha256_cert_fingerprints":["<release>","<debug>"]`) and **remove it before the friends alpha** (#27): anyone with that debug key could otherwise sign an app that opens invite links. Reload Caddy, then check: `curl -i https://<your host>/.well-known/assetlinks.json` must answer `200`, `Content-Type: application/json`, no redirect.
2. **Build the app for that host.** The host comes from the same build property as the default server: `./gradlew :androidApp:installDebug "-Pgifitup.defaultOrigin=https://<your host>"`. A build without it (or with a port or anything but a plain host) gets a host that no link matches, so App Links stay off.
3. **Check it on the phone** (USB debugging on):
   - `adb shell pm get-app-links app.gifitup` should list your host as `verified`. If it says `none` or `1024`, Android couldn't fetch or match the file: re-check part 1 and run `adb shell pm verify-app-links --re-verify app.gifitup`.
   - `adb shell am start -a android.intent.action.VIEW -d "https://<your host>/invite#<a real invite code>"` opens the app with the invite prefilled.
   - Foreign extras must do nothing: `adb shell am start -n app.gifitup/app.gifitup.android.MainActivity --ei android-support-nav:controller:deepLinkIds 0` must open the normal start screen (#131 SEC-21). The same while the app is open on its start screen, as a new intent for the running activity: `adb shell am start -f 0x20000000 -n app.gifitup/app.gifitup.android.MainActivity --ei android-support-nav:controller:deepLinkIds 0` (that flag is `FLAG_ACTIVITY_SINGLE_TOP`) must leave the screen as it is (#145 SEC-1).

Until the web client serves a page at `/invite` (#27), a link opened where the app isn't installed shows an error page from the server; the code can be pasted into the app instead. A link that reaches the app but is for another server is refused as "not for this server".

## Smoke test with the dev CLI (#79)
Checks that a client can register against the deployed server, with a **test account** that is deleted at the end. Until then it is a full member: it can create invites, and later it can be found as a contact (threat model, "Dev CLI test keys"). The CLI only talks to a real origin with `--remote-test-account` and a `cli_` username.

1. On the VM, create an invite as above.
2. On your machine (Linux, or Git Bash on Windows), from the repo root, in **one shell session** (step 3 needs `$state`; `echo "$state"` and note it if the window might close). The state dir holds the test account's unprotected key, so keep it under your user profile (`mktemp -d` does, also in Git Bash):
   ```bash
   ./gradlew :cli:installDist
   cli=cli/build/install/gifitup-cli/bin/gifitup-cli
   server=https://gifitup.<domain>   # exactly GIFITUP_PUBLIC_URL
   state=$(mktemp -d)
   read -rs invite                   # paste the link: not echoed, not in the shell history
   printf '%s\n' "$invite" | "$cli" register --server "$server" --state-dir "$state" --username cli_smoke --remote-test-account
   unset invite
   "$cli" login --server "$server" --state-dir "$state" --remote-test-account
   "$cli" devices --server "$server" --state-dir "$state" --remote-test-account
   ```
   Expect `registered cli_smoke as device …`, `logged in as device …` and one device line. Every command logs in and out again; the server allows 10 logins per device per hour.

   If `register` fails, the invite usually stays valid until it expires (24 h; there is no revoke command), so retry with the **same** invite instead of creating another one. A failed `register` that may have reached the server is resumed from the same state dir, as its message says.
3. **Always**, also after a failure: on the VM delete the account (exit 1 just means it was never created), then delete the state dir on your machine:
   ```bash
   docker compose --profile admin run --rm admin delete-account cli_smoke
   ```
   ```bash
   rm -rf "$state"
   ```

## Rollback or redeploy
- **Automatic:** a failed health check restores the previous commit and image.
- **By hand:** *Actions → Deploy → Run workflow* on `main`, with `commit` set to the full SHA of an earlier deployed commit. Each deploy run's summary lists its commit and digest. The run skips the build, looks up that commit's image, verifies its provenance, and deploys it after your approval.
- **Database:** rolling back the image does **not** roll back the schema. So migrations are **expand-only**: the previous release must keep working against the new schema (ADR-0008). A destructive change (dropping or renaming a column, a new NOT NULL without a default) needs a two-release plan.
- **Owner runbook: remove a single device now** (e.g. a stolen phone, until device management exists, #72; #12 round-0 SEC-11). Whole accounts: use the admin CLI above. On the VM, as `deploy`, from `/opt/gifitup/deploy`:
  ```bash
  docker compose exec postgres psql -U gifitup -d gifitup -c "DELETE FROM gifitup.devices WHERE id = '<device id>'"
  ```
  This also deletes the device's sessions (cascade). Don't paste usernames or ids into shared logs.

## One-time setup (owner)
Secrets live **only in the `production` environment**, never at repo level. Names: `DEPLOY_HOST`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`.

1. **Key pair** (on your own machine, no passphrase because CI uses it):
   ```bash
   ssh-keygen -t ed25519 -N "" -C gifitup-ci -f gifitup-ci
   ```
2. **Restrict the key on the VM.** Append one line to `/opt/gifitup/.ssh/authorized_keys`: owned by `deploy`, `.ssh` mode 700, the file mode 600:
   ```
   restrict,command="/opt/gifitup/deploy/deploy.sh" ssh-ed25519 AAAA… gifitup-ci
   ```
   `restrict` turns off port, agent and X11 forwarding and the PTY. `command=` ignores whatever the client asks to run and passes it to the script as `SSH_ORIGINAL_COMMAND`.
3. **Known hosts:** run `ssh-keyscan -t ed25519 <host>` from your machine. **Compare the fingerprint** with the one on the VM (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`) before trusting it.
   - **On Windows,** the OpenSSH bundled with Windows may fail with "no matching key exchange method" if the VM's sshd only allows post-quantum key exchange such as `sntrup761x25519`, which older Windows builds lack. Check on the VM with `sudo sshd -T | grep kexalgorithms`. Alternatives:
     - **Git for Windows' copy:** `& "C:\Program Files\Git\usr\bin\ssh-keyscan.exe" -t ed25519 <host>` (PowerShell)
     - **Build the line on the VM**, which also skips the fingerprint comparison, since it's read from the host key itself:
       ```bash
       echo "<host> $(cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)"
       ```
       Here `<host>` must be exactly the value you store in `DEPLOY_HOST`.
4. **Environment:** *Settings → Environments → New environment* `production`:
   - **Required reviewers:** you. Leave **"Prevent self-review" off**, so you can approve your own deploys.
   - **Turn off "Allow administrators to bypass configured protection rules".** Otherwise admin tokens, including the one the agent uses, can skip the approval.
   - **Deployment branches and tags:** selected branches, `main` only.
   - **Environment secrets:**
     - `DEPLOY_HOST`: the VM's hostname or IP
     - `DEPLOY_SSH_KEY`: the private key file's contents; then delete the local private key or keep it offline
     - `DEPLOY_KNOWN_HOSTS`: the line from step 3
5. **VM checkout and `.env`** (once, after this PR is merged). A VM set up for #6 has a checkout without `deploy.sh`, so the forced command would fail:
   ```bash
   sudo -iu deploy git -C /opt/gifitup/repo pull --ff-only
   ls -l /opt/gifitup/deploy/deploy.sh          # must exist and be executable
   ```
   Then add `GIFITUP_PUBLIC_URL=https://gifitup.<domain>` to `/opt/gifitup/deploy/.env` (see `deploy/.env.example`). Since #12 the server reads it too, as the only origin device signatures are checked against (ADR-0009), so it must be canonical: lowercase, no trailing slash, no `:443`. Otherwise the server refuses to start and the deploy rolls back. The `GIFITUP_SERVER_IMAGE` line is managed by `deploy.sh`. From now on, deploys keep the checkout up to date (detached at the deployed commit).
6. **Make the package public before approving the first deploy.** New GHCR packages start private. Once the first run has pushed the image (the deploy job then waits for approval), go to *Packages → gifitup-server → Package settings → Change visibility → Public* (owner decision, #7), **then** approve. The provenance check, the rollback lookup and the VM's pull all read the registry without credentials. The image contains only code that is already public.

## Notes
- **Hostname:** it's kept out of the repo as hygiene, but it **isn't secret**. Every TLS certificate shows up in public Certificate Transparency logs. `DEPLOY_HOST` is masked in Actions logs, and `deploy.sh` never prints the URL.
- **Concurrency:** only the deploy job is serialised (`deploy-production`), so every `main` commit still gets an image, and with it a rollback target. GitHub keeps one *pending* deploy per group: a newer run replaces an older one that is still waiting for approval. To skip a deploy, **reject** it instead of leaving it pending. Builds of quick successive merges can finish out of order, so **check the commit in the approval request**: if it isn't the newest on `main`, reject it and redeploy the newest (*Run workflow* with `commit`). On the VM, `flock` serialises `deploy.sh`.
- **What the VM doesn't check:** the VM doesn't verify provenance; CI does. Whoever holds the CI SSH key can deploy any image in the package, or an older commit's `deploy.sh` (commits from before #7 are refused). Guard the key like any deploy secret, and rotate it if in doubt (replace the `authorized_keys` line and the secret).
- **First deploy:** with no earlier deploy, a failed health check stops `server` instead of rolling back.
