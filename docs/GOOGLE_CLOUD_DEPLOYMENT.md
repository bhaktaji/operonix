# Operonix — Google Cloud Deployment

| Field | Value |
| --- | --- |
| Version | 1.0 |
| Last updated | 2026-10-31 |
| Status | **Deployment-ready, not deployed.** No Google Cloud resource has been created and no deployment has been performed. |
| Companion documents | [HLD.md](HLD.md), [LLD.md](LLD.md), [ERD.md](ERD.md), [PRODUCTION_CHECKLIST.md](PRODUCTION_CHECKLIST.md), [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md), [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) |

This document describes how the existing Operonix application — unchanged in architecture — is
packaged and run on Google Cloud. The backend stays Java 21 / Spring Boot 3.3 with PostgreSQL 16 and
Flyway; the frontend stays React + TypeScript + Vite. No data or business logic moved.

---

## 1. Deployment architecture

One container image serves both the built SPA and the `/api/v1` REST API from a **single Cloud Run
service**, so the browser talks to one origin. This was chosen over separate frontend/backend
services because it needs no CORS configuration, no cross-service token forwarding, no second URL to
protect, and no additional hop — and it did not require changing any application code beyond serving
static files.

```
                          ┌──────────────────────────────┐
   Browser  ──HTTPS──▶    │  Cloud Run: operonix-api-*   │
   (SPA + API)            │  one container, one origin   │
                          │                              │
                          │  WebStaticConfig  → SPA      │
                          │  /api/v1/**       → API      │
                          │  /actuator/**     → probes   │
                          └───────┬──────────────┬───────┘
                                  │              │
                    /cloudsql socket             │ secrets as env vars
                                  │              │
                     ┌────────────▼───┐   ┌──────▼──────────┐
                     │ Cloud SQL      │   │ Secret Manager  │
                     │ PostgreSQL 16  │   │ db password,    │
                     │ (Flyway)       │   │ JWT signing key │
                     └────────────────┘   └─────────────────┘

   Cloud Build ──build/push──▶ Artifact Registry ──deploy──▶ Cloud Run service + migrate job
   Cloud Logging / Monitoring / Error Reporting  ◀── stdout JSON logs, /actuator/health
```

### Components

| Component | Resource | Purpose |
| --- | --- | --- |
| Application hosting | Cloud Run service `operonix-api-<env>` | Serves SPA + API, autoscaling, scale-to-zero |
| Migrations | Cloud Run job `operonix-migrate-<env>` | Runs the same image in migrate-only mode before a rollout |
| Database | Cloud SQL PostgreSQL 16 `operonix-postgres` | Same schema as local (`V1__schema.sql`) |
| Images | Artifact Registry `operonix` | Tagged by commit short SHA; keeps the last 10 |
| Build | Cloud Build (`cloudbuild.yaml`) | Builds/pushes, optionally deploys |
| Secrets | Secret Manager `operonix-db-password`, `operonix-jwt-secret` | Injected as env vars at runtime |
| Observability | Cloud Logging, Cloud Monitoring, Error Reporting | Structured JSON logs, health probes, uptime checks |
| Identity | `operonix-run-<env>@…` | Least-privilege runtime service account |
| File storage | Cloud Storage (optional, not enabled) | Reserved for future attachments; not used by the MVP |

### Why the same origin works

* `frontend/src/api/client.ts` already calls a **relative** `/api/v1`, so production needs no build
  time URL and no CORS.
* `WebStaticConfig` serves `classpath:/static/` (the baked SPA bundle) and falls back to
  `index.html` only for client-side routes. `/api/**`, `/actuator/**`, `/v3/**`, `/swagger-ui/**`
  and missing files with an extension are never rewritten, so a bad API URL still returns a JSON
  problem document and a stale asset returns 404 instead of HTML.
* `SecurityConfig` requires a token for `/api/**` and `/actuator/**` and permits the static shell —
  the shell contains no tenant data and every data call is authenticated.

A split deployment (e.g. Firebase Hosting for the SPA) is still possible without touching the
backend: build with `VITE_API_URL=https://<api-host>/api/v1`, set `OPERONIX_CORS_ORIGINS` to the
frontend origin, and skip the static bundle. It is not the recommended path; see §11.

---

## 2. The container image

`Dockerfile` is a three-stage build:

| Stage | Base | Work |
| --- | --- | --- |
| `web` | `node:22-alpine` | `npm ci` + `npm run build` → `frontend/dist` |
| `api` | `maven:3.9.9-eclipse-temurin-21` | copies `dist` into `src/main/resources/static`, `mvn package`, asserts the bundle is inside the jar |
| `runtime` | `eclipse-temurin:21-jre-alpine` | non-root `operonix` user, `/app/app.jar`, `EXPOSE 8080` |

Runtime contract:

| Requirement | Implementation |
| --- | --- |
| Cloud Run `PORT` | `server.port: ${PORT:8080}` (already present) |
| Graceful shutdown | `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 10s` (prod) — aligned with Cloud Run's 10s SIGTERM window; `exec java` keeps the JVM as PID 1 so the signal arrives |
| Health / readiness | `/actuator/health/liveness` and `/actuator/health/readiness` (readiness includes the database indicator) |
| SPA routing | root forwards to `index.html`; deep links resolved by `WebStaticConfig` |
| No persistent local storage | nothing is written to disk; logs go to stdout, uploads do not exist in v1 |
| Least privilege | non-root user, no shell tooling, no secrets in the image |
| Memory awareness | `-XX:MaxRAMPercentage=75`, `-XX:+ExitOnOutOfMemoryError` |

`JAVA_OPTS`, `PORT` and `SPRING_PROFILES_ACTIVE` are overridable per revision; the entrypoint appends
extra arguments, which is how the migrate-only job runs.

---

## 3. Configuration

All production configuration is external. `application-prod.yml` reads environment variables only;
`JwtService` refuses to start without a signing secret and rejects the development value.

| Variable | Source | Purpose |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE=prod` | plain env | JSON logs, docs off, graceful tuning |
| `PORT` | Cloud Run | listen port |
| `OPERONIX_DB_INSTANCE` | plain env | Cloud SQL connection name `project:region:instance` |
| `OPERONIX_DB_NAME`, `OPERONIX_DB_USER` | plain env | database and user |
| `OPERONIX_DB_POOL` | plain env | Hikari pool size **per instance** |
| `OPERONIX_DB_PASSWORD` | **Secret Manager** | database password |
| `OPERONIX_JWT_SECRET` | **Secret Manager** | HS256 signing key (≥ 32 bytes) |
| `OPERONIX_API_DOCS` | plain env | `false` in every deployed environment |
| `OPERONIX_CORS_ORIGINS` | plain env | empty for same-origin; set only for a split deployment |
| `OPERONIX_DB_URL` | optional override | full JDBC URL when not using the socket |

The datasource default in `application-prod.yml` is
`jdbc:postgresql:///${OPERONIX_DB_NAME}?host=/cloudsql/${OPERONIX_DB_INSTANCE}`, i.e. the Unix socket
that Cloud Run mounts at `/cloudsql/<connection-name>`.

## 4. Secrets

* Created by `deploy/gcp/deploy.sh secrets` (or Terraform). The JWT key is generated locally with 48
  random bytes and piped straight into Secret Manager; the database password is generated when the
  SQL user is created. Neither value is printed or stored in the repository.
* Injected with `--set-secrets=OPERONIX_DB_PASSWORD=operonix-db-password:latest,OPERONIX_JWT_SECRET=…`
  — no secret is ever in an image, a build arg, a `.env` file in the repo, or a client bundle.
* Only the runtime service account has `secretmanager.secretAccessor`.
* **Rotation**: add a new secret version (`gcloud secrets versions add …`) and deploy a new
  revision. Rotating the JWT key signs everybody out; rotate during a maintenance window. Rotating
  the database password requires updating both Cloud SQL and the secret.
* `.dockerignore` and `.gitignore` exclude `.env`, keys and service-account JSON files.

## 5. Networking options

| Option | When | How |
| --- | --- | --- |
| **Cloud Run built-in connector** (default) | Public-IP or connector-reachable instance | `--add-cloudsql-instances`, JDBC `host=/cloudsql/<connection>`; IAM-authenticated, no authorized networks, no VPC cost |
| Private IP only | Policy forbids a public IP | Create the instance with `--no-assign-ip`, give Cloud Run **Direct VPC egress** (`--network`, `--subnet`, `--vpc-egress=private-ranges-only`) and keep the same `/cloudsql` socket URL |
| Cloud SQL Java Connector | No socket mount (e.g. another runtime) | `OPERONIX_DB_URL=jdbc:postgresql:///<db>?cloudSqlInstance=<connection>&socketFactory=com.google.cloud.sql.postgres.SocketFactory&ipTypes=PRIVATE`; the dependency ships in the image |

The runtime identity needs `roles/cloudsql.client` in every option.

## 6. Migrations during deployment

Two mechanisms, deliberately redundant:

1. **Service startup** — Flyway runs on every container start (`OPERONIX_FLYWAY_ENABLED=true`). It is
   idempotent and takes a database lock, so several instances starting together (or scaling up later)
   are safe. `ddl-auto=validate` then fails fast if entity and schema disagree.
2. **Migrate-only job** — the same image with `--spring.main.web-application-type=none
   --operonix.migrate-only=true` (`MigrationRunner`): Flyway runs, the schema version is logged, and
   the process exits 0 (non-zero on failure). `deploy.sh migrate` / `deploy.ps1 migrate` execute it
   **before** the new revision is deployed, so a schema problem stops the rollout instead of
   surfacing as 500s.

Migrations are forward-only. Roll back the application revision; fix the schema with a new
migration. Never edit an applied migration.
## 7. Scaling and the database connection budget

Cloud Run instances are independent; each keeps its own Hikari pool. The hard constraint is Cloud
SQL's `max_connections`:

```
max_instances × OPERONIX_DB_POOL  +  migration job  +  headroom  ≤  max_connections
```

With the shipped defaults — `OPERONIX_DB_POOL=5`, `max_instances=3` — that is 15 + 1 + headroom ≈ 16,
which fits a small shared-core instance. Before raising `max_instances`, check the real limit:

```sql
SHOW max_connections;   -- via the Cloud SQL console or a one-off job
```

Recommended starting points:

| Environment | Cloud SQL tier | Pool | Max instances | Min instances | Concurrency |
| --- | --- | --- | --- | --- | --- |
| staging | `db-g1-small` | 5 | 3 | 0 | 80 |
| production (small) | `db-custom-1-3840` | 5 | 5 | 0–1 | 80 |
| production (growth) | `db-custom-2-7680` + read replica | 10 | 10 | 1–2 | 80 |

If connections become the limit before CPU does, prefer a larger database tier or a connection
pooler over raising `max_instances` blindly. `min_instances=0` keeps cost near zero at the price of a
cold start (JVM + Flyway check, roughly 5–10 s on 1 vCPU); `startup_cpu_boost` softens the first
request after idling.

## 8. Environments

| | staging | production |
| --- | --- | --- |
| Config file | `deploy/gcp/.env` with `ENV=staging` | `ENV=production` |
| Service / job | `operonix-api-staging`, `operonix-migrate-staging` | `operonix-api-production`, `operonix-migrate-production` |
| Secrets | separate secrets per environment | separate secrets per environment |
| Database | shared instance with its own database, or a smaller dedicated instance | dedicated instance, 14-day PITR, REGIONAL availability |
| Trigger | push to `main` (`.github/workflows/deploy.yml`) | version tag `v*` or manual dispatch |
| API docs | off | off |

Keep staging disposable: it is the place to rehearse migrations and rollbacks.

## 9. Cost profile and alerts

What costs money, in order: **Cloud SQL** (always on), Cloud Run requests/instance-seconds, Artifact
Registry storage, Cloud Logging volume beyond the free tier, Cloud Storage if enabled.

Defaults chosen to avoid waste: `min_instances=0`, `cpu_idle=true` (CPU only during requests),
1 vCPU / 1 GiB, `max_instances=3`, one image family with a 10-tag retention policy, no bucket, no
uptime-check fees beyond one free check per service.

Recommended alerts (Cloud Monitoring):

| Alert | Threshold | Why |
| --- | --- | --- |
| Billing budget | 50% / 80% / 100% of the monthly budget, email to the owner | Catches a runaway instance or a forgotten staging database |
| Cloud Run 5xx rate | > 1% over 5 minutes | Application or database problems |
| Cloud Run latency p95 | > 2 s over 10 minutes | Pool exhaustion, slow queries |
| Cloud SQL CPU | > 80% for 10 minutes | Under-provisioned tier |
| Cloud SQL disk | > 80% used | Backups/large tables |
| Cloud Run instance count | at `max_instances` for 10 minutes | Throttling risk; raise limits deliberately |
| Uptime check | `/actuator/health/readiness` fails twice | Outage detection |

See [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md) for setup commands.

## 10. What was verified, and what was not

Verification on the development machine (Windows, no Docker daemon, no gcloud project):

| Check | Result |
| --- | --- |
| Backend test suite (`mvn test`) | **30 tests, 0 failures** — includes `SpaHostingIT` (shell at `/`, deep-link fallback, asset serving, missing asset 404, API paths never rewritten, readiness public, CSP) and `WebStaticConfigTest` (resolver rules) |
| Frontend type check + production build | **passes** (`tsc -b && vite build`) |
| SPA bundle inside the packaged jar | **verified** — `BOOT-INF/classes/static/index.html` present after copying `frontend/dist` into the jar's static resources |
| Production artifact over HTTP | **verified** — packaged jar + `prod` profile served the real SPA bundle and API on one origin (see memory.md) |
| Flyway on a clean database | **verified** — migrations run from scratch in every integration test |
| Secrets absent from client bundle and logs | **verified by inspection and by the smoke checks** (`/application-prod.yml` is 404, no secret value is logged, no secret has a `VITE_`-prefixed name) |
| `Dockerfile` build | **not verified** — no container runtime on this machine. The image is built by CI (`.github/workflows/ci.yml`) and Cloud Build |
| GCP deployment | **not verified** — no project, no credentials, and explicitly not authorized |
| Terraform `plan`/`apply` | **not verified** — Terraform is not installed here; the configuration is syntax-reviewed only |

Nothing in this document should be read as evidence that a Google Cloud deployment has been tested.

## 11. Alternative: separate frontend and backend services

If the SPA must be hosted separately (for example behind a CDN, or with Firebase Hosting):

1. Build with `VITE_API_URL=https://<api-host>/api/v1`.
2. Serve `frontend/dist` from the CDN/host of choice with an SPA rewrite to `/index.html`.
3. Set `OPERONIX_CORS_ORIGINS=https://<spa-host>` on the API service and keep the API deployed from
   the same image (the static bundle is simply unused).
4. Keep the API public with application-level authentication; CORS is not a security boundary.

The application already supports this without code changes; it is documented rather than shipped
because it adds moving parts and a second origin to secure.

## 12. Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Container exits immediately with "OPERONIX_JWT_SECRET is required" | Secret not attached | Add `--set-secrets=OPERONIX_JWT_SECRET=…`; confirm the revision has the env var |
| "The development JWT secret cannot be used with the prod profile" | Dev secret leaked into prod config | Generate a real key in Secret Manager |
| Cloud Run 500s at startup, log shows `connection refused /cloudsql/...` | Missing `--add-cloudsql-instances` or `roles/cloudsql.client` | Re-check the deploy flags and the runtime IAM bindings |
| `FATAL: remaining connection slots are reserved` | `max_instances × pool` above `max_connections` | Lower `max_instances`/`OPERONIX_DB_POOL`, raise the tier |
| Deep links (`/quotes/...`) return 404 | Static bundle missing from the image | Check the `jar tf` assertion in the Dockerfile; redeploy |
| `/` returns 404 but `/index.html` works | Root forward removed | `SpaRootController` must map `/` |
| Swagger 404 in production | Intended: `OPERONIX_API_DOCS=false` | Enable temporarily for a debugging revision only |
| Revision healthy but the old version still serves | Traffic pinned to a previous revision | `gcloud run services update-traffic … --to-latest` |
| `Cloud Build` fails at `mvn package` | Frontend build failed first | Check the `web` stage logs in the build output |
