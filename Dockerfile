# syntax=docker/dockerfile:1.7

# ---------- build stage -------------------------------------------------------
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /src

# Build files first, then sources. The Gradle home is a BuildKit cache mount,
# so dependencies are not downloaded again between builds.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY build-plugins ./build-plugins
COPY common ./common
COPY datasources ./datasources
COPY sql-portal ./sql-portal

# Which JDBC drivers to package: all, or a subset of sqlite,postgres.
ARG DATABASES=all
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain :sql-portal:deployment:installDist -Pdatabases=${DATABASES} \
    && cp -r sql-portal/deployment/build/install/script-portal /opt/script-portal \
    # Jars from the Gradle cache can be owner-only; the runtime user must read them.
    && chmod -R a+rX /opt/script-portal

# ---------- runtime stage -----------------------------------------------------
# distroless/java25: a JRE and CA certificates, no shell, runs as non-root.
# The Java version must match the build stage and `java` in gradle/libs.versions.toml.
FROM gcr.io/distroless/java25-debian13:nonroot AS runtime

ARG VERSION=dev
LABEL org.opencontainers.image.title="script-portal" \
      org.opencontainers.image.description="Config-driven SQL script portal with LDAP/OIDC, forms and charts (Kotlin/Ktor)" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app
COPY --from=builder /opt/script-portal/lib /app/lib

# The configuration is bundled in the application jar: config/default.conf plus
# config/env/$APP_ENV.conf. APP_ENV=local (the default here) DISABLES
# authentication and seeds an in-memory SQLite demo database, with text logs.
# In the cluster set APP_ENV=k8s, uat or prod (JSON logs), DATABASE_URL and the
# sign-in variables (see the README); an extra HOCON file can be mounted and
# named by SCRIPT_PORTAL_CONFIG. The application has no command line arguments.
# Timestamps with time zone are rendered in the JVM time zone (UTC here; set TZ to change).
ENV APP_ENV=local \
    SCRIPT_PORTAL_BIND=0.0.0.0:8080 \
    TZ=UTC

EXPOSE 8080
USER nonroot:nonroot

# Kubernetes probes: liveness GET /healthz, readiness GET /readyz.
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
            "-Dkotlin-logging.logStartupMessage=false", "-cp", "/app/lib/*", \
            "io.merkio.sqlportal.deployment.MainKt"]
