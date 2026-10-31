# Operonix — Production Readiness Checklist

Use this before the first production deployment and again before any significant release. Each item
is either **verified** (with the command or evidence noted) or **not done**. Do not mark an item done
from memory.

Status of this document: the application-side items were verified on the development machine; the
Google Cloud items require a project, billing and approval and have **not** been executed.

---

## 1. Application build and tests

| # | Item | How to verify | Status |
| --- | --- | --- | --- |
| 1.1 | Backend test suite passes | `cd backend && mvn verify` → 30 tests, 0 failures | ✅ verified locally |
| 1.2 | Frontend type check passes | `cd frontend && npm run typecheck` | ✅ verified locally |
| 1.3 | Frontend production build passes | `cd frontend && npm run build` | ✅ verified locally |
| 1.4 | Single-origin hosting works | `SpaHostingIT` + a run of the packaged jar with the `prod` profile | ✅ verified locally |
| 1.5 | SPA bundle is inside the jar | `jar tf backend/target/operonix-api.jar \| grep static/index.html` | ✅ verified locally |
| 1.6 | Container image builds | `docker build -t operonix:check .` (CI also builds it) | ⬜ not run here (no Docker daemon) |
| 1.7 | Container starts and reports healthy | `docker run -p 8080:8080 ...` then `curl localhost:8080/actuator/health/readiness` | ⬜ requires a container runtime |
| 1.8 | CI is green on the release commit | GitHub → Actions → `CI` | ⬜ repo has no `.git` yet; workflow is syntax-validated only |

## 2. Configuration and secrets

| # | Item | How to verify | Status |
| --- | --- | --- | --- |
| 2.1 | No secret in the repository | `git grep -iE "jwt_secret\|password=" -- ':!*.example'` and review `deploy/gcp/.env` is untracked | ✅ `.gitignore` + `.dockerignore` added |
| 2.2 | No secret in the image | `.dockerignore` excludes `.env*`, `*.pem`, `*.key`, `*service-account*.json` | ✅ verified by inspection |
| 2.3 | No secret in the client bundle | `grep -r "OPERONIX_JWT_SECRET\|OPERONIX_DB_PASSWORD" frontend/dist/` → no matches | ✅ verified |
| 2.4 | JWT secret is strong and unique per environment | `gcloud secrets versions access latest --secret operonix-jwt-secret \| wc -c` ≥ 44 (48 bytes base64) | ⬜ after bootstrap |
| 2.5 | Dev JWT secret is not used | Startup fails under `prod` if the dev value is supplied (`JwtService`) | ✅ verified by test |
| 2.6 | `.env` files are git-ignored | `git check-ignore -v deploy/gcp/.env` | ⬜ once the repo is initialised |
| 2.7 | `localStorage` holds only the refresh token | Inspect devtools → Application → Local Storage | ✅ verified in the browser |

## 3. Google Cloud resources

| # | Item | How to verify | Status |
| --- | --- | --- | --- |
| 3.1 | Required APIs enabled | `gcloud services list --enabled` includes run, sqladmin, artifactregistry, cloudbuild, secretmanager | ⬜ |
| 3.2 | Artifact Registry repository exists | `gcloud artifacts repositories list --location <region>` | ⬜ |
| 3.3 | Cloud SQL PostgreSQL 16, backups and PITR on | `gcloud sql instances describe operonix-postgres` → `backupConfiguration.enabled=true`, `pointInTimeRecoveryEnabled=true` | ⬜ |
| 3.4 | No authorized networks; connector access only | `gcloud sql instances describe … --format='value(settings.ipConfiguration)'` | ⬜ |
| 3.5 | Runtime service account is least privilege | Only `cloudsql.client`, `secretmanager.secretAccessor`, `logging.logWriter`, `monitoring.metricWriter` | ⬜ |
| 3.6 | No service-account key files exist | `gcloud iam service-accounts keys list --iam-account <run-sa>` shows only Google-managed keys | ⬜ |
| 3.7 | Region chosen deliberately | `.env` `REGION` (default `asia-south1`) | ⬜ |
| 3.8 | Budget and billing alerts configured | Billing → Budgets & alerts | ⬜ |

## 4. Deployment

| # | Item | How to verify | Status |
| --- | --- | --- | --- |
| 4.1 | Migrations applied before traffic | `deploy migrate` succeeds, then `deploy deploy` | ⬜ |
| 4.2 | Revision is healthy | `gcloud run services describe … --format='value(status.conditions)'` | ⬜ |
| 4.3 | Smoke checks pass | `./deploy/gcp/deploy.sh smoke` (readiness, shell, deep link, 401, 404s) | ⬜ |
| 4.4 | Full lifecycle against the live API | `cd frontend && node scripts/smoke.mjs` pointed at the service URL | ⬜ |
| 4.5 | Logs are structured | Cloud Logging shows JSON with `severity`, `message`, `requestId` | ⬜ |
| 4.6 | Rollback rehearsed | Deploy a revision, roll traffic back, confirm the old revision serves | ⬜ |

## 5. Security review

| # | Item | Status |
| --- | --- | --- |
| 5.1 | `/api/**` requires a token; `/actuator/health/*` is the only public platform surface | ✅ `SpaHostingIT` |
| 5.2 | Unknown non-API paths never expose data (SPA shell only) | ✅ `SpaHostingIT` |
| 5.3 | Tenant isolation enforced by query scoping + pre-persist guard | ✅ `AuthFlowIT`, `SalesFlowIT` |
| 5.4 | Role rules enforced server-side (owner/admin/manager/technician) | ✅ `AuthFlowIT` |
| 5.5 | CSP allows fonts but no third-party scripts; HSTS, nosniff, frame-deny | ✅ header assertions |
| 5.6 | Rate limiting on login/register/refresh | ✅ `AuthRateLimiter` |
| 5.7 | API docs disabled in deployed environments (`OPERONIX_API_DOCS=false`) | ✅ prod profile |
| 5.8 | `ddl-auto=validate` (no schema drift at runtime) | ✅ all profiles |
| 5.9 | CORS empty for same-origin deployment | ✅ prod profile |
| 5.10 | Refresh tokens hashed at rest, rotated on use, reuse detected | ✅ `AuthFlowIT` |

## 6. Data and recovery

| # | Item | Status |
| --- | --- | --- |
| 6.1 | Automated daily backups + PITR on the production instance | ⬜ platform |
| 6.2 | Restore procedure documented and timed | ✅ [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) — rehearsal ⬜ |
| 6.3 | RPO/RTO agreed with the business | ⬜ (targets documented: RPO ≤ 5 min via PITR, RTO ≈ 30–60 min) |
| 6.4 | Deletion protection enabled on the instance | ⬜ platform |
| 6.5 | Migrations are forward-only and reviewed | ✅ policy in [ERD.md](ERD.md) §7 |

## 7. Operations

| # | Item | Status |
| --- | --- | --- |
| 7.1 | Uptime check on `/actuator/health/readiness` | ⬜ platform |
| 7.2 | Alerts for 5xx, latency, DB CPU/disk, instance saturation | ⬜ platform |
| 7.3 | Error Reporting picks up exceptions from JSON logs | ⬜ verify after first deploy |
| 7.4 | Rollback and connection-budget procedures documented | ✅ [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md) |
| 7.5 | `memory.md` current, with the exact stopping point | ✅ updated at the end of this session |

## Sign-off

| Role | Name | Date |
| --- | --- | --- |
| Engineering | | |
| Operations | | |
| Business owner (billing/DPA) | | |

Until every ⬜ item in sections 2–4 is closed with evidence, production deployment is **not**
approved.