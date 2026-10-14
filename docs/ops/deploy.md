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
   - writes the digest into `.env` and restarts `server`
   - checks that the container runs that image and that `https://…/health` returns 200
   - if the check fails, it **rolls back automatically** to the last good commit and digest

   It prints only status lines, because Actions logs are public. Details go to the VM journal: `journalctl -t gifitup-deploy`.

**What approving a deploy means:** that commit's `docker-compose.yml` and `deploy.sh` run on the shared VM with the `deploy` user's rights. Those are root-equivalent through Docker (ADR-0005). Approve only commits you merged yourself.

**Agents never dispatch, re-run or approve deploys** (CLAUDE.md hard rule; deny list in `.claude/settings.json`).

## Rollback or redeploy
- **Automatic:** a failed health check restores the previous commit and image.
- **By hand:** *Actions → Deploy → Run workflow* on `main`, with `commit` set to the full SHA of an earlier deployed commit. Each deploy run's summary lists its commit and digest. The run skips the build, looks up that commit's image, verifies its provenance, and deploys it after your approval.
- **Database:** once migrations exist (#12), rolling back the image does **not** roll back the schema. Migrations must stay compatible with the previous release, or come with a documented manual rollback.

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
4. **Environment:** *Settings → Environments → New environment* `production`:
   - **Required reviewers:** you. Leave **"Prevent self-review" off**, so you can approve your own deploys.
   - **Turn off "Allow administrators to bypass configured protection rules".** Otherwise admin tokens, including the one the agent uses, can skip the approval.
   - **Deployment branches and tags:** selected branches, `main` only.
   - **Environment secrets:**
     - `DEPLOY_HOST`: the VM's hostname or IP
     - `DEPLOY_SSH_KEY`: the private key file's contents; then delete the local private key or keep it offline
     - `DEPLOY_KNOWN_HOSTS`: the line from step 3
5. **VM `.env`:** add `GIFITUP_PUBLIC_URL=https://gifitup.<domain>` (see `deploy/.env.example`). The `GIFITUP_SERVER_IMAGE` line is managed by `deploy.sh`.
6. **After the first push:** under *Packages → gifitup-server → Package settings*, make sure the package is **public** (owner decision, #7). The image contains only code that is already public. The VM pulls without credentials.

## Notes
- **Hostname:** it's kept out of the repo as hygiene, but it **isn't secret**. Every TLS certificate shows up in public Certificate Transparency logs. `DEPLOY_HOST` is masked in Actions logs, and `deploy.sh` never prints the URL.
- **Concurrency:** deploy runs queue (`deploy-production`, never cancelled). On the VM, `flock` serialises `deploy.sh`.
- **First deploy:** with no earlier deploy, a failed health check stops `server` instead of rolling back.
