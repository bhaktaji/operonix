# Operonix — Google AI Studio Handover

| Field | Value |
| --- | --- |
| Version | 1.0 |
| Last updated | 2026-10-31 |
| Scope | Using Google AI Studio as a *development* assistant on the existing Java + React repository |
| Source of truth | **GitHub.** Not AI Studio, not a local machine, not this document |

## 0. The short version

AI Studio can be useful for reading and editing this repository with an AI agent. It is **not** the
deployment target for Operonix, and the application must not be rewritten to make its Publish button
apply:

* Operonix is a **Java 21 / Spring Boot 3.3** backend with **PostgreSQL 16** and Flyway, plus a React
  + TypeScript SPA. AI Studio's Publish workflow targets web applications it builds itself (static or
  Node/Cloud Run style). It does not build a Maven project, run Flyway, or provision PostgreSQL.
* Production runs on **Cloud Run + Cloud SQL + Secret Manager** via `cloudbuild.yaml` and
  `deploy/gcp/*`. That path is documented in [GOOGLE_CLOUD_DEPLOYMENT.md](GOOGLE_CLOUD_DEPLOYMENT.md)
  and is unaffected by anything AI Studio does.
* Converting the backend to Node/Firebase merely to satisfy a hosted Publish button would discard the
  verified architecture (JWT + rotating refresh tokens, tenant-scoped JPA queries, Flyway schema,
  the quote→job→invoice state machines) and break every backend test.

Use AI Studio for **code assistance**; use GitHub + Cloud Build/Cloud Run for **delivery**.

> Note on UI labels: AI Studio's repository features are evolving and are not identical across
> accounts/regions. Where this guide names a control, confirm it against the product you actually
> see and against Google's current documentation. Do not assume a capability exists because this
> document mentions it — the validation steps in §5 exist for exactly that reason.

---

## 1. Import the existing GitHub repository

1. Ensure the repository is on GitHub and that the branch you want to work on (`main`) is pushed.
   This workspace currently has **no `.git` directory**, so before any of this: create the repository,
   push, and treat GitHub as canonical from then on.
2. In AI Studio, sign in with the Google account that should own the work, and look for the
   repository/GitHub integration (typically under the app/project creation flow or workspace
   settings).
3. Authorize the GitHub connection for **only this repository** (least privilege — do not grant
   organization-wide access if a single-repository option exists).
4. Import/attach the repository and select the `main` branch.
5. Confirm what was imported: the file tree should contain `backend/pom.xml`,
   `frontend/package.json`, `docs/`, `Dockerfile`, `cloudbuild.yaml`, `deploy/gcp/`. If AI Studio
   imported only a web-app subset (for example just `frontend/`), the repository is **not** fully
   attached — stop and use it read-only instead (§3 fallback).

## 2. Enable two-way GitHub synchronization

Two-way sync means AI Studio can push commits to GitHub. Treat that as a privileged capability:

1. Enable the sync/write permission only on a **dedicated branch** first
   (`ai-studio/experiments`), never directly on `main`.
2. Turn on branch protection on `main` (GitHub → Settings → Branches): require pull requests, require
   the `CI` workflow to pass, and disallow force pushes. This makes it impossible for a sync to
   overwrite reviewed history without review.
3. Verify the direction of sync with a harmless commit: ask the agent to fix a typo in a comment in
   `docs/`, push, and confirm the commit appears on GitHub in the expected branch. Then make a change
   in GitHub and confirm it appears in AI Studio.
4. Decide the conflict policy in advance: **GitHub wins**. If both sides changed the same file, drop
   the AI Studio change, re-import, and redo it as a pull request.

## 3. Instruct the AI Studio agent about the architecture

Paste this into the agent's instructions/system prompt. It is deliberately explicit because an agent
that does not know the layout will "helpfully" restructure the project:

```
You are working in the Operonix repository. Read docs/HLD.md, docs/LLD.md, docs/ERD.md and
memory.md before changing anything. Do not re-plan the architecture.

Stack (do not replace):
- backend/: Java 21, Spring Boot 3.3, Spring Security 6, Spring Data JPA (Hibernate 6),
  Flyway, PostgreSQL 16. Runs on port 8080 (${PORT}).
- frontend/: React 18, TypeScript 5, Vite 5, React Router 6, TanStack Query 5,
  Tailwind 3. Package manager is npm with frontend/package-lock.json (do not introduce
  pnpm/yarn or a second lockfile).
- Tests: JUnit 5 + MockMvc against embedded PostgreSQL 16 (io.zonky). Run `mvn verify`
  in backend/. Frontend: `npm run typecheck` and `npm run build` in frontend/.

Rules:
1. Never introduce Node/Express/Firebase/Firestore or an ORM migration tool other than
   Flyway. PostgreSQL is the only datastore.
2. Keep the modular-monolith package layout under com.operonix.{tenancy,identity,customer,
   catalog,quoting,scheduling,billing,reporting,audit,document,common,config,security}.
   A module calls another module's service, never its repository (reporting is read-only).
3. Tenant isolation: tenant id comes only from the JWT `tid` claim, every tenant-owned query
   is scoped by it, and cross-tenant ids must resolve to 404. Never accept a tenant id from
   a request body or query string.
4. Every endpoint must match docs/LLD.md §4 (path, roles, validation, error codes). If a
   rule changes, update LLD and ERD in the same change and record it in memory.md §10.
5. Money is BigDecimal through common/money/MoneyCalculator; never use double/float, never
   compute totals in the browser as the source of truth.
6. Schema changes require a new forward-only Flyway migration in
   backend/src/main/resources/db/migration. Never edit an applied migration.
7. No secrets in code, docs, fixtures or frontend: the JWT key and database credentials come
   from environment variables or Secret Manager only.
8. Production packaging: one container serves the SPA and /api/v1 (Dockerfile,
   WebStaticConfig, SpaRootController). Do not add a second server or CORS relaxations.
9. After each change run the relevant checks and report actual results; never claim a feature
   is complete without a passing test or a verified manual check.
```

The agent should also be told what *not* to touch: `deploy/`, `infra/terraform/`, `cloudbuild.yaml`
and `.github/workflows/` are deployment contracts; changes there need human review.

## 4. Preserve PostgreSQL and the existing APIs

Concrete guardrails:

* **Schema**: `V1__schema.sql` is the only migration and Hibernate runs `ddl-auto=validate`, so an
  entity change that does not match the schema fails startup — and the tests fail too. Any AI
  suggestion that adds `ddl-auto=update` is wrong.
* **APIs**: `docs/LLD.md` §4 is the contract; the SPA in `frontend/src/api/` is the only client.
  Renaming a resource or changing a response shape requires updating LLD, the typed client
  (`frontend/src/api/types.ts`) and the tests in the same change.
* **Auth**: do not "simplify" to a cookie session, do not store the access token in `localStorage`,
  do not disable the refresh-token rotation. `AuthFlowIT` guards reuse detection and role rules.
* **Tenancy**: any new repository method must include `tenantId`; `TenantEntity` fails writes that
  cross tenants.
* **Frontend**: keep relative `/api/v1` calls so the same-origin deployment keeps working.

A useful review question for every AI Studio branch: *does `mvn verify` still pass, and does
`npm run build` still pass?* If either fails, the change is not ready.

## 5. Validate whether AI Studio's Publish workflow supports this project

Run this check before promising anyone a published build. It is a **validation**, not a step toward
conversion.

| Check | How | Pass condition |
| --- | --- | --- |
| Repository fully imported | Inspect the file tree | `backend/pom.xml` and `frontend/package.json` both present |
| Runtime detection | Ask the agent / read Publish settings | It reports a Java/Maven service **or** explicitly says it cannot build this project |
| Build command | Look for a configurable build/deploy command | You can set `mvn -B -DskipTests package` **and** a container-based deploy |
| Database | Look for PostgreSQL/Cloud SQL support | A managed PostgreSQL 16 connection can be configured and Flyway can run |
| Secrets | Check for secret/environment management | JWT key and DB credentials can be injected without committing them |
| Port | Check the runtime contract | It honours `PORT` and exposes `/actuator/health/readiness` |
| Verdict | — | **Only if every row passes** may Publish be used, and even then as a staging experiment |

Expected outcome for this repository: Publish reports a web/Node-oriented target and cannot build the
Maven module or provision PostgreSQL. That is a **pass** for this validation — it means the decision
"deploy with Cloud Run, not with Publish" is confirmed rather than guessed.

If Publish *does* claim support, verify it with the smoke checks in §6 against the published URL
before trusting it, and never point it at the production database.

## 6. The supported alternative: Cloud Run + Cloud Build

This is the real deployment path; it is already implemented:

```bash
# once per environment
cp deploy/gcp/.env.example deploy/gcp/.env      # set PROJECT_ID, REGION, ENV
./deploy/gcp/deploy.sh bootstrap --yes
./deploy/gcp/deploy.sh build
./deploy/gcp/deploy.sh job --yes
./deploy/gcp/deploy.sh migrate
./deploy/gcp/deploy.sh deploy --yes
./deploy/gcp/deploy.sh smoke
```

On Windows PowerShell the same sequence is `.\deploy\gcp\deploy.ps1 <command> -Yes`. Details,
Terraform and CI (Workload Identity Federation) are covered in
[GOOGLE_CLOUD_DEPLOYMENT.md](GOOGLE_CLOUD_DEPLOYMENT.md) and
[deploy/gcp/README.md](../deploy/gcp/README.md).

Post-deployment validation (also run by CI):

```bash
curl -fsS "$URL/actuator/health/readiness"          # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' "$URL/"    # 200, the SPA shell
curl -s -o /dev/null -w '%{http_code}\n' "$URL/api/v1/customers"   # 401
cd frontend && node scripts/smoke.mjs               # full lifecycle against the live API
```

## 7. Synchronize changes safely

Rules that keep GitHub canonical:

1. **Branch, never main.** AI Studio works on `ai-studio/<topic>`; humans merge through pull
   requests after `CI` passes.
2. **Pull before you push.** Start every AI Studio session by syncing from GitHub; if the local branch
   is behind, discard local edits rather than merging blindly.
3. **Never force-push.** Branch protection on `main` plus no force-push makes overwrites impossible.
4. **Small commits, one concern.** A 40-file "refactor" from an agent is unreviewable and usually
   breaks the module boundaries in §3.
5. **Verify locally after pulling.** `cd backend && mvn verify` and `cd frontend && npm run build`
   must both pass before merging; CI enforces the same.
6. **Deployment files are review-gated.** `deploy/`, `infra/terraform/`, `cloudbuild.yaml`,
   `.github/workflows/`, `Dockerfile` changes need a human reviewer, because a wrong flag can take a
   live service down or widen IAM permissions.
7. **Update `memory.md`** in the same pull request as any architectural decision; it is the handover
   contract for the next session and the next agent.
8. **No secrets in AI Studio.** Never paste a production JWT key, database password, service-account
   JSON or API key into a prompt, a chat file, or a repository file. Rotate anything that leaks.

## 8. What is intentionally *not* in AI Studio

* No production secrets, no service-account keys, no Cloud SQL credentials.
* No deployment authority: production rollouts run through Cloud Build with Workload Identity
  Federation (`.github/workflows/deploy.yml`), not through an assistant session.
* No parallel copy of the schema or of the business rules. If a rule changes, it changes in
  `docs/LLD.md`, the code and the tests together, in one reviewed pull request.