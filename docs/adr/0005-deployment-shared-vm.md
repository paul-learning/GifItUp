# ADR-0005: Deployment on the owner's shared VM behind the existing Caddy

- **Status:** accepted
- **Date:** 2026-10-14
- **Issue/PR:** #6

## Context
ADR-0001 puts the backend on the owner's Hetzner VM with Docker Compose and Caddy. The VM already runs other services of the owner (Ubuntu 24.04). An existing Caddy in its own Compose project owns ports 80/443 and terminates TLS for every hostname. Containers reach it through an external Docker network, `proxy`. GifItUp must fit in without changing those services (owner, 2026-10-14). The server is untrusted for content by design (ADR-0002), but it holds metadata and serves the web client later.

## Options considered
1. **Own Caddy in the GifItUp project.** Fully self-contained. Rejected: ports 80/443 are taken, and a second proxy would mean moving every other site.
2. **Join the existing Caddy (chosen).** One site block in its Caddyfile, plus the GifItUp server on the `proxy` network. No published ports, no changes to other services. Cons: the GifItUp repo doesn't control the proxy config (the owner pastes the block), and TLS is terminated by a proxy shared with other services.
3. **Separate VM.** Strongest isolation. Rejected for now: extra cost and admin work for a hobby project. Revisit if the shared host's risk becomes unacceptable.

Container isolation on the host:
- **Docker `userns-remap`:** daemon-wide. Enabling it would hide and force migration of every existing container, image and volume. Deferred to a possible later VM-wide migration.
- **Rootless Docker:** a separate per-user daemon, plus a sysctl for low ports. Rejected as too many moving parts.
- **Per-container hardening (chosen):** read-only root filesystem, `cap_drop: ALL`, `no-new-privileges`, memory and process limits, non-root server, log rotation.

## Decision
- GifItUp runs as its own Compose project `gifitup` (`deploy/docker-compose.yml`): `server` and its own Postgres 18 (digest-pinned).
- Only `server` joins the external `proxy` network, under the alias `gifitup-server`. Postgres is only on an `internal: true` network. No host ports are published.
- The existing Caddy gets the site block from `deploy/caddy/gifitup.caddy`: security headers, 1 MB body limit, and no access log (CLAUDE.md, security rule 4). The real hostname lives only on the VM.
- Secrets (the Postgres password) are files in `/opt/gifitup/deploy/secrets/` on the VM, passed in as Compose secrets. Nothing is committed.
- A `deploy` user owns `/opt/gifitup`. It's in the `docker` group, so CI can run `docker compose`. CI's SSH key is limited to one deploy script (#7).
- Backups: nightly `pg_dump` via a systemd timer, kept 14 days, on the VM only for now (owner decision). Revisit before the friends alpha (#27). The dumps are unencrypted, so a row deleted by a retention rule stays readable in the dumps taken before its deletion for about 15 to 16 days (and with no limit while the backup job fails) (threat model, *Known metadata*; SEC-M0-8, #55).
- The setup and hardening checklist is in `docs/ops/vm-setup.md`. The owner runs it; agents never touch the VM.

## Consequences
- **Shared trust base.** Anyone who controls the shared Caddy, the Docker daemon (`docker` group, Docker management UIs) or a container escape on this VM controls the GifItUp server. With the server, they get the metadata it sees and the web client code it will serve (see the threat model). Content stays protected by MLS as long as the clients aren't swapped. So the security of the owner's other services now matters for GifItUp.
- **The shared `proxy` network is a trust boundary we don't control.** Every container on it can call `gifitup-server:8080` directly, which skips Caddy's limits and lets it forge `X-Forwarded-For`. It can also register the same alias and get part of GifItUp's traffic after TLS. Or it can ARP-spoof the Caddy→server hop (plain HTTP, and Docker gives containers `CAP_NET_RAW` by default) and read or change it (SEC-M0-5, #55; the owner accepted this route separately on 2026-10-21, see the threat model's sign-offs). A dedicated Caddy↔GifItUp network would close this, but it would need a change in the owner's other project. The owner chose to keep `proxy` and accept the risk (2026-10-14 for the alias route, threat model "Shared VM"). Consequence for #12: the server enforces its own size and rate limits. (#12 tightened this: the server doesn't read `X-Forwarded-For` at all, SEC-M0-5.)
- `deploy` in the `docker` group is root-equivalent. That's accepted and documented, and limited by a restricted SSH key and no sudo.
- Proxy changes (new routes, websocket, web app in #27) are manual edits on the VM, documented next to the snippet.
- No TLS config in this repo. Certificates come from the existing Caddy (automatic HTTPS).
- Metadata: nothing new beyond the threat model's server-visible list. Caddy writes no access log for GifItUp. Its error log (stderr, Docker log rotation) can still contain a client IP when a request fails, e.g. while the server is down.
- Dependabot tracks the Postgres image digest (`docker-compose` ecosystem). The shared Caddy image belongs to the owner's other project.
