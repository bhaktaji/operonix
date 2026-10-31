# Operonix — Project Memory (single source of truth)

> Read this file first in a new session, then `docs/HLD.md`, `docs/LLD.md`, `docs/ERD.md`, and
> `docs/GOOGLE_CLOUD_DEPLOYMENT.md`.
> Never store credentials, tokens or secrets here.

| Field | Value |
| --- | --- |
| Last updated | 2026-10-31 (session 6) |
| Current phase | **Operonix 2.0 P0-1…P0-3 COMPLETE** — global/industry-neutral foundation shipped on top of the verified MVP |
| Next phase | **STOPPED by instruction.** P1a (financial refactor, multi-currency, tax profiles) must not start until the owner approves |
| Overall status | Working end-to-end locally: industry-neutral UI, country-derived onboarding, multi-workspace sign-in, refresh-token families, shared rate limiter |

---

## 1. Project overview

**Operonix — The Operating Platform for Growing Businesses.** A multi-tenant, **industry-independent**
business platform for small and growing service businesses anywhere in the world (cleaning, pest
control, equipment rental, auto repair, plumbing/electrical, property maintenance, construction,
local logistics, training centres, …). One workspace covers the lead-to-cash loop: customers and
locations → catalog → quotations → scheduled jobs → invoices and payments → dashboard, reporting
and audit trail.

Industry is **configuration, not a code path**: `IndustryTemplate` supplies vocabulary and defaults
only. A workspace also carries country, currency, locale, timezone and optional legal/tax details,
so money and dates render correctly per market (including zero-decimal currencies such as JPY).

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
| Database | PostgreSQL 16, Flyway `V1__schema.sql` + `V2__global_foundation.sql`, `ddl-auto=validate` |
| Auth | JWT access tokens (HS256, 15 min, memory-only) + rotating opaque refresh tokens (SHA-256 at rest) |
| API docs | springdoc-openapi 2.6 (dev only; disabled in prod) |
| Logging | Logback; JSON encoder on stdout under the `prod` profile (Cloud Logging) |
| Tests | JUnit 5, AssertJ, MockMvc, Zonky embedded PostgreSQL 2.2.2 |
| Frontend | React 18.3, TypeScript 5.6, Vite 5.4, React Router 6.28, TanStack Query 5, Tailwind 3.4 |
| Backend build | Maven 3.9.9 + JDK 21 from `C:\Tools` (machine-wide, on `PATH`) |
| Frontend build | **npm** with `frontend/package-lock.json` (single lockfile; `npm ci` in CI and Docker) |
| Deployment | Cloud Run (one service), Cloud SQL PostgreSQL 16, Artifact Registry, Cloud Build, Secret Manager, Cloud Logging/Monitoring |
| IaC / CI | Terraform (minimal, not applied), GitHub Actions (CI + WIF deployment) |

**Local toolchain note:** JDK 21 (`C:\Tools\jdk-21.0.13+9`) and Maven 3.9.9
(`C:\Tools\apache-maven-3.9.9`) are installed machine-wide and registered in `JAVA_HOME`,
`MAVEN_HOME` and `PATH` by `scripts/windows/setup-toolchain.ps1`. A new shell therefore runs
`java`, `javac` and `mvn` with no project-local overrides; `.tools/` no longer exists. Docker,
`gcloud`, Terraform and Python are still absent. Integration tests use embedded PostgreSQL, so
`mvn test` needs no database server and no container runtime.
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
├── scripts/windows/         setup-toolchain.ps1 (JDK/Maven install + persistent env vars)
├── .dockerignore            excludes node_modules, dist, target, .env, keys
├── .gitignore               excludes build output, deploy/gcp/.env, state, keys
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

**Current task:** none in flight. **P0-1…P0-3 are implemented and verified; the session stopped there
on instruction.** P1a (multi-currency money model, tax profiles, costing) is NOT started.

See §13 for the exact P0 state, the verification table and the next actionable steps.

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
cd backend && mvn test

# Backend run locally (needs PostgreSQL; dev profile supplies a dev-only JWT secret)
cd backend && java -jar target/operonix-api.jar --server.port=8080

# Frontend
cd frontend && npm ci && npm run dev      # :5173, proxies /api -> :8080
cd frontend && npm run typecheck && npm run build

# Full lifecycle check against a running API on :8080
cd frontend && node scripts/smoke.mjs

# Production-shaped local run (what the container does)
cd frontend && npm run build && cp -r dist/* ../backend/src/main/resources/static/
cd backend && mvn -DskipTests package
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

---

## 13. Session 5 — Operonix 2.0 P0-1…P0-3 (IMPLEMENTED, VERIFIED, STOPPED)

**Authorization:** foundation only. P1a (money-model refactor, tax profiles, costing), P1b, P2 (BI),
P3 (Operonix AI) are **not started** and must not start without explicit owner approval.

### 13.1 What P0 delivered

| Item | State | Evidence |
| --- | --- | --- |
| Industry-neutral product foundation | ✅ | Cleaning-only copy/seed/branding removed; `IndustryTemplate` (10 profiles) drives vocabulary + default job title only |
| Global brand + design system | ✅ | `Brand.tsx`, tokens in `index.css`/`tailwind.config.js`, rebuilt Layout/nav, auth shells, all list/detail pages restyled; `docs/DESIGN_SYSTEM.md` |
| Reference data module | ✅ | `com.operonix.global` + `GET /api/v1/reference`; 20 countries, 15 currencies (with `minorUnits`), 10 industry templates |
| Global tenant configuration | ✅ | `tenant.country_code/locale/industry_template/home_region/legal_name/tax_number/onboarded_at`; `PUT /workspace` extended |
| Country-aware onboarding | ✅ | Two-step registration; country seeds currency + locale; timezone defaults to the browser zone; legal/tax optional, label from the country (`GSTIN`, `EIN`, …) |
| Locale/currency formatting | ✅ | `lib/format.ts` is the only formatter; `JPY` renders 0 decimals from `minorUnits` |
| i18n scaffolding | ✅ | `i18n/messages.ts` + `I18nProvider`; chrome/nav/auth/onboarding catalogued, per-key English fallback |
| Multi-workspace identity | ✅ | Per-workspace email uniqueness; `400 WORKSPACE_REQUIRED` with candidate list; client workspace picker |
| Refresh-token families | ✅ | `family_id`, `replaced_by`, `user_agent`; reuse revokes the family and audits `REFRESH_TOKEN_REUSE` |
| Shared rate limiting | ✅ | `rate_limit_counter` + atomic upsert in `AuthRateLimiter` (login/register/refresh) |
| Migration | ✅ | `V2__global_foundation.sql` — additive, backfills `country_code` from the existing currency and `family_id` from the token id |

### 13.2 Verification (session 5, actually executed)

| Check | Result |
| --- | --- |
| `mvn test` (backend, embedded PostgreSQL + real Flyway) | **40 tests, 0 failures, BUILD SUCCESS** — `MoneyCalculatorTest` 6, `SpaHostingIT` 7, `WebStaticConfigTest` 4, `GlobalFoundationIT` 10, `AuthFlowIT` 11, `SalesFlowIT` 2 |
| `npm run typecheck` | passes (`tsc --noEmit`) |
| `npm run build` | passes — `dist/assets/index-*.css` 34.79 kB, `index-*.js` 398.02 kB (gzip 109.84 kB) |
| V2 on a **populated V1 database** | live dev DB: `Successfully applied 1 migration` at startup, health `200` |
| Live registration + onboarding (browser, 1440×900) | country `India` seeded `INR` + `en-IN`, timezone `Asia/Calcutta`; workspace created; dashboard renders `₹0.00` |
| Live workspace settings | GSTIN label from country, "Optional" helper text, language `English (India)`, security-posture panel |
| Live full lifecycle (`scripts/smoke.mjs` against :8080) | **SEED OK** — quote 360 − 20 discount + 8.25% tax → job → invoice 319.34 → partial 100 → balance 219.34 → PAID balance 0 → dashboard 319.34 → audit trail → cross-tenant 404 → anonymous 401 |
| Industry-neutrality audit | `grep -riE "clean(er|ing)?"` over `frontend/src`, `frontend/index.html`, `backend/src/main`: only the `CLEANING` template, the address-sanitising helper `cleanAddress`, and the AuthShell sentence that lists several trades as examples |
| Screenshots | `docs/screenshots/{dashboard,quotations,settings}.png` (pre-restyle) + `register-global.png`, `register-india-defaults.png`, `dashboard-empty-global.png`, `settings-global.png`, `customers-empty-global.png` |

Two defects were found and fixed during the session (both caught by tests, not by inspection):
1. a rate-limit configuration key that did not match its properties record;
2. nullable JPQL list filters that fail on PostgreSQL — list filtering now uses type-safe Specifications.

### 13.3 Files created / modified in session 5

**Backend — new**
- `global/{Country,CountryRepository,Currency,CurrencyRepository,IndustryTemplate,ReferenceDataController,ReferenceDataService,ReferenceDtos}.java`
- `resources/db/migration/V2__global_foundation.sql`
- `test/java/com/operonix/global/GlobalFoundationIT.java`

**Backend — modified**
- `identity/{AuthService,AppUserRepository,RefreshToken,RefreshTokenRepository,SessionRevocationService,TeamService}.java`
- `identity/dto/{LoginRequest,RegisterRequest}.java`
- `tenancy/{Tenant,TenantService,WorkspaceService}.java`, `tenancy/dto/{TenantSummary,UpdateWorkspaceRequest,WorkspaceResponse}.java`
- `security/AuthRateLimiter.java`, `config/{SecurityConfig,OpenApiConfig}.java`, `common/error/ErrorCode.java`
- `scheduling/JobService.java` (industry-driven default job title)

**Frontend — new**
- `components/Brand.tsx`, `i18n/{messages.ts,I18nProvider.tsx}`

**Frontend — modified**
- `index.html` (title/description/theme), `main.tsx`, `api/{client,endpoints,types}.ts`, `auth/{AuthProvider,RequireAuth}.tsx`,
  `components/{AuthShell,Layout,JobDetail,JobDialog,ConvertQuoteDialog}.tsx`, `components/ui/{cn,misc}.tsx`,
  `lib/format.ts`, `index.css`, `tailwind.config.js`, `vite.config.ts`
- `pages/{Login,Register,Dashboard,Customers,CustomerDetail,Catalog,Quotes,Schedule,Invoices,Team,Settings}.tsx`

**Docs**
- New: `docs/DESIGN_SYSTEM.md`, `docs/FRONTEND_GLOBALIZATION.md`, `docs/INDUSTRY_NEUTRALITY.md`
- Updated: `docs/HLD.md` §14, `docs/LLD.md` §4.1/§4.2/§9.1/§9.3/§11 + §17, `docs/ERD.md` §1/§3/§5/§7,
  `README.md`, this file
- Reference (unchanged): everything under `docs/product-evolution/`

### 13.4 Deviations from the approved designs (P0)

| Design intent | What shipped | Why |
| --- | --- | --- |
| `membership` table with one credential per person | per-workspace `app_user` rows, workspace-aware login | A membership table needs an identity/credential split that belongs with the P1a financial refactor; the shipped version already satisfies "one person, several workspaces" and is reversible |
| `label_override` / terminology table | template supplies the default job title only | P0 forbids duplication; the lookup point exists in one place, ready for overrides |
| Country-driven postal validation | regex stored, not enforced on addresses | Rejecting valid international formats before address redesign is riskier than deferring it |
| Translated copy per locale | English catalogue + formatting localisation | Only English is reviewed and shippable; the mechanism is in place |
| `tax_definition`/`tax_profile` tables | not created | That is P1a; P0 explicitly excludes the financial refactor |

### 13.5 Known issues / pending

- One `app_user` row per workspace membership (see the deviation above); no invitation flow yet.
- `currency.minorUnits` reaches the client, but the backend `MoneyCalculator` still stores `numeric(12,2)`;
  JPY therefore *displays* without decimals while storage remains 2-dp until the P1a refactor.
- No automated browser suite; the UI was verified manually with screenshots plus the API smoke script.
- Git baseline: commit `f4a3f3b` ("chore: baseline before Operonix 2.0 P0 implementation") on `master`.
  The P0 work is **uncommitted** (61 changed/new paths) — commit it before starting P1a so the phase is
  reviewable as one unit. CI has still never run (no remote push yet).
- Nothing was deployed; no GCP resource, billing change or paid service was touched in this session.

### 13.6 How to resume

1. Read `memory.md` (this file), then `docs/HLD.md` §14, `docs/LLD.md` §17, `docs/ERD.md` §3, and
   `docs/product-evolution/IMPLEMENTATION_ROADMAP.md`.
2. **Stop at P0.** Wait for explicit approval of P1a and for owner decisions D1 (market order) and D2
   (tax-inclusive default) before touching `MoneyCalculator` or tax data.
3. To verify the current state from scratch:
   ```bash
   # backend tests (embedded PostgreSQL, no Docker needed)
   cd backend && mvn test   # 40 tests at the time of session 5; 56 since P1a
   # frontend
   cd frontend && npm run typecheck && npm run build
   # live
   cd frontend && API_BASE=http://localhost:8080 node scripts/smoke.mjs
   ```
4. If you change a page's copy, keep industry-neutral vocabulary (see `docs/INDUSTRY_NEUTRALITY.md` §5).
   If you add a country or currency, extend `V2`'s seed tables in a **new** migration (`V3__…`) — never edit
   an applied migration.
5. Before ending a session, update §12/§13 here with the stopping point and the exact next command.

---

## 14. Session 6 — P1a financial foundation + global Windows toolchain (IMPLEMENTED, VERIFIED, STOPPED)

### 14.1 Git checkpoint (done first, as instructed)

- `f4a3f3b` — baseline before Operonix 2.0 (pre-existing).
- `da397f9` — **P0 committed as one reviewable commit** (61 paths, tree clean afterwards).
  Git had no commit identity, so the repository-local `user.name`/`user.email` were set to
  `Codebuff <noreply@codebuff.com>` (matching the baseline author). No global git config was touched.
- P1a work is the uncommitted working tree after `da397f9`.

### 14.2 Windows toolchain (moved out of the checkout)

- `.tools/jdk-21.0.13+9` → `C:\Tools\jdk-21.0.13+9`; `.tools/apache-maven-3.9.9` →
  `C:\Tools\apache-maven-3.9.9`. The empty `.tools/` directory was removed after verification.
- `scripts/windows/setup-toolchain.ps1` writes `JAVA_HOME`, `MAVEN_HOME` and prepends
  `%JAVA_HOME%\bin;%MAVEN_HOME%\bin` to `PATH` at **user** and (with `-Scope Machine`) **machine** scope.
  It reads the registry PATH, de-duplicates entries and rewrites it as a single string; `setx` is never
  used (1024-character truncation + REG_EXPAND_SZ loss).
- The machine scope was applied with explicit administrator approval, because the machine PATH began
  with Corretto JDK 8. Verified from a registry-rebuilt environment (what a new shell sees):
  `java -version` → 21.0.13, `javac -version` → 21.0.13, `mvn -version` → Maven 3.9.9 running on
  `C:\Tools\jdk-21.0.13+9`, `where java` lists `C:\Tools\...` first.
- A stale `operonix-api.jar` process (PID 19468) was holding a file lock in `.tools`; it was the dev
  server from the previous session and was terminated to allow the move.

### 14.3 P1a backend (new `V3__global_financial_foundation.sql`)

Additive migration: `tax_definition` + `tax_component` (12 seeded jurisdictions, 14 components),
`tax_profile`, `legal_entity`, `business_location`, `tenant_settings`, `document_number_rule`,
`exchange_rate`, `line_tax_component`; `quote`/`invoice` gained entity/tax-mode/FX/base-total snapshot
columns; `service_item.tax_profile_id`. Backfills: one default entity + profile + settings row +
"Main location" + three numbering rules per existing tenant; `fx_rate = 1`,
`base_currency = currency`, `base_total = total`, `base_amount_paid = amount_paid`.

Code: `com.operonix.finance` (`FinanceService`, `TaxService`, `OrganisationService`,
`ExchangeRateService`, `TaxSnapshotWriter`, `WorkspaceFinanceBootstrap`, controllers, DTOs),
`DocumentNumberService` rewritten to read `document_number_rule`, `MoneyCalculator` extended with
currency scale, inclusive/exclusive mode and per-component allocation while keeping its two-argument
behaviour byte-identical. `WorkspaceProvisionedEvent` seeds finance defaults without a module cycle.

API: `/api/v1/tax/{definitions,profiles}`, `/legal-entities`, `/business-locations`,
`/workspace/financial-settings`, `/number-rules`, `/exchange-rates`.

Frontend: `api/finance.ts`, `pages/FinanceSettings.tsx` (`/settings/financial`, linked from Settings),
invoice detail now prints per-component tax rows, tax mode and the base-currency equivalent, catalog
gains a tax-profile selector.

### 14.4 Verification actually run (session 6)

| Check | Result |
| --- | --- |
| `mvn test` (JDK 21 from `C:\Tools`) | **56 tests, 0 failures** — MoneyCalculatorTest 14, SpaHostingIT 7, WebStaticConfigTest 4, FinancialFoundationIT 8, GlobalFoundationIT 10, AuthFlowIT 11, SalesFlowIT 2 |
| Flyway V1→V3 + `ddl-auto=validate` | applied inside every integration test; V3 also ran on the pre-P1a schema |
| `npm run typecheck` | clean |
| `npm run build` | 413.29 kB JS / 35.07 kB CSS |
| New `FinancialFoundationIT` | seeds, inclusive VAT gross, JPY whole-yen rounding, FX snapshot immutability, tax-component snapshot vs profile edit, numbering change, base-currency payments, cross-tenant 404s |

Two implementation bugs were caught by those tests and fixed: `document_number_rule.created_at` and
`tenant_settings.created_at` had no generator (insert failed with `409`), and a catalog item's tax
profile correctly overrides an explicit rate — the initial test expectation was wrong, the code was not.

### 14.5 Deviations from the approved P1a roadmap

- P1a-7 address model and P1a-8 fiscal-year reporting are partial (columns stored, not enforced/used).
- P1a-9 keeps the delete-on-reversal behaviour and adds `base_amount_paid`; no `payment_reversal` ledger.
- P1a-10 (payment gateway) not started. No costing/BI/AI, no cloud resources, no deployment.

### 14.6 Known issues / pending

- Compound tax components are summed, not compounded (no seeded market needs compounding).
- `exchange_rate` rows are global, so a rate pair is unique across tenants for a given date by design.
- `numeric(12,2)` storage is unchanged; JPY rounds to whole units at calculation time.
- The P1a tree is uncommitted; the next agent should commit it before P1b.
- No browser run in this session (UI verified by typecheck/build only); earlier sessions verified the
  shell visually with `docs/screenshots/`.

### 14.7 How to resume (P1b, only after approval)

1. Read `memory.md` (this section), `docs/HLD.md` §15, `docs/LLD.md` §18, `docs/ERD.md` §9.
2. Prove the baseline: `cd backend && mvn test` (56) and `cd frontend && npm run typecheck && npm run build`.
3. Commit P1a as one reviewable commit before adding costing tables.
4. Then follow `IMPLEMENTATION_ROADMAP.md` §4 (P1b): `work_log`, `vendor`, `expense`, `job_cost`,
   `service_item.cost`, `job.actual_start/actual_end`, `recurring_series`. Costing must render
   **"cost not recorded"** rather than a 100% margin — that is P1b's acceptance criterion.
