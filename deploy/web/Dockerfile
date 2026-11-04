# The web app's static files, served to browsers (#27). Built by .github/workflows/deploy.yml from the output of
# `./gradlew :composeApp:wasmJsBrowserDistribution -Pgifitup.defaultOrigin=...`, like the server image (deploy/README.md).
# It is a plain file server on the compose network: the VM's Caddy reverse-proxies to it and adds every security header
# (deploy/caddy/gifitup.caddy), and nothing from the host is mounted into it. The Caddy base is pinned by digest.
FROM caddy:2.11.7-alpine@sha256:e70b164da81ef698b3ad19af799ea6086ce6d9e48ed4079eb9fe26f6fbf14ee5

ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/paul-learning/GifItUp" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.description="GifItUp web app (static files)" \
      org.opencontainers.image.licenses="MIT"

COPY deploy/web/Caddyfile /etc/caddy/Caddyfile
COPY composeApp/build/dist/wasmJs/productionExecutable/ /srv/
