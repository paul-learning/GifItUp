# deploy

Deployment of the backend to the owner's shared Hetzner VM (ADR-0005). Setup and hardening: [`docs/ops/vm-setup.md`](../docs/ops/vm-setup.md).

- `docker-compose.yml`: Compose project `gifitup` (Ktor server + its own PostgreSQL). TLS comes from the VM's existing Caddy, not from this project.
- `caddy/gifitup.caddy`: site block the owner adds to that Caddy's Caddyfile.
- `backup/`: nightly `pg_dump` script and systemd units.
- `.env.example`: copied to `.env` on the VM.
- `deploy.sh`: forced command of the CI deploy key; see [`docs/ops/deploy.md`](../docs/ops/deploy.md).

Never commit secrets, hostnames of private infrastructure or `.env` files here; they come from GitHub secrets or the VM (`secrets/` and `.env` are gitignored).
