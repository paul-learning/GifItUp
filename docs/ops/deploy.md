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

**Agents never dispatch, re-run or approve deploys** (CLAUDE.md hard rule; deny list in `.claude/settings.json`).

## Rollback or redeploy
- **Automatic:** a failed health check restores the previous commit and image.
- **By hand:** *Actions → Deploy → Run workflow* on `main`, with `commit` set to the full SHA of an earlier deployed commit. Each deploy run's summary lists its commit and digest. The run skips the build, looks up that commit's image, verifies its provenance, and deploys it after your approval.
- **Database:** rolling back the image does **not** roll back the schema. So migrations are **expand-only**: the previous release must keep working against the new schema (ADR-0008). A destructive change (dropping or renaming a column, a new NOT NULL without a default) needs a two-release plan.
- **Owner runbook: remove a device or account now** (e.g. a stolen phone, until device management exists; #12 round-0 SEC-11). On the VM, as `deploy`, from `/opt/gifitup/deploy`:
  ```bash
  docker compose exec postgres psql -U gifitup -d gifitup -c "DELETE FROM gifitup.devices WHERE id = '<device id>'"
  ```
  This also deletes the device's sessions (cascade). Deleting from `gifitup.accounts` removes all of an account's devices. Don't paste usernames or ids into shared logs.

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
