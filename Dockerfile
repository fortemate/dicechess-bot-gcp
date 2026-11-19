# Runtime-only image. Build target/dicechess-bot-gcp.jar with `sbt assembly` first;
# the Docker build needs no package-registry credentials.
FROM eclipse-temurin:25-jre-noble@sha256:3c6d7b40c2e78d4a8f4fe00af8165c706b34faf672dc21ff0924e583559ec6c2

LABEL org.opencontainers.image.title="Dice Chess Bot GCP" \
      org.opencontainers.image.description="Engine-backed Dice Chess webhook bot for Cloud Run" \
      org.opencontainers.image.url="https://github.com/fortemate/dicechess-bot-gcp" \
      org.opencontainers.image.source="https://github.com/fortemate/dicechess-bot-gcp" \
      org.opencontainers.image.documentation="https://github.com/fortemate/dicechess-bot-gcp#readme" \
      org.opencontainers.image.vendor="Fortemate" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.authors="Jegors Čemisovs" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25-jre-noble"

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app app
WORKDIR /app
COPY --chown=app:app target/dicechess-bot-gcp.jar /app/app.jar
USER app

# Cloud Run routes requests to $PORT (default 8080); Main reads it. EXPOSE is documentation only.
ENV PORT=8080
EXPOSE 8080

# Preserve the existing JVM heap policy and application entrypoint.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
