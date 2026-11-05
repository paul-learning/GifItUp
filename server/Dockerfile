# GifItUp server runtime image (#7, docs/ops/deploy.md). Built by .github/workflows/deploy.yml from the output of
# `./gradlew :server:installDist`. The Java build runs in the workflow's verified Gradle step (dependency
# verification, locks, wrapper checksum), not inside Docker, so this is a single runtime stage.
# Distroless: no shell, no package manager, non-root by default (compose additionally pins UID 10001).
FROM gcr.io/distroless/java21-debian13:nonroot@sha256:b22857e939bd95abd69d9d747f0421a274f9f70855ecfc335f7d97f6eeef578d

ARG REVISION=unknown
# The web image that belongs to this release (#27): "<repo>@sha256:<digest>", or empty. deploy.sh reads it from the verified
# server image, so one attested digest names both images and the SSH call stays "<commit> <digest>".
ARG WEB_IMAGE=
LABEL org.opencontainers.image.source="https://github.com/paul-learning/GifItUp" \
      org.opencontainers.image.revision="${REVISION}" \
      app.gifitup.web-image="${WEB_IMAGE}" \
      org.opencontainers.image.description="GifItUp server (untrusted relay for MLS ciphertext)" \
      org.opencontainers.image.licenses="MIT"

COPY server/build/install/server/lib/ /app/lib/
# Ktor resolves the working directory at startup. The base image's /home/nonroot is private to UID 65532, but
# compose runs as 10001, so use the world-readable /app instead.
WORKDIR /app

USER 65532:65532
# A hit on the compose memory limit ends the JVM cleanly, so Docker restarts it instead of leaving it half-dead.
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "app.gifitup.server.ApplicationKt"]
