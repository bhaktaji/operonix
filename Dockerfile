# Operonix — single image for Cloud Run.
#
# One container serves both the built React SPA and the Spring Boot API on the same origin, which
# keeps the existing architecture intact (PostgreSQL stays the datastore, the SPA calls /api/v1
# relatively, no CORS configuration is needed in production).
#
#   docker build -t operonix:<tag> .
#   docker run --rm -p 8080:8080 \
#     -e OPERONIX_DB_URL=jdbc:postgresql://host.docker.internal:5432/operonix \
#     -e OPERONIX_DB_USER=operonix -e OPERONIX_DB_PASSWORD=operonix \
#     -e OPERONIX_JWT_SECRET=local-testing-secret-at-least-32-bytes-long \
#     operonix:<tag>
#
# No syntax directive, cache mounts, or build secrets are used so the file works with a plain
# `docker build` as well as BuildKit on Cloud Build.
#
# This is a deployment-readiness artifact: it has not been executed in this repository's
# environment (no container runtime available). See docs/GOOGLE_CLOUD_DEPLOYMENT.md.

# ---------------------------------------------------------------- stage 1: SPA build
FROM node:22-alpine AS web
WORKDIR /build/frontend
# Dependency layer first so source edits do not re-resolve packages. The lockfile is preserved
# (npm ci, not npm install) so the image builds the exact versions CI verified.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# Produces frontend/dist (tsc -b && vite build); the API URL is relative so no build-time env is
# required for the same-origin deployment.
RUN npm run build

# ---------------------------------------------------------------- stage 2: API build
FROM maven:3.9.9-eclipse-temurin-21 AS api
WORKDIR /build/backend
COPY backend/pom.xml ./
# Warm the Maven cache in its own layer; a resolution hiccup must not fail an otherwise valid build.
RUN mvn -B -q dependency:go-offline || true
COPY backend/src ./src
# Bake the SPA bundle into the jar: it becomes classpath:/static/, served by WebStaticConfig.
COPY --from=web /build/frontend/dist ./src/main/resources/static
# maven.test.skip also skips compiling tests: the image never runs them, and CI verifies the suite.
RUN mvn -B -Dmaven.test.skip=true package \
 && jar tf target/operonix-api.jar | grep -q 'BOOT-INF/classes/static/index.html'

# ---------------------------------------------------------------- stage 3: runtime
FROM eclipse-temurin:21-jre-alpine AS runtime

# Least privilege: Cloud Run does not require root, and the container writes nothing to disk.
RUN addgroup -S operonix && adduser -S -G operonix -H operonix
WORKDIR /app
COPY --from=api /build/backend/target/operonix-api.jar /app/app.jar
RUN chown -R operonix:operonix /app
USER operonix

# Cloud Run injects PORT (default 8080) and expects the process to listen on it; server.port
# already reads ${PORT}. JAVA_OPTS is intentionally overridable per revision.
ENV PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC -Dfile.encoding=UTF-8"
EXPOSE 8080

# Used by local `docker run` only — Cloud Run probes /actuator/health/* instead.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health/liveness" >/dev/null 2>&1 || exit 1

# exec keeps the JVM as PID 1 so SIGTERM reaches Spring's graceful shutdown; "$@" lets Cloud Run
# append arguments (used by the migrate-only Cloud Run job).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
