# Operonix — Project Memory (single source of truth)

> Read this file first in a new session, then `docs/HLD.md`, `docs/LLD.md`, `docs/ERD.md`, and
> `docs/GOOGLE_CLOUD_DEPLOYMENT.md`.
> Never store credentials, tokens or secrets here.

| Field | Value |
| --- | --- |
| Last updated | 2026-10-31 (session 3) |
| Current phase | Phase 3 complete — MVP implemented and verified; **Google Cloud deployment ready, not deployed** |
| Overall status | Working end-to-end locally and from the packaged production artifact; all GCP artifacts prepared and verified as far as this machine allows |

---

## 1. Project overview

**Operonix** is a multi-tenant vertical SaaS for underserved small service businesses. The first
vertical is **cleaning companies** (2–50 staff). One workspace covers the lead-to-cash loop:
customers and service locations → priced service catalogue → quotes → scheduled jobs with
technicians → invoices and payments → owner dashboard and audit trail.

Objectives (v1), all met: one customer record with locations; server-computed quote pricing;
conflict-free scheduling including recurring visits; invoicing with partial payments, reversals and
accurate balances; an owner dashboard (money-free for technicians); enforced tenant isolation and
role-based access; production-oriented code (validation, migrations, tests, audit, logging, docs).

Out of scope for v1: payment-gateway capture, email/SMS delivery, accounting sync, customer portal,
native apps, attachments, route optimisation, SSO, SaaS billing, cross-workspace membership.

---

## 2. Technology stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.3.13 (MVC, Security 6, Data JPA/Hibernate 6, Validation) |
| Database | PostgreSQL 16, Flyway `V1__schema.sql`, `ddl-auto=validate` |
| Auth | JWT access tokens (HS256, 15 min, memory-only) + rotating opaque refresh tokens (SHA-256 at rest) |
| API docs | springdoc-openapi 2.6 (dev only; disabled in prod) |
| Logging | Logback; JSON encoder on stdout under the `prod` profile (Cloud Logging) |
| Tests | JUnit 5, AssertJ, MockMvc, Zonky embedded PostgreSQL 2.2.2 |
| Frontend | React 18.3, TypeScript 5.6, Vite 5.4, React Router 6.28, TanStack Query 5, Tailwind 3.4 |
| Backend build | Maven 3.9.9 + JDK 21 from project-local `.tools/` (git-ignored) |
| Frontend build | **npm** with `frontend/package-lock.json` (single lockfile; `npm ci` in CI and Docker) |
| Deployment | Cloud Run (one service), Cloud SQL PostgreSQL 16, Artifact Registry, Cloud Build, Secret Manager, Cloud Logging/Monitoring |
| IaC / CI | Terraform (minimal, not applied), GitHub Actions (CI + WIF deployment) |

**Local toolchain note:** this machine has no system JDK 21, Maven, Docker, `gcloud`, Terraform or
Python. Everything runs from `.tools/` (`jdk-21.0.13+9`, `apache-maven-3.9.9`). Integration tests use
embedded PostgreSQL, so `mvn test` needs no database server and no container runtime.
---

## 3. Repository structure

```
operonix/
├── backend/                 Spring Boot API (Maven)
│   └── src/main/java/com/operonix/{common,config,security,audit,document,tenancy,
│                                  identity,customer,catalog,quoting,scheduling,billing,reporting}
├── frontend/                React + TypeScript SPA (Vite) + scripts/{smoke,demo-seed}.mjs
├── docs/                    HLD, LLD, ERD, GOOGLE_CLOUD_DEPLOYMENT, AI_STUDIO_HANDOVER,
│                            PRODUCTION_CHECKLIST, OPERATIONS_RUNBOOK, DISASTER_RECOVERY, research
├── deploy/gcp/              deploy.sh, deploy.ps1, README.md, .env.example
├── infra/terraform/         versions, variables, platform, run, outputs, README, tfvars example
├── .github/workflows/       ci.yml (tests/build/image), deploy.yml (WIF build+migrate+deploy)
├── Dockerfile               SPA -> jar with bundle -> non-root JRE runtime (single Cloud Run image)
├── .dockerignore            excludes .tools, node_modules, dist, target, .env, keys
├── .gitignore               excludes .tools, build output, deploy/gcp/.env, state, keys
├── cloudbuild.yaml          build+push, optional migrate job + Cloud Run deploy (_DEPLOY=true)
├── compose.yaml             PostgreSQL 16 for local development
├── README.md                setup, commands, scope, deployment pointer, limitations
└── memory.md                this file
```

Module boundaries: HLD §4.1. Each module owns its tables; services call other modules' services,
never their repositories (reporting is the read-only exception).

**No `.git` directory exists in this workspace.** Git metadata is unavailable, so nothing has been
committed and CI has never run. Creating the repository and pushing is step 1 of the next session.

---

## 4. Completed features / implementation status

| Area | Status | Evidence |
| --- | --- | --- |
| Phase 1 designs: HLD/LLD/ERD | ✅ | `docs/*.md`, reconciled with code and with the deployment |
| Auth: register/login/refresh/logout/me | ✅ | `AuthFlowIT`, browser walkthrough |
| Tenant isolation | ✅ | `AuthFlowIT`, `SalesFlowIT`, live 404/401 probes, cross-tenant check in `smoke.mjs` |
| Roles (owner/admin/manager/technician) | ✅ | `AuthFlowIT` role matrix, UI nav + guards |
| Workspace settings, team, catalogue | ✅ | API + UI verified (session 2) |
| Customers + locations | ✅ | CRUD/archive/restore, contact column |
| Quotes | ✅ | catalogue + ad-hoc lines, header discount, server totals (360 − 15 + 8.25% = 373.46) |
| Scheduling | ✅ | week board, conflicts, recurring series, transitions, technician scope |
| Invoices + payments | ✅ | derived from job (inherits discount), partial payments, reversal, voids, balances |
| Reporting dashboard | ✅ | money metrics for office roles, operational counters for technicians |
| Audit log | ✅ | filtered search, owner/admin only, append-only |
| Same-origin SPA+API hosting | ✅ **new** | `WebStaticConfig`, `SpaRootController`, `SpaHostingIT` (7 tests), `WebStaticConfigTest` (4) |
| Production profile (Cloud SQL socket, secrets, JSON logs, 10s shutdown) | ✅ **new** | `application-prod.yml`, `logback-spring.xml`, `MigrationRunner`; verified by running the packaged jar |
| Container image | ⚠️ prepared, not built here | `Dockerfile` (+ bundle assertion), `.dockerignore`; no Docker on this machine |
| Cloud Build / deploy scripts / Terraform / CI | ⚠️ prepared, not executed | `cloudbuild.yaml`, `deploy/gcp/*`, `infra/terraform/*`, `.github/workflows/*` (YAML validated) |
| GCP deployment | ❌ not performed | No project, no credentials, not authorized |

---

## 5. Current task and next steps

**Current task:** none in flight. Deployment artifacts are complete and verified as far as possible
without Docker, `gcloud` and a Google Cloud project.

**Verified in session 3 (commands + results):**

| Check | Result |
| --- | --- |
| `mvn test` (backend) | **30 tests, 0 failures** (was 19; +7 `SpaHostingIT`, +4 `WebStaticConfigTest`) |
| `npm run build` (frontend) | `tsc -b && vite build` passes (`dist/index.html`, `assets/index-*.js` 383.6 kB) |
| Frontend type check | `npm run typecheck` passes |
| SPA bundle in the packaged jar | `jar tf target/operonix-api.jar` → `static/index.html`, `static/assets/index-*.css/js` |
| **Production artifact over HTTP** | Packaged jar + `SPRING_PROFILES_ACTIVE=prod` + real PostgreSQL 16 (embedded, port 55432): Flyway applied V1 to an empty database, readiness `UP`, `/` 200 HTML, `/quotes/abc-123` 200 (shell), `/assets/index-*.js` 200 `text/javascript`, missing asset 404, `/api/v1/customers` 401 problem+json, `/api/v1/nope` 401 (not the shell), `/application-prod.yml` 404, CSP with font origins |
| Full lifecycle against that artifact | `node frontend/scripts/smoke.mjs` → **SEED OK**: register → customer → quote (discount/tax) → accept → convert → complete → invoice (total 319.34) → partial 100 (balance 219.34) → paid → dashboard → audit → cross-tenant 404 → anonymous 401 |
| JSON logging | `{"@timestamp","severity","logger_name","message",...}` one line per event on stdout |
| YAML validity | `ci.yml`, `deploy.yml`, `cloudbuild.yaml`, `compose.yaml` parsed with js-yaml |
| `deploy.sh` | `bash -n` syntax clean (421 lines) |
| `deploy.ps1` | PowerShell parser clean (428 lines) |

**Not verified (and must not be claimed):** `docker build` / container start (no Docker daemon),
`gcloud` commands (not installed, no project), `terraform plan/apply` (not installed), GitHub Actions
runs (no repository), any live GCP deployment.

**Next actionable steps (in order):**

1. `git init`, commit, push to GitHub, then confirm `.github/workflows/ci.yml` is green (it builds the
   image too, which is the first real container verification).
2. Run `docker build -t operonix:local .` on a machine with Docker; then
   `docker run -e OPERONIX_DB_URL=… -e OPERONIX_JWT_SECRET=… -p 8080:8080 operonix:local` and repeat
   the HTTP matrix from §5.
3. `cp deploy/gcp/.env.example deploy/gcp/.env`, `./deploy/gcp/deploy.sh plan`, then
   `bootstrap --yes` **only with owner approval** (creates billable Cloud SQL).
4. Deploy staging (`build`, `job --yes`, `migrate`, `deploy --yes`, `smoke`), then run
   `frontend/scripts/smoke.mjs` against the staging URL.
5. Rehearse a rollback (`update-traffic`) and a point-in-time restore on staging
   ([DISASTER_RECOVERY.md](docs/DISASTER_RECOVERY.md) §8); record the timings there.
6. Product v2 candidates (not started): gateway capture, email delivery, customer portal,
   `membership` table, exports, recurring invoice generation.

---

## 6. Architecture decisions (additions in session 3)

| Decision | Rationale |
| --- | --- |
| **One Cloud Run service** serves the SPA and `/api/v1` | Same-origin removes CORS, a second service, a second URL and token forwarding; the SPA already calls a relative `/api/v1`, so no app change was needed |
| Static shell is public; `/api/**` and `/actuator/**` require a token | The shell holds no tenant data; every data call is authenticated and every route is guarded client-side |
| `SpaRootController` forwards `/` to `index.html` | `ResourceHttpRequestHandler` skips resolution entirely for an empty path, so the resolver cannot serve the root |
| Fallback refuses `api/`, `actuator/`, `v3/`, `swagger-ui/`, `error`, `webjars/` and any path whose last segment has a dot | Keeps API 404s as JSON problems and makes a stale hashed asset a real 404 instead of HTML with a wrong MIME type |
| `spring.web.resources.add-mappings=false` | Leaves exactly one `/**` resource handler; Boot would otherwise register a second one and the winner would be ambiguous |
| CSP extended with `fonts.googleapis.com` (style) and `fonts.gstatic.com` (font) | `default-src 'self'` blocked the fonts that `index.html` loads; also added `connect-src`/`form-action` |
| Cloud SQL via Cloud Run's `/cloudsql` Unix socket (no authorized networks, no VPC connector) | IAM-authenticated, no extra networking cost; the Java Connector dependency ships for the alternative URL-based path |
| Flyway at startup **and** a migrate-only Cloud Run job | Startup migrations handle first boot and scale-up; the job makes a schema failure stop the rollout before traffic moves |
| JSON logs via `logstash-logback-encoder` under `prod` only | Cloud Logging parses `severity`/`message`/MDC without an agent; local development keeps readable logs |
| `timeout-per-shutdown-phase: 10s` in prod | Cloud Run sends SIGTERM then kills after 10 s; `exec java` keeps the JVM as PID 1 |
| Deploy scripts require `--yes`/`-Yes` and print a plan | The patterns bootstrap billable resources; a bare run must never create them |
| Terraform ignores `template[0].containers[0].image` | Infrastructure shape is Terraform's, the running image is the pipeline's — no fighting between them |
| npm retained (not pnpm) | The repository already had `package-lock.json` and a working `npm ci` build path; adding a second package manager/lockfile was unnecessary risk. `npm ci` is used in CI and Docker |

---

## 7. Files created or modified (session 3)

**Backend**
- `config/WebStaticConfig.java` (new) — SPA resource handler + narrow fallback resolver.
- `config/SpaRootController.java` (new) — `GET /` → `forward:/index.html`.
- `config/MigrationRunner.java` (new) — `--operonix.migrate-only=true`: log schema version, exit 0.
- `config/SecurityConfig.java` — `/api/**` + `/actuator/**` authenticated, shell public; CSP font
  origins, `connect-src`, `form-action`.
- `config/OperonixProperties.java` — `web.static-locations` record.
- `resources/application.yml` — `add-mappings: false`, `operonix.web.static-locations`.
- `resources/application-prod.yml` — rewritten: Cloud SQL socket URL, pool sizing, secret-only
  credentials, docs off, empty CORS, 10 s shutdown, probes, Tomcat threads.
- `resources/logback-spring.xml` — `prod` JSON appender.
- `pom.xml` — `logstash-logback-encoder` 8.0, `postgres-socket-factory` 1.21.0 (runtime).
- Tests: `config/SpaHostingIT.java`, `config/WebStaticConfigTest.java`,
  `test/resources/spa-fixture/{index.html,assets/app.js}`.

**Deployment / CI / IaC**
- `Dockerfile` (new), `.dockerignore` (new), `.gitignore` (new).
- `cloudbuild.yaml` (new).
- `deploy/gcp/deploy.sh`, `deploy/gcp/deploy.ps1`, `deploy/gcp/README.md`, `deploy/gcp/.env.example`.
- `infra/terraform/{versions,variables,platform,run,outputs}.tf`, `README.md`,
  `terraform.tfvars.example`.
- `.github/workflows/ci.yml`, `.github/workflows/deploy.yml`.

**Docs**
- New: `docs/GOOGLE_CLOUD_DEPLOYMENT.md`, `docs/AI_STUDIO_HANDOVER.md`,
  `docs/PRODUCTION_CHECKLIST.md`, `docs/OPERATIONS_RUNBOOK.md`, `docs/DISASTER_RECOVERY.md`.
- Updated: `docs/HLD.md` §10 (Cloud Run topology), `docs/LLD.md` §11 (new tests), §13 (config rows),
  new §16 (deployment packaging), `docs/ERD.md` §8 (production topology, no schema change),
  `README.md`, this file.

**Housekeeping:** `backend/src/main/resources/static/` was populated only to build and test the
production jar, then removed so build output is never committed. Two stale API processes from the
previous session (PIDs 19320, 2196) were stopped because they held a lock on the jar.

---

## 8. Known bugs, blockers, pending tests

| Item | Status |
| --- | --- |
| No `.git` directory | Blocker for CI and for GitHub-based delivery; the user must create the repository |
| No Docker daemon on this machine | `Dockerfile` is unbuilt locally; CI (`docker` job) and Cloud Build are the first real checks |
| No `gcloud`, no project, no billing | No GCP command has been executed; nothing has been deployed |
| Terraform not installed | Config is review-verified only; `terraform validate/plan` is pending |
| Cloud SQL networking assumption | Default path assumes Cloud Run's built-in connector reaches the instance; if the org mandates a private-IP-only instance, add Direct VPC egress (documented in GOOGLE_CLOUD_DEPLOYMENT.md §5) |
| DB password passed via `gcloud sql users create --password` | Brief exposure in the local process list; gcloud has no file-based option (noted in both scripts) |
| `mvn package` fails while an API instance runs on Windows (jar lock) | Stop the API first; not a code defect |
| Currency changes only affect new documents | Intentional |
| No automated browser tests | Manual walkthrough + `smoke.mjs` cover the flows |
| Region default is `asia-south1` | Configurable everywhere (`.env`, Terraform, Cloud Build substitution); it is not hardcoded in the app |

---

## 9. Commands

```bash
# Backend tests (embedded PostgreSQL, no Docker or server needed)
cd backend
JAVA_HOME=../.tools/jdk-21.0.13+9 ../.tools/apache-maven-3.9.9/bin/mvn test

# Backend run locally (needs PostgreSQL; dev profile supplies a dev-only JWT secret)
cd backend && JAVA_HOME=../.tools/jdk-21.0.13+9 java -jar target/operonix-api.jar --server.port=8080

# Frontend
cd frontend && npm ci && npm run dev      # :5173, proxies /api -> :8080
cd frontend && npm run typecheck && npm run build

# Full lifecycle check against a running API on :8080
cd frontend && node scripts/smoke.mjs

# Production-shaped local run (what the container does)
cd frontend && npm run build && cp -r dist/* ../backend/src/main/resources/static/
cd backend && JAVA_HOME=../.tools/jdk-21.0.13+9 ../.tools/apache-maven-3.9.9/bin/mvn -DskipTests package
SPRING_PROFILES_ACTIVE=prod OPERONIX_DB_URL=jdbc:postgresql://localhost:5432/operonix \
  OPERONIX_DB_USER=operonix OPERONIX_DB_PASSWORD=operonix \
  OPERONIX_JWT_SECRET=<32+ random bytes> java -jar target/operonix-api.jar
# then remove backend/src/main/resources/static again

# Container (needs Docker)
docker build -t operonix:local . && docker run --rm -p 8080:8080 -e ... operonix:local

# Google Cloud (needs gcloud + approval; creates billable resources)
cp deploy/gcp/.env.example deploy/gcp/.env
./deploy/gcp/deploy.sh plan
./deploy/gcp/deploy.sh bootstrap --yes && ./deploy/gcp/deploy.sh build
./deploy/gcp/deploy.sh job --yes && ./deploy/gcp/deploy.sh migrate
./deploy/gcp/deploy.sh deploy --yes && ./deploy/gcp/deploy.sh smoke
# Windows PowerShell equivalents: .\deploy\gcp\deploy.ps1 <command> -Yes
```

This shell exports `PORT=0`, which makes Spring bind a random port — pass `--server.port=8080` (the
`prod` profile already reads `${PORT:8080}`).

---

## 10. Deviations from HLD / LLD / ERD

Everything from session 2 still stands (see `docs/LLD.md` §15). Additions from session 3:

- **npm, not pnpm.** The repository already used npm with a committed `package-lock.json`; the task
  brief listed pnpm, but switching would add a second lockfile and risk the verified build for no
  benefit. CI and Docker run `npm ci`.
- **Same-origin hosting added `SpaRootController`** (not anticipated in the designs): Spring's
  resource handler cannot resolve an empty path, so `/` needs an explicit forward.
- **CSP now allows the two Google Fonts origins**; without it the production shell loads no fonts.
- **`spring.web.resources.add-mappings=false`** so exactly one `/**` handler exists.
- **`application-prod.yml` requires `OPERONIX_JWT_SECRET`** (no default) rather than relying only on
  the empty-string check in `JwtService`.
- **Cloud SQL access uses the Cloud Run `/cloudsql` socket** by default; the Java Connector URL is a
  documented alternative, and the dependency is present so it works without a rebuild.
- **10 s graceful-shutdown phase in `prod`** (base config keeps 20 s) to fit Cloud Run's kill window.
- **Terraform ignores image changes** on the service/job so provisioning and deployment stay separate.
- **No schema, API, or business-rule change** was needed for deployment.

---

## 11. Instructions for the next AI agent

1. Read this file, then `docs/HLD.md`, `docs/LLD.md`, `docs/ERD.md`, then
   `docs/GOOGLE_CLOUD_DEPLOYMENT.md`. Do not re-plan from scratch.
2. The stopping point is §5: deployment artifacts complete and locally verified; **nothing deployed**.
   Continue with the first unfinished step in §5.
3. Never claim the image builds, Terraform applies, CI runs, or GCP works until it has actually been
   executed somewhere — this machine has no Docker, no `gcloud`, no Terraform and no `.git`.
4. Keep the same-origin packaging contract: one image, SPA in `classpath:/static/`, `/api/v1`
   authenticated, shell public, no CORS. If a rule changes, update `docs/LLD.md` §16 and §10 here.
5. If you change anything in `backend/`, run `mvn test` (30 tests) before finishing. If you change
   `frontend/`, run `npm run typecheck && npm run build`.
6. Secrets never go in the repository, in `deploy/gcp/.env`, in docs, or in an image. Production
   values come from Secret Manager only. Never paste a real secret into chat.
7. Deployment-side changes (`Dockerfile`, `cloudbuild.yaml`, `deploy/`, `infra/terraform/`,
   `.github/workflows/`) need human review; a wrong flag can take a live service down or widen IAM.
8. Do not create Google Cloud resources, enable billing, or deploy without explicit user approval.
9. Before ending a session: update §4, §5, §7, §8, check that `docs/PRODUCTION_CHECKLIST.md` still
   reflects reality, and record the exact next command to run.

---

## 12. Product evolution analysis (session 4) — COMPLETE, AWAITING APPROVAL

**State:** analysis and documentation only. **No application code, migration, dependency, API contract or
cloud resource was created or modified in this session.** The only files written are Markdown under
`docs/product-evolution/` plus this section.

**Deliverables (10 files, ~3,000 lines):**

| File | Contents |
| --- | --- |
| `docs/product-evolution/README.md` | Index, reading order, final recommendations (12 items), what was/was not verified, approval statement |
| `docs/product-evolution/GLOBAL_PRODUCT_VISION.md` | Repositioning, segments/personas, philosophy, **verified list of cleaning-specific assumptions**, market strategy, industry-independence definition, success criteria, owner decisions |
| `docs/product-evolution/ARCHITECTURE_IMPACT_ANALYSIS.md` | Current architecture (as built), target architecture with Mermaid diagrams, impact matrix (severity/complexity/dependencies/action), detailed change records, what stays unchanged, scalability, deferrals |
| `docs/product-evolution/GLOBALIZATION_AND_LOCALIZATION.md` | Currency model, tax model, numbering/entities/fiscal year, addresses, dates/locales, language, regional config, universal vs country-specific, residency, migration, testing, phasing |
| `docs/product-evolution/BUSINESS_INTELLIGENCE_DESIGN.md` | Current reporting baseline, revenue concept separation, metric catalogue (formulas), API contracts, UX, aggregation architecture, multi-currency/timezone, performance, indexing, isolation, testing |
| `docs/product-evolution/OPERONIX_AI_DESIGN.md` | Principles, architecture with Mermaid, **semantic metric layer as the security boundary**, provider abstraction, 4 use cases (ask, quote assist, insights, automation), security, cost/quotas, data model, failure handling, evaluation, phasing |
| `docs/product-evolution/DATA_MODEL_IMPACT_ANALYSIS.md` | Verified 15-table baseline, gaps, proposed model overview diagram, new reference tables, field-level changes per table, **proposed ER diagram (Mermaid)**, tenant-isolation rules, indexing plan, V2–V9 migration sequence, explicit deferrals, verification notes |
| `docs/product-evolution/SECURITY_AND_COMPLIANCE_IMPACT.md` | Verified current posture + 7 known weaknesses, AI threat model (A1–A10), authorization matrix, residency levels L0–L3, data-protection controls, jurisdiction matrix (IN/US/UK/JP), **architecture vs legal-certification boundary**, hardening backlog H1–H11, required tests |
| `docs/product-evolution/IMPLEMENTATION_ROADMAP.md` | P0/P1a/P1b/P2a/P2b/P3 with effort, dependencies, exit gates; effort totals and critical path; risk register R1–R10; per-phase testing; milestones M0–M5; keep-vs-evolve comparison; cost considerations; D1–D8 owner decisions; consequence of doing nothing |
| `docs/product-evolution/ARCHITECTURAL_DECISIONS.md` | ADR-01…ADR-14 (context/decision/alternatives/consequences/reversibility/phase) + approval checklist |

**Key findings that shape any future work:**

1. **No cost data exists** anywhere in the schema — job profitability is not computable. `work_log`,
   `expense`, `vendor`, `job_cost` must land before any profit metric (P1b before the profitability parts of P2a).
2. **Money model is single-currency and 2-dp fixed**: `tenant.currency` is a 6-value CHECK, `MoneyCalculator.MONEY_SCALE = 2`.
   Multi-currency and JPY (0 dp) require the P1a refactor. Characterization tests must be written first.
3. **Tax is one percentage per item** (`service_item.tax_rate`); inclusive tax and CGST/SGST splits are not
   expressible. Solution is data-driven `tax_definition`/`tax_component`/`tax_profile` + `line_tax_component`.
4. **`app_user.email` is globally unique** — a defect that blocks multi-workspace membership (P0-3).
5. **Login rate limiting is in-process**, so it multiplies with Cloud Run replicas (P0-4).
6. **AI must never generate SQL.** The design is validated intent → deterministic tenant-scoped query → facts → narration.
7. Existing strengths to preserve: shared-schema tenancy with mandatory `tenant_id`, snapshot money lines,
   locked per-tenant document sequences, `audit_event`, stateless JWT + hashed refresh tokens.

**Recommended start on approval:** P0-1…P0-3 (industry neutrality + seed/template profiles + tenant-scoped
email). Stop again before P1a until D1 (market order) and D2 (inclusive-pricing default) are answered —
both change the tax calculator's contract.

**Nothing was run in this session except file reads/writes:** no `mvn`, no `npm`, no Docker, no `gcloud`.
The test state is unchanged from session 3 (backend 30 tests green, frontend typecheck + build green).

**Instruction for the next agent:** do not implement any proposal in `docs/product-evolution/` without
explicit approval of the relevant ADR (see `ARCHITECTURAL_DECISIONS.md` approval checklist) and decisions
D1–D8. The baseline documents `docs/HLD.md`, `docs/LLD.md`, `docs/ERD.md` describe the **current** system
and were deliberately not rewritten; the evolution documents describe the **proposed** target.
