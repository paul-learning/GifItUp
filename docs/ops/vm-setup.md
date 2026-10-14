# VM setup (Hetzner, Ubuntu 24.04)

How GifItUp runs on the owner's VM, and the one-time setup. Design: [ADR-0005](../adr/0005-deployment-shared-vm.md). **The owner runs every command here; agents never touch the VM** (CLAUDE.md).

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
sudo ss -tulpn                        # what listens where (SSH, WireGuard, published container ports)
sudo ufw status verbose               # firewall state
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
- [ ] **Firewall (ufw): deny incoming, allow 22/80/443**, plus anything the VM already needs, such as the WireGuard UDP port from the pre-flight check. Allow these *before* enabling:
  ```bash
  sudo ufw default deny incoming && sudo ufw default allow outgoing
  sudo ufw allow 22/tcp && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw allow 443/udp
  # sudo ufw allow <wireguard-port>/udp   # if WireGuard runs on this VM
  sudo ufw enable
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
- CI's SSH key is restricted with `restrict,command="…"` to the single deploy script (set up in #7)

## 4. GifItUp Compose project
As `deploy` (`sudo -iu deploy`):
```bash
git clone --filter=blob:none --sparse https://github.com/paul-learning/GifItUp.git /opt/gifitup/repo
cd /opt/gifitup/repo && git sparse-checkout set deploy
ln -sfn /opt/gifitup/repo/deploy /opt/gifitup/deploy
cd /opt/gifitup/deploy
cp .env.example .env                                       # then set GIFITUP_SERVER_IMAGE (from #7)
install -d -m 0700 secrets
(umask 022; openssl rand -base64 32 | tr -d '\n' > secrets/postgres_password)
chmod 0444 secrets/postgres_password
```
The secret file is world-readable *inside* the `0700` directory on purpose. Compose bind-mounts it into the containers, where the Postgres entrypoint (and later the server, #12) reads it as a non-root user. On the host, only `deploy` (and root) can get into `secrets/`.

Start Postgres only, until the server image exists (#7):
```bash
docker compose up -d postgres && docker compose ps
```
After #7: `docker compose up -d`. Then check `docker compose ps`: postgres should be `healthy` and server `running`.

## 5. Caddy site block
In the existing Caddy project (outside this repo), append the contents of `deploy/caddy/gifitup.caddy` to its Caddyfile and replace `gifitup.example.com` with `gifitup.<domain>`. Then:
```bash
docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
curl -sI https://gifitup.<domain>/health      # after the server runs: 200 plus the security headers
```
Caddy reaches the server as `gifitup-server:8080` on the shared `proxy` network. Postgres is not on that network.

The block deliberately has **no access log**. Logs must not map users to IPs (CLAUDE.md, security rule 4). Caddy's error log can still include a client IP for failed requests (e.g. while the server is down); it stays in Docker's rotated container log. It also caps request bodies at 1 MB and sets an API-only CSP, which the web app (#27) will replace.

## 6. Backups
Nightly `pg_dump` (custom format) into `/var/backups/gifitup`, kept for 14 days, on the VM only for now (owner decision, 2026-10-14). A lost or compromised VM loses the backups too. That's acceptable while there's no real user data; revisit before the friends alpha (#27).
```bash
sudo cp /opt/gifitup/deploy/backup/gifitup-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now gifitup-backup.timer
sudo systemctl start gifitup-backup.service && journalctl -u gifitup-backup.service -n 5
```

**Restore test** (monthly, and after any Postgres major upgrade). Restore the newest dump into a throwaway container, never into the live database:
```bash
f=$(ls -t /var/backups/gifitup/gifitup-*.dump | head -1)
docker run -d --name gifitup-restore-test -e POSTGRES_PASSWORD=throwaway --network none \
  -v /var/backups/gifitup:/backups:ro postgres:18-alpine
sleep 5
docker exec gifitup-restore-test sh -c "createdb -U postgres restored && pg_restore -U postgres -d restored --no-owner /backups/$(basename "$f") && psql -U postgres -d restored -c '\dt'"
docker rm -f gifitup-restore-test
```
To restore for real: stop `server`, then `pg_restore --clean --if-exists` into the live database through `docker compose exec -T postgres`, and start `server` again.

## 7. Updating
- **Server:** the deploy workflow (#7) sets `GIFITUP_SERVER_IMAGE` and runs `docker compose pull && docker compose up -d`.
- **Postgres:** Dependabot proposes new digests for `postgres:18-alpine`. Minor updates: `docker compose pull postgres && docker compose up -d postgres`. Major upgrades (19+) need a dump and restore: take a backup, run the restore test, then switch.
- **Repo files:** `git -C /opt/gifitup/repo pull --ff-only`.
