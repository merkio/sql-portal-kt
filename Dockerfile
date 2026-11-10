# syntax=docker/dockerfile:1.7

# ---------- build stage -------------------------------------------------------
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /src

# Build files first, then sources. The Gradle home is a BuildKit cache mount,
# so dependencies are not downloaded again between builds.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY build-logic ./build-logic
COPY common ./common
COPY datasources ./datasources
COPY sql-portal ./sql-portal
COPY config ./config

# Which JDBC drivers to package: all, or a subset of sqlite,postgres.
ARG DATABASES=all
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain :sql-portal:deployment:installDist -Pdatabases=${DATABASES} \
    && cp -r sql-portal/deployment/build/install/script-portal /opt/script-portal \
    # Jars from the Gradle cache can be owner-only; the runtime user must read them.
    && chmod -R a+rX /opt/script-portal

# ---------- runtime stage -----------------------------------------------------
# distroless/java21: a JRE and CA certificates, no shell, runs as non-root.
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime

ARG VERSION=dev
LABEL org.opencontainers.image.title="script-portal" \
      org.opencontainers.image.description="Config-driven SQL script portal with LDAP/OIDC, forms and charts (Kotlin/Ktor)" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app
COPY --from=builder /opt/script-portal/lib /app/lib
COPY config/ /app/config/

# The bundled demo config DISABLES authentication and uses an in-memory SQLite
# database. Mount your own configuration and set SCRIPT_PORTAL_CONFIG for real use.
# Timestamps with time zone are rendered in the JVM time zone (UTC here; set TZ to change).
ENV SCRIPT_PORTAL_CONFIG=/app/config/config.demo.conf \
    SCRIPT_PORTAL_BIND=0.0.0.0:8080 \
    TZ=UTC

EXPOSE 8080
USER nonroot:nonroot

# Distroless has no curl: the application ships its own probe (GET /readyz),
# started as a small, short-lived JVM.
HEALTHCHECK --interval=15s --timeout=10s --start-period=20s --retries=3 \
    CMD ["/usr/bin/java", "-Xmx32m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-cp", "/app/lib/*", \
         "io.merkio.sqlportal.deployment.MainKt", "healthcheck", "--addr", "127.0.0.1:8080", "--path", "/readyz"]

ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", \
            "io.merkio.sqlportal.deployment.MainKt"]
CMD ["serve"]
