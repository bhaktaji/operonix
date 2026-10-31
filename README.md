# Operonix

Multi-tenant vertical SaaS for small service businesses. v1 serves **cleaning companies**: one
workspace covers the whole lead-to-cash loop — customers and service locations → priced service
catalogue → quotes → scheduled jobs with technicians → invoices, payments and reversals → owner
dashboard and audit trail.

- Backend: Java 21, Spring Boot 3.3, Spring Security 6, Spring Data JPA, Flyway, PostgreSQL 16
- Frontend: React 18 + TypeScript 5, Vite 5, React Router 6, TanStack Query 5, Tailwind CSS 3
- Design documents: [HLD](docs/HLD.md), [LLD](docs/LLD.md), [ERD](docs/ERD.md)
- Session state and handover notes: [memory.md](memory.md)

## Quick start

```bash
# 1. Database (any PostgreSQL 14+ works; Docker is convenient)
docker compose up -d db

# 2. API on http://localhost:8080 (Flyway creates the schema at startup)
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Web app on http://localhost:5173 (proxies /api to :8080)
cd frontend
npm install
npm run dev
```

Register the first workspace in the browser; the registering user becomes its `OWNER`.

### Environment variables

| Variable | Default (dev profile) | Purpose |
| --- | --- | --- |
| `OPERONIX_DB_URL` | `jdbc:postgresql://localhost:5432/operonix` | JDBC URL |
| `OPERONIX_DB_USER` / `OPERONIX_DB_PASSWORD` | `operonix` | Database credentials |
| `OPERONIX_JWT_SECRET` | dev-only constant | HS256 signing key, ≥ 32 bytes; the app refuses to start without one and rejects the dev value under the `prod` profile |
| `OPERONIX_JWT_ISSUER` / `OPERONIX_JWT_AUDIENCE` | `operonix` / `operonix-web` | Token claims |
| `OPERONIX_ACCESS_TTL` / `OPERONIX_REFRESH_TTL` | `15m` / `30d` | Token lifetimes |
| `OPERONIX_CORS_ORIGINS` | `http://localhost:5173` | Allowed web origins |
| `OPERONIX_API_DOCS` | `true` (dev) | Swagger UI at `/swagger-ui.html` |

Never commit secrets; production must supply its own JWT secret and database credentials.

In production (`SPRING_PROFILES_ACTIVE=prod`) the database is reached through Cloud Run's Cloud SQL
socket and credentials come from Secret Manager:

| Variable | Source | Purpose |
| --- | --- | --- |
| `OPERONIX_DB_INSTANCE` | plain env | `project:region:instance` (mounted at `/cloudsql/…`) |
| `OPERONIX_DB_NAME` / `OPERONIX_DB_USER` | plain env | database and user |
| `OPERONIX_DB_POOL` | plain env | pool size **per instance** (default 5 in prod) |
| `OPERONIX_DB_PASSWORD` | **Secret Manager** | database password |
| `OPERONIX_JWT_SECRET` | **Secret Manager** | signing key; startup fails without it |
| `PORT` | Cloud Run | listen port (default 8080) |

## Tests and builds

```bash
# Backend: unit + integration tests (embedded PostgreSQL, real Flyway migrations)
cd backend && mvn verify

# Frontend: type-check and production build
cd frontend && npm run build        # tsc -b && vite build

# Optional manual API walkthrough against a running backend
cd frontend && node scripts/smoke.mjs

# Optional: seed a reviewable workspace (prints credentials)
cd frontend && node scripts/demo-seed.mjs
```

On a machine without system JDK/Maven, this repository was developed with a project-local toolchain
in `.tools/` (git-ignored): `JAVA_HOME=$PWD/.tools/jdk-21.0.13+9` and
`$PWD/.tools/apache-maven-3.9.9/bin/mvn`.

## Deployment (Google Cloud)

The application is packaged as **one container** that serves the SPA and the API from the same origin,
deployed on **Cloud Run** with **Cloud SQL PostgreSQL 16**, **Artifact Registry**, **Cloud Build**,
**Secret Manager** and Cloud Logging/Monitoring.

```bash
# once per environment (creates billable resources; prints a plan first)
cp deploy/gcp/.env.example deploy/gcp/.env   # set PROJECT_ID, REGION, ENV
./deploy/gcp/deploy.sh bootstrap --yes       # Windows: .\deploy\gcp\deploy.ps1 bootstrap -Yes

./deploy/gcp/deploy.sh build                 # Cloud Build -> Artifact Registry
./deploy/gcp/deploy.sh job --yes             # migrate-only Cloud Run job
./deploy/gcp/deploy.sh migrate               # Flyway before traffic moves
./deploy/gcp/deploy.sh deploy --yes          # Cloud Run revision
./deploy/gcp/deploy.sh smoke                 # live checks
```

- Architecture and configuration: [docs/GOOGLE_CLOUD_DEPLOYMENT.md](docs/GOOGLE_CLOUD_DEPLOYMENT.md)
- Deployment walkthrough: [deploy/gcp/README.md](deploy/gcp/README.md)
- Terraform (infrastructure only, nothing applied): [infra/terraform](infra/terraform/README.md)
- Go-live gates: [docs/PRODUCTION_CHECKLIST.md](docs/PRODUCTION_CHECKLIST.md)
- Operations and rollback: [docs/OPERATIONS_RUNBOOK.md](docs/OPERATIONS_RUNBOOK.md)
- Backups and restore: [docs/DISASTER_RECOVERY.md](docs/DISASTER_RECOVERY.md)
- Google AI Studio (development assistance, not deployment):
  [docs/AI_STUDIO_HANDOVER.md](docs/AI_STUDIO_HANDOVER.md)

**Nothing has been deployed.** The image build, Terraform apply and any Cloud Run rollout require a
Google Cloud project, billing and explicit approval; see the checklist for what is still ⬜.

## What the app does

| Area | Capability |
| --- | --- |
| Workspace | Register a company, owner/admin team management, currency and timezone settings |
| Customers | Contacts, billing address, multiple service locations with access notes, archive/restore |
| Catalogue | Priced services with unit, default duration and tax rate; deactivate instead of delete |
| Quotes | Line items from the catalogue or ad-hoc, header discount, server-computed tax and totals, send/accept/decline/convert, expiry |
| Scheduling | Week board, crew assignment with conflict detection, recurring series (weekly/biweekly/monthly), status transitions |
| Billing | Invoices from completed jobs or ad-hoc, partial payments, reversals, balances, overdue flags, void with reason |
| Reporting | Money collected this month vs last, outstanding/overdue, jobs today/this week, quote pipeline, top services, upcoming jobs |
| Administration | Team roles and deactivation, workspace settings, append-only audit log with filters |
| Security | JWT access tokens (memory) + rotating refresh tokens (hashed at rest), tenant-scoped queries, role-based access |

## Project layout

```
backend/        Spring Boot API (modular monolith: tenancy, identity, customer, catalog, quoting,
                scheduling, billing, reporting, audit, document numbering)
frontend/       React SPA (api/, auth/, components/, pages/, lib/)
docs/           HLD, LLD, ERD, deployment, operations, research
Dockerfile      three-stage build: SPA -> jar with the bundle -> non-root JRE runtime
cloudbuild.yaml Cloud Build: build, push, optional migrate + deploy
deploy/gcp/     deploy.sh / deploy.ps1 / .env.example / README
infra/terraform/ Cloud Run, Cloud SQL, Artifact Registry, Secret Manager, IAM, optional bucket
.github/        CI and Workload-Identity-Federation deployments
memory.md       single source of truth for progress and handover
compose.yaml    local PostgreSQL
```

The frontend uses **npm** with a committed `package-lock.json` (one lockfile); `npm ci` is used in
CI and in the container build. Nothing in the project requires pnpm/yarn.

## Known limitations (v1)

- Billing records payments; no card capture or payment-gateway integration.
- No email/SMS delivery: "send" changes status and stamps the audit log.
- One user belongs to one workspace (no cross-workspace membership or invitations).
- No customer portal, attachments, route optimisation or SSO.
- No automated browser tests; UI is verified manually and by type-check/build.
- No payment gateway, no email/SMS delivery, no attachments (so Cloud Storage is not used yet).
- Deployment is prepared but **not executed**: no image has been built on this machine (no container
  runtime), no Terraform applied, no Google Cloud resource created. See
  [docs/PRODUCTION_CHECKLIST.md](docs/PRODUCTION_CHECKLIST.md) for what remains ⬜.