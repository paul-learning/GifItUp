# GifItUp server runtime image (#7, docs/ops/deploy.md). Built by .github/workflows/deploy.yml from the output of
# `./gradlew :server:installDist`. The Java build runs in the workflow's verified Gradle step (dependency
# verification, locks, wrapper checksum), not inside Docker, so this is a single runtime stage.
# Distroless: no shell, no package manager, non-root by default (compose additionally pins UID 10001).
FROM gcr.io/distroless/java21-debian13:nonroot@sha256:a2aeb5a7a714cfe928b31fdf67b2d23f503ea6b9612705af062de22e0c50ba77

ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/paul-learning/GifItUp" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.description="GifItUp server (untrusted relay for MLS ciphertext)" \
      org.opencontainers.image.licenses="MIT"

COPY server/build/install/server/lib/ /app/lib/
# Ktor resolves the working directory at startup. The base image's /home/nonroot is private to UID 65532, but
# compose runs as 10001, so use the world-readable /app instead.
WORKDIR /app

USER 65532:65532
# A hit on the compose memory limit ends the JVM cleanly, so Docker restarts it instead of leaving it half-dead.
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "app.gifitup.server.ApplicationKt"]
