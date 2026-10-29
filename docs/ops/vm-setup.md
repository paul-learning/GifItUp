# VM setup (Hetzner, Ubuntu 24.04)

How GifItUp runs on the owner's VM, and the one-time setup. Design: [ADR-0005](../adr/0005-deployment-shared-vm.md). **The owner runs every command here; agents never touch the VM** (AGENTS.md).

The VM is shared with other services. An existing Caddy (its own Compose project) owns ports 80/443 and terminates TLS for all hostnames. GifItUp adds:
- a Compose project `gifitup` in `/opt/gifitup/deploy` ([`deploy/docker-compose.yml`](../../deploy/docker-compose.yml)): `server` + its own `postgres`. No published host ports.
- one site block in the existing Caddyfile ([`deploy/caddy/gifitup.caddy`](../../deploy/caddy/gifitup.caddy)).

```
internet ──443──> existing Caddy ──(docker network "proxy")──> gifitup-server:8080 ──(network "backend", internal)──> postgres:5432
```

Placeholders: `gifitup.<domain>` is the real hostname. It's kept out of the repo, which is public.

## 1. Pre-flight checks
Change nothing yet; just look at the current state.
```bash
lsb_release -ds                       # Ubuntu 24.04.x LTS
sudo ss -tulpn                        # what listens where: SSH, VPN (WireGuard/OpenVPN), published container ports
if command -v ufw >/dev/null; then sudo ufw status verbose; else echo "ufw is not installed"; fi
docker network inspect proxy >/dev/null && echo "proxy network exists"
docker compose version
```

## 2. Host hardening checklist
Apply what isn't already in place. Keep a second SSH session open while changing SSH or the firewall.

- [ ] **SSH: keys only, no root login.** Ubuntu 24.04 reads drop-ins from `/etc/ssh/sshd_config.d/`:
  ```bash
  sudo tee /etc/ssh/sshd_config.d/10-hardening.conf >/dev/null <<'EOF'
  PermitRootLogin no
  PasswordAuthentication no
  KbdInteractiveAuthentication no
  PubkeyAuthentication yes
  X11Forwarding no
  MaxAuthTries 3
  EOF
  sudo sshd -t && sudo systemctl reload ssh
  ```
  Check with `sudo sshd -T | grep -E 'permitrootlogin|passwordauthentication'`.
- [ ] **Firewall (ufw): deny incoming, allow 22/80/443**, plus every host service from the pre-flight `ss -tulpn` that must stay reachable, such as a VPN port. ufw isn't always installed (`sudo apt install ufw`). It may already have saved rules: check `sudo ufw show added`. Allow everything needed *before* enabling, or you cut off running services. If ufw is already active, run the `allow` lines first: `default deny incoming` takes effect immediately.
  ```bash
  sudo ufw default deny incoming && sudo ufw default allow outgoing
  sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw allow 443/udp
  # sudo ufw allow <vpn-port>/udp          # e.g. WireGuard or OpenVPN on this host
  sudo ufw show added                      # review before enabling
  sudo ufw enable && sudo ufw status verbose
  ```
  **Docker caveat:** ports a container publishes (`ports: "X:X"`) bypass ufw. Docker writes its own iptables rules. Every published port is reachable from the internet whatever ufw says. GifItUp publishes none. For other services, publish only through Caddy or bind to `127.0.0.1:`.
- [ ] **Automatic security updates:** `unattended-upgrades` is installed on Ubuntu 24.04 by default. Check that `/etc/apt/apt.conf.d/20auto-upgrades` has `APT::Periodic::Unattended-Upgrade "1";`, or run `sudo dpkg-reconfigure -plow unattended-upgrades`. Automatic reboots stay off because the VM is shared. When `/var/run/reboot-required` exists, reboot in a quiet moment.
- [ ] **fail2ban for SSH:** with key-only auth it mostly cuts log noise, but it's cheap:
  ```bash
  sudo apt install fail2ban
  sudo tee /etc/fail2ban/jail.d/sshd.local >/dev/null <<'EOF'
  [sshd]
  enabled = true
  backend = systemd
  maxretry = 5
  bantime = 1h
  EOF
  sudo systemctl enable --now fail2ban && sudo fail2ban-client status sshd
  ```
- [ ] **Container isolation:** per-container hardening in `deploy/docker-compose.yml`:
  - read-only root filesystem
  - all capabilities dropped (Postgres gets back only the five its entrypoint needs)
  - `no-new-privileges`
  - memory and process limits
  - log rotation
  - the server image runs as non-root (#7)

  *Evaluated, not used for now:* Docker `userns-remap` maps container root to an unprivileged host UID range. But it's daemon-wide: enabling it switches Docker to separate storage, so every existing container, image and volume on this shared VM would have to be migrated. Possible later step, done for the whole VM at once. Rootless Docker was rejected: it needs a separate per-user daemon and a sysctl for ports below 1024.

## 3. The `deploy` user
CI (#7) deploys as `deploy`, and the backup timer runs as `deploy`.
```bash
sudo adduser --disabled-password --gecos "" --home /opt/gifitup deploy
sudo usermod -aG docker deploy
sudo install -d -o deploy -g deploy -m 0750 /opt/gifitup
sudo install -d -o deploy -g deploy -m 0700 /var/backups/gifitup
```
**Membership in `docker` is root-equivalent.** Anyone who can run `docker` can mount the host filesystem. This is the accepted cost of "deploy = `docker compose pull && up`" (see the threat model). So keep the `deploy` user narrow:
- no password and no sudo
- CI's SSH key is restricted with `restrict,command="…"` to the single deploy script (see [deploy.md](deploy.md))

## 4. GifItUp Compose project
As `deploy` (`sudo -iu deploy`):
```bash
git clone --filter=blob:none --sparse https://github.com/paul-learning/GifItUp.git /opt/gifitup/repo
cd /opt/gifitup/repo && git sparse-checkout set deploy
ln -sfn /opt/gifitup/repo/deploy /opt/gifitup/deploy
cd /opt/gifitup/deploy
cp .env.example .env                                       # then set GIFITUP_PUBLIC_URL (see deploy.md)
install -d -m 0700 secrets
(umask 022; openssl rand -base64 32 | tr -d '\n' > secrets/postgres_password)
(umask 022; openssl rand -base64 32 | tr -d '\n' > secrets/postgres_app_password)
chmod 0444 secrets/postgres_password secrets/postgres_app_password
```
The secret files are world-readable *inside* the `0700` directory on purpose. Compose bind-mounts them into the containers, where the Postgres entrypoint, the server and the `migrate` container read them as non-root users. Compose without swarm ignores per-secret modes, so the host mode decides. On the host, only `deploy` (and root) can get into `secrets/`. `deploy.sh` refuses to deploy unless `secrets/` is mode `0700`, owned by `deploy` and not a symlink, and both files are regular files (not symlinks), non-empty and world-readable.

- `postgres_password`: the database superuser. Only Postgres and the one-shot `migrate` container get it (ADR-0008).
- `postgres_app_password`: the server's least-privilege role `gifitup_app`. `migrate` sets the role's password from this file on every deploy. To rotate it, write a new file and redeploy.

**Existing VM (set up before #12):** create `secrets/postgres_app_password` with the second and third commands above **before approving the first deploy that contains #12's database change**. Nothing else changes: the existing superuser and data stay as they are, and `migrate` creates the roles and schema on the next deploy.

Start Postgres only, until the server image exists (#7):
```bash
docker compose up -d postgres && docker compose ps
```
The server is started by the first deploy ([deploy.md](deploy.md)). Then check `docker compose ps`: postgres should be `healthy` and server `running`.

## 5. Caddy site block
In the existing Caddy project (outside this repo), append the contents of `deploy/caddy/gifitup.caddy` to its Caddyfile and replace `gifitup.example.com` with `gifitup.<domain>`. If the Caddyfile is a single bind-mounted file, append to it or edit it in place: editors that replace the file (new inode) leave the container with the old version, and then only a container restart helps.

Then, from the Caddy project's directory, do **all three steps**. Editing the file alone changes nothing:
- [ ] **Validate:** `docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile`
- [ ] **Reload:** `docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile`. Caddy only gets a certificate for the new host after this.
- [ ] **Check:** `curl -sI https://gifitup.<domain>/health`. Once the server runs, you get `200` plus the security headers.

Reading the result of the check:
- **TLS error** (e.g. `tlsv1 alert internal error`): Caddy has no certificate for the host. Possible reasons:
  - the reload didn't happen or failed
  - the container still sees the old file
  - the certificate is still being issued (wait a minute)
  - issuance failed because DNS doesn't point at the VM or port 80 is blocked

  `docker compose logs caddy` tells you which.
- **`502`:** Caddy serves the host, but the server isn't reachable. It isn't running yet (before the first deploy) or isn't on the `proxy` network.
- **`200`:** the server answers through Caddy.

Caddy reaches the server as `gifitup-server:8080` on the shared `proxy` network. Postgres is not on that network. Every other container on `proxy` can reach the server too (accepted risk, see ADR-0005).

Afterwards, make sure no access log is written for GifItUp. Check that the shared Caddyfile has no global or imported `log` block that covers this host, and that no new file shows up in Caddy's log directory after a request. The snippet's `log_skip` is a second line of defence.

The block deliberately has **no access log**. Logs must not map users to IPs (AGENTS.md, security rule 4). Caddy's error log can still include a client IP for failed requests (e.g. while the server is down); it stays in Docker's rotated container log. It also caps request bodies at 1 MB and sets an API-only CSP, which the web app (#27) will replace.

## 6. Backups
Nightly `pg_dump` (custom format) into `/var/backups/gifitup`, kept for 14 days, on the VM only for now (owner decision, 2026-10-14). A lost or compromised VM loses the backups too. That's acceptable while there's no real user data; revisit before the friends alpha (#27).
```bash
sudo cp /opt/gifitup/deploy/backup/gifitup-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now gifitup-backup.timer
sudo systemctl start gifitup-backup.service && journalctl -u gifitup-backup.service -n 5
```
**Check that backups keep running.** A failed run shows up only in the journal. Check at least weekly, and wire this into the VM's monitoring if it has one (e.g. a push-based uptime monitor):
```bash
systemctl list-timers gifitup-backup.timer
find /var/backups/gifitup -name 'gifitup-*.dump' -mtime -2 | grep -q . && echo "recent backup OK" || echo "NO BACKUP IN 2 DAYS"
```

**Restore test** (monthly, and after any Postgres major upgrade). Restore the newest dump into a throwaway container, never into the live database:
```bash
f=$(ls -t /var/backups/gifitup/gifitup-*.dump | head -1)
img=$(grep -o 'postgres:18-alpine@sha256:[0-9a-f]*' /opt/gifitup/deploy/docker-compose.yml)   # same pinned image
docker run -d --name gifitup-restore-test -e POSTGRES_PASSWORD=throwaway --network none \
  -v /var/backups/gifitup:/backups:ro "$img"
until docker exec gifitup-restore-test pg_isready -U postgres -q; do sleep 1; done
docker exec gifitup-restore-test sh -c "createdb -U postgres restored && pg_restore -U postgres -d restored --no-owner /backups/$(basename "$f") && psql -U postgres -d restored -c '\dt'"
docker rm -f gifitup-restore-test
```
To restore for real: stop `server`, then `pg_restore --clean --if-exists` into the live database through `docker compose exec -T postgres`, and start `server` again.

## 7. Updating
- **Server and repo files:** only through the deploy workflow ([deploy.md](deploy.md)). Each deploy checks out the approved commit (detached) and runs its image.
- **Postgres:** Dependabot proposes new digests for `postgres:18-alpine`. Minor updates: `docker compose pull postgres && docker compose up -d postgres`. Major upgrades (19+) need a dump and restore: take a backup, run the restore test, then switch.
