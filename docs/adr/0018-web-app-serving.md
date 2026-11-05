# ADR-0018: Serving the web app

- **Status:** accepted (built: the edge in #249, the image and its deploy in #250)
- **Date:** 2026-11-04
- **Issue/PR:** #27, #249. Round-0 plan review by the security reviewer (SEC-5, SEC-7, SEC-8, SEC-9). Amends ADR-0005's site block (the hostname is public from the friends alpha on, and the site block now routes the web app).

## Context
The friends alpha needs the web app on the same domain as the API (owner decision, 2026-11-04: "Same server and domain (Recommended)"). The server's protocol-version check and deny-by-default auth sit in front of every request, and a page load carries neither the header nor a session. The VM's Caddy is shared (ADR-0005).

## Options considered
1. **The Ktor server serves the files.** Needs exemptions in the two gates that protect every other route. Rejected.
2. **Files mounted from the host into the VM's Caddy.** A new write channel into the shared Caddy's file system. Rejected (SEC-5).
3. **A static-file container (`gifitup-web`), the VM's Caddy reverse-proxies to it.** Chosen.

## Decision
- The web app is its own image (`deploy/web/`, pinned Caddy base, no mounts, read-only, no capabilities), built from the production bundle by the deploy workflow. Its digest travels as a label (`app.gifitup.web-image`) of the **server image**, which is attested and deployed by digest as before: one verified digest names both images, `deploy.sh` accepts only a digest of our own web repository from the label, and the SSH call keeps its `<commit> <digest>` form (so the previously deployed `deploy.sh` still accepts it). Without the repository variable `GIFITUP_PUBLIC_URL` no web image is built. The web image's own attestation is pushed to the registry but not verified separately: the VM trusts the digest in the label, whose carrier (the server image) CI verifies against the workflow, the commit and `main`.
- The VM's Caddy routes the server's route prefixes (`@api`, kept complete by `scripts/check-repo-hygiene.sh`) to `gifitup-server` and everything else to `gifitup-web`. It sets the security headers and **one** Content-Security-Policy per answer, each handle its own: API `default-src 'none'; frame-ancestors 'none'`; web as in the site block (self, `'wasm-unsafe-eval'`, no inline script, one inline style element allowed by its hash (the Compose runtime's own), `style-src-attr 'unsafe-inline'` because the Compose Web runtime sets style attributes (found by the CI browser test), no eval, images from `blob:` and the GIF provider's hosts, connections to its own host and the provider's API).
- The host name is not in the repo as hygiene only; nothing relies on it being secret.
- The production bundle is built only with an explicit canonical origin, the repository variable `GIFITUP_PUBLIC_URL`. It must be the same value as the server's `GIFITUP_PUBLIC_URL` on the VM (ADR-0009), but nothing can compare the two (the workflow doesn't see the VM's `.env`, `deploy.sh` doesn't see the bundle): the owner sets both from one value, and a mismatch shows as the web app failing to sign in.
- CI opens the real bundle behind the real site block in headless Chrome; `scripts/check-web-headers.sh` runs the same header checks against the real host.

## Consequences
- A second container on the shared `proxy` network (`gifitup-web`): another container can claim its alias and serve code to web users. Recorded in the threat model; open until the owner decides (accept, or a dedicated network).
- Order on the VM: the web image and container first (the first deploy with the repository variable set), then the new site block; a reload with the new block before `gifitup-web` runs answers `502` for every non-API path.
- Trust chain of a web release: CI verifies the **server** image's attestation (workflow, commit, `main`); its label names the web image by digest, which `deploy.sh` accepts only for our own web repository; the web image itself is content-addressed. The `gifitup-web` package is public (like the server's), so the VM pulls without credentials. A rollback to a release without the label stops the web container: the app is then not served until a newer release has one.
- No new server-visible data.
