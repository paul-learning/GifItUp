# The web app's static files, served to browsers (#27). Built from the output of
# `./gradlew :composeApp:wasmJsBrowserDistribution -Pgifitup.defaultOrigin=...`: by CI for its edge test and by
# .github/workflows/deploy.yml, which pushes it and names it in the server image's label (deploy/deploy.sh).
# It is a plain file server on the compose network: the VM's Caddy reverse-proxies to it and adds every security header
# (deploy/caddy/gifitup.caddy), and nothing from the host is mounted into it. The Caddy base is pinned by digest.
FROM caddy:2.11.7-alpine@sha256:e70b164da81ef698b3ad19af799ea6086ce6d9e48ed4079eb9fe26f6fbf14ee5

ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/paul-learning/GifItUp" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.description="GifItUp web app (static files)" \
      org.opencontainers.image.licenses="MIT"

# The official binary carries a file capability (bind to low ports). Compose runs this container with every capability
# dropped and no-new-privileges, where exec of such a binary fails; a plain copy has no file capability and the server
# listens on 8080 anyway.
RUN cp /usr/bin/caddy /tmp/caddy && rm /usr/bin/caddy && mv /tmp/caddy /usr/bin/caddy && chmod 755 /usr/bin/caddy

COPY deploy/web/Caddyfile /etc/caddy/Caddyfile
COPY composeApp/build/dist/wasmJs/productionExecutable/ /srv/
