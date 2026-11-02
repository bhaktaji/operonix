# Operonix — Local Setup & Developer Onboarding Guide

This guide takes you from *"I have never run a Java project"* to *"Operonix is running on my laptop and
I have created my first workspace."* Every command below was taken from this repository's actual
configuration; nothing here is invented. Where a step could not be executed on the machine that wrote
this guide, it is marked **Not verified** and you are told exactly how to check it yourself.

Read the sections in order the first time. Later you can jump straight to
[Troubleshooting](#10-troubleshooting).

---

## Contents

1. [What is Operonix?](#1-what-is-operonix)
2. [Prerequisites](#2-prerequisites)
3. [Clone the repository](#3-clone-the-repository)
4. [Database setup](#4-database-setup)
5. [Environment configuration](#5-environment-configuration)
6. [Run the backend](#6-run-the-backend)
7. [Run the frontend](#7-run-the-frontend)
8. [First-time application usage](#8-first-time-application-usage)
9. [Build and test](#9-build-and-test)
10. [Troubleshooting](#10-troubleshooting)
11. [Useful developer reference](#11-useful-developer-reference)
12. [What was verified while writing this guide](#12-what-was-verified-while-writing-this-guide)

---

## 1. What is Operonix?

Operonix is business software for small service companies — cleaning crews, plumbers, workshops,
maintenance teams. It keeps one loop in one place:

**customer → quotation → scheduled job → invoice → payment → insight**

The point that separates it from a spreadsheet is the cost data underneath: it answers *"did we
actually make money on that job?"*, not only *"did the customer pay?"*.

It is **multi-tenant** (one running server serves many independent businesses, called *workspaces*),
**industry-neutral** (the trade vocabulary comes from a template, not from hardcoded screens) and
**global** (country, currency, tax and time zone are configuration).

### 1.1 The three pieces

| Piece | Technology | What it does | Where it runs locally |
| --- | --- | --- | --- |
| **Frontend** | React 18 + TypeScript, built by Vite | The screens you click. Talks to the backend over HTTP. | `http://localhost:5173` (Vite dev server) |
| **Backend** | Java 21 + Spring Boot 3.3 | Business rules, authentication, calculations, database access. Exposes a JSON REST API under `/api/v1`. | `http://localhost:8080` (default) |
| **Database** | PostgreSQL 16 | Stores every workspace's data permanently. | `localhost:5432` |

### 1.2 How they talk to each other

```
   Your browser
        │
        │  http://localhost:5173  (HTML, CSS, JavaScript)
        ▼
 ┌───────────────────────┐        /api/...  proxied        ┌────────────────────────┐
 │  Vite dev server      │ ─────────────────────────────▶ │  Spring Boot API       │
 │  (React app)          │                                │  (Java, port 8080)     │
 └───────────────────────┘                                └───────────┬────────────┘
                                                                      │ JDBC
                                                                      ▼
                                                          ┌────────────────────────┐
                                                          │  PostgreSQL 16         │
                                                          │  database: operonix    │
                                                          └────────────────────────┘
```

Two details that confuse beginners, so they are stated early:

- **The browser never talks to the database.** Only the Java backend does, using a library called JDBC.
- **In development the browser does not call `:8080` directly.** It calls `:5173` (the Vite dev server),
  and Vite forwards anything starting with `/api` to the backend (`frontend/vite.config.ts`). This is why
  there are no CORS problems locally. In production one server serves both the files and the API.

---

## 2. Prerequisites

You need six tools. Each row explains **what it is**, **which version this project needs**, and **how to
prove it is installed**.

| Tool | Version required by this project | Why you need it |
| --- | --- | --- |
| **Git** | 2.40 or newer (verified on 2.56.0) | Downloads (clones) the code and tracks your changes. |
| **JDK** (Java Development Kit) | **Java 21** — set in `backend/pom.xml` (`<java.version>21</java.version>`) | Compiles and runs the backend. A JRE alone is not enough. |
| **Maven** | **3.9 or newer** (verified on 3.9.9) | Builds the backend and downloads its Java libraries. The repository has **no** `mvnw` wrapper, so you must install Maven. |
| **Node.js** | **20 LTS or newer** (verified on 26.12.1). Vite 5 needs at least Node 18. | Runs the frontend tooling and the dev server. |
| **npm** | Ships with Node.js (verified on 11.20.0) | Installs frontend libraries. This project commits `frontend/package-lock.json`; it does **not** use pnpm or Yarn. |
| **PostgreSQL** | **16** (the test suite pins 16.15 binaries) | The database. |

> **About pnpm:** some tutorials tell you to use `pnpm`. This repository has no `pnpm-lock.yaml`, so use
> **npm** exactly as written below. Mixing package managers produces two lockfiles and inconsistent
> installs.

### 2.1 Windows (PowerShell) — recommended path

Open **Windows Terminal** or **PowerShell** (press `Win`, type `powershell`, press Enter). Commands in this
guide are for PowerShell unless a block says otherwise.

**Option A — winget (fastest).** Package identifiers come from the Microsoft winget repository and can
change; if one is not found, use Option B for that tool.

```powershell
winget install --id Git.Git -e
winget install --id EclipseAdoptium.Temurin.21.JDK -e
winget install --id Apache.Maven -e
winget install --id OpenJS.NodeJS.LTS -e
winget install --id PostgreSQL.PostgreSQL.16 -e
```

**Option B — official installers.**

| Tool | Where to download |
| --- | --- |
| Git for Windows | <https://git-scm.com/download/win> |
| Eclipse Temurin JDK 21 | <https://adoptium.net/temurin/releases/?version=21> |
| Apache Maven | <https://maven.apache.org/download.cgi> (the "Binary zip archive") |
| Node.js LTS | <https://nodejs.org/en/download> |
| PostgreSQL 16 | <https://www.postgresql.org/download/windows/> (the EDB installer) |

Manual-install notes:

- **Maven is a zip, not an installer.** Extract it, for example to `C:\Tools\apache-maven-3.9.9`, then add
  `C:\Tools\apache-maven-3.9.9\bin` to your `Path` (Start → "Edit the system environment variables" →
  Environment Variables → User variables → `Path` → New).
- **The PostgreSQL installer asks for a superuser password.** Write it down; you need it in
  [section 4](#4-database-setup). It also installs `psql`, usually at
  `C:\Program Files\PostgreSQL\16\bin`. Add that folder to `Path` so `psql` works anywhere.
- **Close and reopen PowerShell after installing.** A terminal keeps the `Path` it started with, so new
  tools look "missing" until you open a new window.

### 2.2 Verify every installation

Run these one at a time. The first line of each output is shown so you know what success looks like.

```powershell
git --version
# git version 2.56.0.windows.2      (any 2.40+ is fine)

java -version
# openjdk version "21.0.13" 2026-10-20 LTS
# If you see 17 or 11, the wrong JDK is first on your PATH — fix that before continuing.

mvn -v
# Apache Maven 3.9.9 (...)
# Java version: 21.0.13

node -v
# v22.x or v26.x — anything 20 or newer

npm -v
# 10.x or 11.x

psql --version
# psql (PostgreSQL) 16.x
```

If a command says *"is not recognized as the name of a cmdlet"*, the tool is either not installed or its
folder is not on `Path` — see [Troubleshooting](#10-troubleshooting).

### 2.3 macOS

```bash
# Homebrew (https://brew.sh) installs all six
brew install git openjdk@21 maven node postgresql@16

# Homebrew's JDK is keg-only; put it on PATH for this shell
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"

# Start PostgreSQL now and on login
brew services start postgresql@16

# Verify
git --version; java -version; mvn -v; node -v; npm -v; psql --version
```

Add the `export PATH=...` line to `~/.zshrc` if you want it to persist.

### 2.4 Linux (Debian / Ubuntu)

```bash
sudo apt update
sudo apt install -y git openjdk-21-jdk maven nodejs npm postgresql-16 postgresql-client-16
git --version; java -version; mvn -v; node -v; npm -v; psql --version
```

If your distribution's repositories do not carry `openjdk-21-jdk` or `postgresql-16`, use the Adoptium and
PostgreSQL APT repositories, or a Node version manager for Node 20+.

---

## 3. Clone the repository

### 3.1 Where to put it

Pick a folder **without spaces or OneDrive sync**, for example `C:\dev`. Cloud-synced folders cause file
locks (a running build holds files open, OneDrive tries to sync them, and Windows build errors appear).

```powershell
mkdir C:\dev
cd C:\dev
git clone https://github.com/bhaktaji/operonix.git
cd operonix
```

Expected result:

```
Cloning into 'operonix'...
remote: Enumerating objects: ...
Receiving objects: 100% ...
```

Check you are on the main branch and the history loaded:

```powershell
git status
# On branch master
# nothing to commit, working tree clean

git log --oneline -3
```

### 3.2 Open the project

- **VS Code:** `code .` from `C:\dev\operonix` (or File → Open Folder).
- **IntelliJ IDEA:** File → Open → select the `backend` folder (it is a Maven project).
- **Terminal:** keep one PowerShell tab at the repository root. You will `cd` into `backend` and
  `frontend` for different commands — this guide always says which.

### 3.3 The folders that matter

```
operonix/
├── backend/                  Java / Spring Boot API
│   ├── pom.xml               dependencies + Java version (21)
│   └── src/
│       ├── main/java/com/operonix/
│       │   ├── config/       security, OpenAPI, static-file serving
│       │   ├── security/     JWT filter, tenant context
│       │   ├── identity/     register / login / refresh / logout, team
│       │   ├── customer/     customers and their locations
│       │   ├── catalog/      services and prices
│       │   ├── quoting/      quotations
│       │   ├── scheduling/   jobs, recurring series, time zones
│       │   ├── billing/      invoices and payments
│       │   ├── global/       currency, tax, costing, reference data
│       │   ├── analytics/    metric catalogue and rollups
│       │   ├── ai/           Operonix AI (grounded answers)
│       │   └── audit/        append-only audit trail
│       ├── main/resources/
│       │   ├── application.yml, application-dev.yml, application-prod.yml
│       │   └── db/migration/ Flyway SQL: V1 … V8
│       └── test/             unit + integration tests
├── frontend/                 React app
│   ├── package.json          scripts: dev, build, typecheck, preview
│   ├── vite.config.ts        port 5173 + /api proxy
│   ├── scripts/              demo-seed.mjs, smoke.mjs
│   └── src/
│       ├── api/              fetch client, endpoint wrappers, types
│       ├── auth/             session provider and route guards
│       ├── pages/            one file per screen
│       └── components/       layout, dialogs, UI primitives
├── docs/                     HLD, LLD, ERD and this guide
├── compose.yaml              optional PostgreSQL container
├── Dockerfile                production image (API + built SPA in one jar)
└── memory.md                 project progress and handover notes
```

---

## 4. Database setup

The backend will not start without a reachable PostgreSQL 16 database. Choose **one** of the two options.

### Option A — Docker (least typing, Windows/macOS/Linux)

The repository ships `compose.yaml`, which starts PostgreSQL 16 with the exact database, user and
password the backend expects. Requires Docker Desktop.

```powershell
cd C:\dev\operonix
docker compose up -d db

docker compose ps
# NAME           STATUS                   PORTS
# operonix-db    Up ... (healthy)         0.0.0.0:5432->5432/tcp
```

### Option B — Native PostgreSQL 16 (no Docker)

The installer registers a Windows service that starts automatically. Confirm it is running:

```powershell
Get-Service postgresql*
# Status   Name               DisplayName
# Running  postgresql-x64-16  postgresql-x64-16 - PostgreSQL Server 16
```

If it is stopped: `Start-Service postgresql-x64-16` (run PowerShell as Administrator).

macOS: `brew services start postgresql@16`. Linux: `sudo systemctl start postgresql`.

### 4.1 Create the database and user

The defaults baked into `backend/src/main/resources/application.yml` are:

| Setting | Default | Where it comes from |
| --- | --- | --- |
| Database name | `operonix` | `OPERONIX_DB_URL` → `jdbc:postgresql://localhost:5432/operonix` |
| Username | `operonix` | `OPERONIX_DB_USER` |
| Password | `operonix` | `OPERONIX_DB_PASSWORD` |
| Host / port | `localhost` / `5432` | — |

These are **local development values only**. Using them keeps you on the documented defaults; you may
choose your own, but then you must set the three `OPERONIX_DB_*` variables from
[section 5](#5-environment-configuration).

Connect as the PostgreSQL superuser (`postgres`) and run the SQL:

```powershell
psql -U postgres -h localhost
```

```sql
-- Inside psql. Replace 'operonix' with a password of your choice if you prefer.
CREATE USER operonix WITH PASSWORD 'operonix';
CREATE DATABASE operonix OWNER operonix;
GRANT ALL PRIVILEGES ON DATABASE operonix TO operonix;
\q
```

PostgreSQL 15 and newer removed the default `CREATE` right on the `public` schema for other users. Because
the database is **owned by `operonix`**, that is not a problem here. If you created it under another owner,
also run, connected to the `operonix` database:

```sql
\c operonix
GRANT ALL ON SCHEMA public TO operonix;
```

Verify the login works (this proves the password, the port and the database name all at once):

```powershell
psql -U operonix -h localhost -d operonix -c "select current_database(), current_user, version();"
```

Expected: one row showing `operonix | operonix | PostgreSQL 16.x ...`.

> **Not verified:** the SQL above was not executed while writing this guide because this machine has no
> `psql` client and already had a database provisioned. The statements are standard PostgreSQL and the
> backend's own connection was verified (`/actuator/health` returned `UP`). If any statement fails, read
> the error text — it usually names the missing privilege or the existing object.

### 4.2 How the schema is created (Flyway)

You do **not** create tables by hand. On every backend start, Flyway checks the database and applies any
migration it has not seen:

- Location: `backend/src/main/resources/db/migration/`
- Files: `V1__schema.sql` … `V8__scheduling_timezone_and_payment_idempotency.sql`
- Trusted schema history table: `flyway_schema_history` (Flyway creates and maintains it)

`spring.jpa.hibernate.ddl-auto` is `validate`. Hibernate therefore **never creates or alters tables**; it
only checks that the entities match what the migrations built. If a migration is missing, startup fails
with a validation error — that is intentional, so the schema cannot drift silently.

### 4.3 Reset the database during development

Only when you want a clean slate, and only locally (**this deletes all local data —
workspaces, customers, invoices, payments, audit history**):

```powershell
psql -U postgres -h localhost -d postgres -c "DROP DATABASE operonix WITH (FORCE);"
psql -U postgres -h localhost -d postgres -c "CREATE DATABASE operonix OWNER operonix;"
```

Docker users can instead remove the volume (`docker compose down -v`) and start the container again. The
next backend start re-applies V1→V8 from scratch.

Never edit a migration that has already run: Flyway stores a checksum and will refuse to start
("Migration checksum mismatch"). Add a new `V9__...sql` instead.

### 4.4 Database troubleshooting

| Symptom | Meaning | Fix |
| --- | --- | --- |
| `Connection to localhost:5432 refused` | Nothing is listening — service stopped, container down, or wrong port. | `Get-Service postgresql*`, or `docker compose up -d db`, or check `OPERONIX_DB_URL`. Another program may own 5432: `netstat -ano | findstr :5432`. |
| `FATAL: password authentication failed for user "operonix"` | Password mismatch. | Reset it: `psql -U postgres -c "ALTER USER operonix WITH PASSWORD 'operonix';"`, or change `OPERONIX_DB_PASSWORD`. |
| `FATAL: database "operonix" does not exist` | User exists, database does not. | Re-run the `CREATE DATABASE` statement. |
| `permission denied for schema public` | Database created under a different owner. | `GRANT ALL ON SCHEMA public TO operonix;` as described above. |
| `Flyway ... Validate failed: Migration checksum mismatch` | An already-applied migration file was edited. | Restore the file (`git checkout -- backend/src/main/resources/db/migration`), or reset the local database (§4.3). |
| `Schema-validation: missing table [...]` | Migrations did not run or were only partly applied. | Check the start-up log for the Flyway line; reset and restart. |
| `Flyway ... Found non-empty schema ... without schema history table` | The database has tables Flyway did not create. | Point the app at a fresh database, or drop the stray schema in a local database. |

---

## 5. Environment configuration

### 5.1 How configuration works in this project

There are two independent configuration systems. Mixing them up is the most common beginner mistake.

**Backend (Spring Boot)** reads `backend/src/main/resources/application.yml` (shared defaults), plus
`application-dev.yml` or `application-prod.yml` when that profile is active, and lets **environment
variables override** every value. The backend does **not** read a `.env` file — there is no dotenv support
in this project. So you set variables in the shell before starting it, or in your IDE's run
configuration.

**Frontend (Vite)** reads variables prefixed `VITE_` from files in `frontend/` (`frontend/.env`,
`frontend/.env.local`) or from the shell. Only `VITE_*` values reach the browser, and anything you put
there is public — never a secret.

`.gitignore` excludes `.env` and `.env.*` (except the tracked `.env.example`), so local files cannot be
committed by accident.

### 5.2 The safe minimum for local development

If you followed §4 with the default database and use the `dev` profile, **you need to configure nothing**:
`application-dev.yml` supplies a development-only JWT signing secret.

To be explicit anyway (recommended, because it teaches the mechanism), use a separate PowerShell window:

```powershell
$env:OPERONIX_JWT_SECRET = "local-dev-only-please-change-0123456789abcdef"
```

That value is 45 characters; the backend requires **at least 32 bytes** and refuses to start otherwise.

### 5.3 Complete variable reference

Every variable below exists in `backend/src/main/resources/application*.yml`. "Default" is what you get
with no variable set. Examples are **placeholders**, not real credentials.

| Variable | Purpose | Required? | Default / example |
| --- | --- | --- | --- |
| `OPERONIX_DB_URL` | JDBC address of PostgreSQL. | No | `jdbc:postgresql://localhost:5432/operonix` |
| `OPERONIX_DB_USER` | Database username. | No | `operonix` |
| `OPERONIX_DB_PASSWORD` | Database password. | No | `operonix` (local only) |
| `OPERONIX_DB_POOL` | Hikari connection-pool size. | No | `10` |
| `OPERONIX_JWT_SECRET` | HS256 signing key for access/refresh tokens, **≥ 32 bytes**. | **Yes** outside the `dev` profile | dev profile: a public dev-only constant; prod: your own long random string |
| `OPERONIX_JWT_ISSUER` | `iss` claim value. | No | `operonix` |
| `OPERONIX_JWT_AUDIENCE` | `aud` claim value. | No | `operonix-web` |
| `OPERONIX_ACCESS_TTL` | Access-token lifetime (short). | No | `15m` |
| `OPERONIX_REFRESH_TTL` | Refresh-token lifetime. | No | `30d` |
| `OPERONIX_CORS_ORIGINS` | Comma-separated browser origins allowed to call the API directly. | No | `http://localhost:5173` |
| `OPERONIX_API_DOCS` | Enables Swagger UI and the OpenAPI JSON. | No | `true` |
| `OPERONIX_STATIC_LOCATIONS` | Where the built SPA bundle is served from. | No | `classpath:/static/` |
| `PORT` | HTTP port of the backend. | No | `8080` |
| `OPERONIX_AI_ENABLED` | Kill switch for the AI provider call path. | No | `true` |
| `OPERONIX_AI_PROVIDER` | Provider adapter name. | No | `gemini` |
| `OPERONIX_AI_API_KEY` | Provider key. Empty means built-in explanations, not an error. | No | *(empty)* |
| `OPERONIX_AI_MODEL` | Model id sent to the provider. | No | `gemini-2.0-flash` |
| `OPERONIX_AI_BASE_URL` | Provider base URL. | No | `https://generativelanguage.googleapis.com` |
| `OPERONIX_AI_TIMEOUT` | Per-call timeout. | No | `20s` |
| `OPERONIX_AI_MAX_OUTPUT_TOKENS` | Maximum answer length. | No | `700` |
| `OPERONIX_AI_DAILY_LIMIT` | Per-workspace questions per day (default quota for new workspaces). | No | `100` |
| `OPERONIX_ANALYTICS_RUN_ON_STARTUP` | Rebuild analytics rollups at boot, then optionally exit (batch job). | No | `false` |
| `OPERONIX_ANALYTICS_EXIT_AFTER_RUN` | Exit after that rollup pass. | No | `false` |
| `OPERONIX_ANALYTICS_SCHEDULER` | Enable the in-process nightly rollup scheduler. | No | `false` |
| `OPERONIX_ANALYTICS_CRON` | Schedule for it (six-field Spring cron). | No | `0 15 2 * * *` |

Frontend variables:

| Variable | Purpose | Required? | Default |
| --- | --- | --- | --- |
| `VITE_API_PROXY_TARGET` | Where the Vite dev server forwards `/api` and `/actuator`. Change it if the backend is not on `:8080`. | No | `http://localhost:8080` |
| `VITE_API_URL` | Absolute API base used by the browser, bypassing the proxy. Leave it unset for local work. | No | `/api/v1` |

### 5.4 Example: running the API on port 8090

Port 8080 is popular; if something already uses it, move the backend rather than fighting for the port.

```powershell
# Terminal 1 — backend
cd C:\dev\operonix\backend
$env:PORT = "8090"
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

```powershell
# Terminal 2 — frontend, told where the API moved
cd C:\dev\operonix\frontend
$env:VITE_API_PROXY_TARGET = "http://localhost:8090"
npm run dev
```

You can persist the proxy target in `frontend/.env.local` instead (it is git-ignored):

```
VITE_API_PROXY_TARGET=http://localhost:8090
```

**Do not** put the JWT secret, an AI key or a database password in a `VITE_*` variable. Vite bakes those
into the JavaScript bundle, where any visitor can read them.

---

## 6. Run the backend

All backend commands run **from `C:\dev\operonix\backend`**. `pom.xml` lives there; running Maven from the
repository root fails with "no POM in this directory".

### 6.1 Download dependencies (first time)

```powershell
cd C:\dev\operonix\backend
mvn -DskipTests dependency:go-offline
```

This fills your local Maven cache (`C:\Users\<you>\.m2\repository`). It needs internet access the first
time and may take several minutes.

### 6.2 Build

```powershell
mvn -DskipTests package
```

Expected tail:

```
[INFO] BUILD SUCCESS
[INFO] Building jar: C:\dev\operonix\backend\target\operonix-api.jar
```

The artifact is named `operonix-api.jar` because `pom.xml` sets `<finalName>operonix-api</finalName>`.

### 6.3 Start it in development mode

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile is what supplies the local JWT secret and DEBUG logging. Without a profile the app
refuses to start: *"OPERONIX_JWT_SECRET is required (minimum 32 bytes)"*.

### 6.4 What a healthy start-up looks like

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.3.13)

... HikariPool-1 - Start completed.
... Flyway Community Edition ... Database: jdbc:postgresql://localhost:5432/operonix (PostgreSQL 16.x)
... Successfully applied 8 migrations to schema "public", now at version v8
    (first run; later runs say "Schema \"public\" is up to date. No migration necessary.")
... Tomcat started on port 8080 (http) with context path ''
... Started OperonixApplication in 6.4 seconds (process running for 7.1)
```

If you see `Successfully applied 8 migrations` **and** `Tomcat started`, the backend and database are
correct.

### 6.5 URLs to check

| Purpose | URL | Expected |
| --- | --- | --- |
| Health | <http://localhost:8080/actuator/health> | `{"status":"UP","groups":["liveness","readiness"]}` |
| Readiness probe | <http://localhost:8080/actuator/health/readiness> | `{"status":"UP"}` |
| Swagger UI (browsable API docs) | <http://localhost:8080/swagger-ui.html> | The Operonix API page |
| OpenAPI JSON | <http://localhost:8080/v3/api-docs> | A large JSON document |
| Public reference data | <http://localhost:8080/api/v1/reference> | Countries, currencies, industry templates |
| Protected check (should fail) | <http://localhost:8080/api/v1/customers> | `401` with a JSON problem body — proof authentication is on |

On port 8090 these become `http://localhost:8090/...`. All five were verified while writing this guide.

### 6.6 Stop the backend

Press `Ctrl + C` in the terminal running it. Spring shuts down gracefully (`server.shutdown: graceful`,
20-second timeout). On Windows, if you closed the window and port 8080 stays busy, find and stop the
process — see [Troubleshooting](#10-troubleshooting).

---

## 7. Run the frontend

All frontend commands run **from `C:\dev\operonix\frontend`**.

### 7.1 Install dependencies

```powershell
cd C:\dev\operonix\frontend
npm ci
```

`npm ci` installs exactly the versions in `frontend/package-lock.json` — use it, not `npm install`, when
the lockfile is present. Expect a few hundred package names and a final `added N packages` line.

### 7.2 Start the dev server

```powershell
npm run dev
```

Expected:

```
  VITE v5.4.x  ready in 412 ms

  ➜  Local:   http://localhost:5173/
  ➜  Network: use --host to expose
  ➜  press h + enter to show help
```

Open **<http://localhost:5173/>** in your browser. That is the application. There is only one frontend
app — no admin portal, no second port.

### 7.3 How the frontend finds the backend

- `frontend/src/api/client.ts` uses `VITE_API_URL` if set, otherwise the relative path `/api/v1`.
- `frontend/vite.config.ts` proxies `/api` **and** `/actuator` to `VITE_API_PROXY_TARGET`
  (default `http://localhost:8080`), stripping the browser's `Origin` header so the call looks
  same-origin to Spring Security's CORS filter.
- Because the browser only ever sees `:5173`, local development needs no CORS configuration at all.

If the backend is not running, the UI still loads but every screen shows
*"Cannot reach the Operonix API. Check that the backend is running."* — that message is the fastest
signal that step 6 failed.

### 7.4 Stop the frontend

`Ctrl + C` in its terminal.

### 7.5 Production-style preview (optional)

```powershell
cd C:\dev\operonix\frontend
npm run build
npm run preview
```

`npm run build` (`tsc -b && vite build`) writes `frontend/dist/`. `npm run preview` serves that folder on
`http://localhost:4173`, but it does **not** proxy `/api`, so API calls fail there. To run the real
single-origin production shape, build the frontend and copy `dist/` into the backend's static resources
before packaging — exactly what the `Dockerfile` does:

```powershell
cd C:\dev\operonix\frontend
npm run build
New-Item -ItemType Directory -Force ..\backend\src\main\resources\static | Out-Null
Copy-Item -Recurse -Force .\dist\* ..\backend\src\main\resources\static\
cd ..\backend
mvn -DskipTests package
java -jar target\operonix-api.jar --spring.profiles.active=dev
```

Now `http://localhost:8080` serves the same app and API from one origin. The copied files land in
`backend/src/main/resources/static/`, which is **not** git-ignored, so `git status` will list them as
untracked. Remove that folder when you are done experimenting so they never reach a commit:

```powershell
Remove-Item -Recurse -Force ..ackendsrcmainesourcesstatic
```

---

## 8. First-time application usage

### 8.1 Create the first workspace (registration)

1. Open <http://localhost:5173/>. Because you have no session, you land on **`/login`**.
2. Choose **Create workspace** (link to `/register`).
3. **Step 1 — business details:**
   - **Business name** (at least 2 characters), e.g. `Harbor Cleaning Co`
   - **Country** — drives default currency and number/date formatting
   - **Industry** — a template (`CLEANING`, `GENERAL_SERVICE`, …) that seeds vocabulary and defaults
   - **Currency** — pre-filled from the country, editable
   - **Time zone** — pre-filled from your browser
4. **Step 2 — your account:**
   - **Your name**, **email**, **password**
   - Password rule enforced by the backend: **12–72 characters, at least one letter and one digit**
     (`StrongPassword` validator)
5. Submit. The account is created, you are signed in, and you land on the dashboard as **OWNER** of the
   new workspace.

Registration returns a short-lived access token plus a refresh token. The access token is kept **in
memory**; the refresh token is stored in the browser's `localStorage` under `operonix.refresh`, which is
why a page reload keeps you signed in.

Signing in later with an email that belongs to more than one workspace shows a **Workspace** selector;
pick the one to open.

### 8.2 Configure the workspace

Two screens, both reachable from the left sidebar:

- **Workspace settings** (`/settings`) → *Business profile*: business name, optional registered legal
  name, business type (industry template), country of registration, currency, time zone, language &
  formats, and an optional tax number. These drive formatting and defaults across the app.
- **Financial settings** (`/settings/financial`, owners and admins): *Selling entities*, *Business
  locations* (each with an optional own time zone — a job booked at 09:00 uses the **location's** zone),
  *Tax profiles*, *Document numbering*, *Exchange rates*, *Workspace preferences*.

Changing a setting never rewrites documents already issued: invoices and quotations keep the currency,
tax components and totals they were created with.

### 8.3 Explore the product in a sensible order

| Step | Screen | Try this |
| --- | --- | --- |
| 1 | **Customers** (`/customers`) | Add a customer with a billing address and a primary service location |
| 2 | **Catalog** (`/catalog`) | Add a service with a price, duration and tax rate |
| 3 | **Quotations** (`/quotes`) | Create a quote from catalog lines, add a discount, send it, accept it |
| 4 | **Scheduling** (`/schedule`) | Convert the accepted quote into a job; drag/assign a technician; start and complete it |
| 5 | **Job drawer** | Record time and expenses, then read *Costing & profit* (planned vs actual) |
| 6 | **Invoices** (`/invoices`) | Create an invoice from the completed job, send it, record a payment (cash/card/transfer/check) |
| 7 | **Analytics** (`/analytics`) | Revenue, collections, margin, receivables — every card drills into the documents behind it |
| 8 | **Operonix AI** (`/assistant`) | Ask "What was our revenue this month?" — the figures come from the same metric catalogue as Analytics |
| 9 | **Team** (`/team`) | Add a technician (owner/admin only); roles are OWNER, ADMIN, MANAGER, TECHNICIAN |
| 10 | **Audit log** (`/audit`) | See the append-only record of business changes |

Roles matter: a **TECHNICIAN** sees scheduling and catalog but not customers, quotes, invoices, analytics,
team or settings — both the menu and the server enforce this.

### 8.4 Optional: seed a demo workspace

The repository ships a seed script that creates a realistic workspace (customers, services, an
accepted quote, a completed job, an invoice with a partial payment, plus a pending quote and a future
job):

```powershell
cd C:\dev\operonix\frontend
# Only needed if the API is not on the default port:
$env:OPERONIX_API = "http://localhost:8090/api/v1"
node scripts/demo-seed.mjs
```

The script prints the exact email and password it used, for example:

```
Seeded demo workspace for owner+482913@harborclean.example / Harbor-Clean-2026
```

Use those printed values on the login page. The email contains a timestamp and changes between runs (the
script reuses the empty demo account when it can). These are **local demo accounts created by the script
in your own database** — they are not production credentials, and no real account exists at that address.

### 8.5 Optional: end-to-end smoke test

`frontend/scripts/smoke.mjs` exercises the whole loop against a running API and prints one line per step:
register → customer → service → quote → accept → convert to job → complete → invoice → two payments →
dashboard → audit → tenant-isolation check → anonymous request.

```powershell
cd C:\dev\operonix\frontend
node scripts/smoke.mjs                      # API on :8080
$env:OPERONIX_BASE_URL = "http://localhost:8090/api/v1"; node scripts/smoke.mjs
```

It ends with `SEED OK` and writes test data into that database — use a throwaway workspace or reset
afterwards (§4.3).

### 8.6 Limitations you will notice

- **Payments are recorded, not captured.** There is no card processor; "CARD" is a label on a payment you
  enter.
- **No email or SMS delivery.** Marking a quote or invoice "sent" changes its status; nothing is emailed.
- **Operonix AI works without a key.** With `OPERONIX_AI_API_KEY` empty the assistant still returns the
  server-calculated figures, explained by built-in templates instead of a model. Nothing else in the app
  depends on it.
- **No invitation flow.** An owner/admin creates team members directly with a password on `/team`.
- **One language is catalogued** (English); date, number and currency formatting are locale-aware.
- **No file attachments** on customers, jobs or invoices.

---

## 9. Build and test

### 9.1 Backend

```powershell
cd C:\dev\operonix\backend

mvn -DskipTests package     # compile + package the jar
mvn test                    # unit + integration tests
mvn verify                  # tests, then the JaCoCo coverage report
```

`mvn test` needs **no** running database: the tests start an embedded PostgreSQL 16 (Zonky
`embedded-postgres`) and run the **real** Flyway migrations against it. That is why the suite is
trustworthy — it exercises the same schema you just created by hand.

Success looks like:

```
[INFO] Tests run: <N>, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

(`<N>` is however many tests the suite currently contains — it grows as tests are added. The important
part is `Failures: 0, Errors: 0`. The surefire configuration runs both `*Test.java` and `*IT.java`.)

### 9.2 Frontend

```powershell
cd C:\dev\operonix\frontend

npm run typecheck   # TypeScript, no output files
npm run build       # tsc -b && vite build -> dist/
```

Success:

- `npm run typecheck` prints nothing and exits 0.
- `npm run build` ends with a table such as `dist/index.html 0.66 kB` and `✓ built in 8.5s`.

### 9.3 Development mode vs production build

| | Development (`mvn spring-boot:run`, `npm run dev`) | Production build (`mvn package`, `npm run build`) |
| --- | --- | --- |
| Frontend | Served by Vite on `:5173`, recompiles on save (hot reload) | Pre-built static files in `dist/`, no dev server |
| API base | `/api/v1` proxied to the backend | Same origin, no proxy |
| Backend | Dev profile, DEBUG logging, dev JWT secret | `prod` profile, JSON logs, secret from the environment |
| Speed | Fast to iterate | Optimised, minified, single artifact |
| Use it for | Writing code | Checking the real artifact, deploying |

---

## 10. Troubleshooting

Diagnose in this order — it follows the dependency chain: tools → database → backend → frontend →
browser.

### 10.1 "Port already in use"

```
Web server failed to start. Port 8080 was already in use.
```

Find the owner and stop it (PowerShell):

```powershell
netstat -ano | findstr :8080
#   TCP    0.0.0.0:8080    0.0.0.0:0    LISTENING    12345
taskkill /PID 12345 /F
```

`5173` (frontend) and `5432` (database) work the same way. Safer alternative: run the API elsewhere —
`$env:PORT = "8090"` — and point the frontend at it (`$env:VITE_API_PROXY_TARGET = "http://localhost:8090"`).
Vite itself picks `5174` automatically if `5173` is taken; read the URL it prints.

### 10.2 "java / mvn / node / npm / psql is not recognized"

The executable is not on `Path`.

```powershell
where.exe java
where.exe mvn
where.exe node
where.exe psql
```

- Nothing printed → not installed, or installed without adding its `bin` directory to `Path`.
- Wrong version printed first (e.g. Java 17 before 21) → reorder `Path`, or set `JAVA_HOME` to the JDK 21
  folder and put `%JAVA_HOME%\bin` first.
- Fixed `Path` but the same error → **open a new terminal**; the old one cached the environment.

### 10.3 PostgreSQL: connection refused or authentication failed

```powershell
Get-Service postgresql*                                  # is it running?
netstat -ano | findstr :5432                             # is anything listening?
psql -U operonix -h localhost -d operonix -c "select 1;" # do the credentials work?
```

- Service stopped → `Start-Service postgresql-x64-16` (Administrator PowerShell).
- Container stopped → `docker compose up -d db`, then `docker compose ps` until healthy.
- `password authentication failed` → reset the password, or set `OPERONIX_DB_PASSWORD` to the real one.
- Works in `psql` but not in the app → the app is reading different values. Remember `application-dev.yml`
  also defaults to `operonix/operonix`; check for stray `OPERONIX_DB_*` variables with
  `Get-ChildItem Env:OPERONIX*`.

### 10.4 Flyway migration errors

Read the first `Caused by:` line; it names the file and the SQL problem.

| Message | Cause | Fix |
| --- | --- | --- |
| `Migration checksum mismatch for version 3` | A migration that already ran was edited. | `git checkout -- backend/src/main/resources/db/migration`, or reset the local DB (§4.3). |
| `Detected resolved migration not applied to database` | You pulled a branch that added a migration, but the schema was migrated by another branch. | Reset the local database; never hand-edit it to match. |
| `Syntax error at or near ...` in `V...sql` | Genuine SQL error in a new migration. | Fix the new file (safe only before it has been applied anywhere) and restart. |
| `Schema-validation: missing column` | Code expects a migration that has not run. | Check the Flyway line in the log; confirm `spring.flyway.enabled=true`. |

### 10.5 Missing environment variables

```
OPERONIX_JWT_SECRET is required (minimum 32 bytes). Refusing to start without a signing secret.
```

You started the backend without the `dev` profile and without a secret. Either:

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

or set one for the session (at least 32 characters):

```powershell
$env:OPERONIX_JWT_SECRET = "local-dev-only-please-change-0123456789abcdef"
```

`OPERONIX_JWT_SECRET must be at least 32 bytes long` is the same fix with a shorter value. A secret
containing the development marker is rejected under `prod` on purpose.

### 10.6 CORS or "Cannot reach the Operonix API"

| Symptom | Cause | Fix |
| --- | --- | --- |
| UI shows *"Cannot reach the Operonix API"* | Backend not running, or proxy target wrong. | Start the backend; confirm `http://localhost:8080/actuator/health`; set `VITE_API_PROXY_TARGET` to the right port and restart `npm run dev` (Vite reads env at start). |
| Browser console: `blocked by CORS policy` | You set `VITE_API_URL` to an absolute URL, so the browser calls the API cross-origin. | Unset `VITE_API_URL` and use the proxy, or add the origin to `OPERONIX_CORS_ORIGINS` and restart the backend. |
| `401` on every screen after a restart | Access token expired while the API was down; the refresh token is still valid. | Reload the page (one refresh attempt happens automatically; a 5xx deliberately keeps the token). If it persists, sign out and in. |

### 10.7 Frontend dependency or build failures

```powershell
cd C:\dev\operonix\frontend
Remove-Item -Recurse -Force node_modules
npm ci
npm run typecheck
```

- `EACCES`/`EPERM`/file locked → close the dev server, editors and antivirus scan windows on that folder,
  then retry.
- `Unsupported engine` → your Node is too old; install Node 20 LTS or newer.
- Type errors after switching branches → `npm ci` again; the lockfile pins the matching versions.
- `dist` looks stale → `npm run build` (which runs `tsc -b` first) rather than copying files by hand.

### 10.8 Login / registration / onboarding problems

| Symptom | Explanation |
| --- | --- |
| "Couldn't create the workspace" with a password message | The rule is 12–72 characters with at least one letter and one digit. |
| "email already registered" | That address already has an account. Sign in instead. |
| Sign-in asks you to choose a workspace | The email belongs to more than one workspace — expected. Pick one. |
| Dashboard is empty after registering | Correct: a new workspace has no data. Add a customer, a service, then a quote. |
| Redirected to `/login` on reload | The refresh token is missing or was rejected (cleared `localStorage`, reset database, or the account no longer exists). Sign in again. |
| Everything 401s after a database reset | Sessions reference users that no longer exist. Sign out (or clear `localStorage`) and register again. |

---

## 11. Useful developer reference

### 11.1 Important files

| File | Why you would open it |
| --- | --- |
| `backend/pom.xml` | Java version, Spring Boot version, dependencies, test configuration |
| `backend/src/main/resources/application.yml` | Every default and every `OPERONIX_*` variable name |
| `backend/src/main/resources/application-dev.yml` | Local overrides (dev JWT secret, DEBUG logging) |
| `backend/src/main/java/com/operonix/config/SecurityConfig.java` | Which endpoints are public, CORS, security headers |
| `backend/src/main/java/com/operonix/config/OpenApiConfig.java` | Swagger title, version, bearer scheme |
| `frontend/vite.config.ts` | Dev port 5173 and the `/api` proxy |
| `frontend/package.json` | Scripts (`dev`, `build`, `typecheck`, `preview`) |
| `compose.yaml` | Local PostgreSQL 16 container |
| `Dockerfile` | How the production image bakes the SPA into the jar |

### 11.2 Database migrations

- Folder: `backend/src/main/resources/db/migration/`
- Applied automatically at backend start (`spring.flyway.locations=classpath:db/migration`)
- Current versions: **V1 → V8**
- Rule: migrations are **additive**; never edit one that has run. Add `V9__description.sql`.

### 11.3 API documentation

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Base path: `/api/v1`; authentication is `Authorization: Bearer <accessToken>`
- Public endpoints: `POST /api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`,
  `GET /api/v1/reference`, and the `/actuator/health` endpoints
- Turn the docs off with `OPERONIX_API_DOCS=false`

### 11.4 Where the tests live

| Path | What it covers |
| --- | --- |
| `backend/src/test/java/com/operonix/**/*Test.java` | Unit tests (money maths, AI guards, static hosting) |
| `backend/src/test/java/com/operonix/**/*IT.java` | Integration tests on embedded PostgreSQL with real migrations |
| `backend/src/test/java/com/operonix/support/AbstractIntegrationTest.java` | Shared test harness |
| `frontend/scripts/smoke.mjs` | End-to-end lifecycle against a running API |
| `frontend/scripts/demo-seed.mjs` | Builds a demo workspace for screenshots/review |

There is no automated browser (UI) test suite yet; the frontend is checked by `npm run typecheck` and
`npm run build`.

### 11.5 Stop everything

```powershell
# Backend and frontend: press Ctrl+C in their terminals.

# If a terminal was closed but the ports are still busy:
netstat -ano | findstr ":8080 :5173 :5432"
# then, for each PID that belongs to this project:
taskkill /PID <pid> /F

# Database
docker compose down          # container, keeps data
docker compose down -v       # container AND data (destructive)

# Native PostgreSQL (Windows)
Stop-Service postgresql-x64-16     # Administrator PowerShell
```

Before rebuilding the jar on Windows, stop the running API. A running Java process holds
`backend/target/operonix-api.jar` open, and Maven then fails with "cannot delete/access the file because
it is being used by another process".

### 11.6 Full clean restart (when everything is confused)

```powershell
# 1. stop backend + frontend (Ctrl+C), then free the ports if needed
# 2. reset the database (DESTRUCTIVE, local only)
psql -U postgres -h localhost -d postgres -c "DROP DATABASE operonix WITH (FORCE);"
psql -U postgres -h localhost -d postgres -c "CREATE DATABASE operonix OWNER operonix;"
# 3. rebuild
cd C:\dev\operonix\backend; mvn -DskipTests clean package
cd C:\dev\operonix\frontend; Remove-Item -Recurse -Force node_modules; npm ci
# 4. start backend, then frontend, then open http://localhost:5173 and register again
```

---

## 12. What was verified while writing this guide

Checked directly against the running application and the repository on the machine that wrote this
guide:

| Item | How it was checked | Result |
| --- | --- | --- |
| Java version = 21 | `java -version` | OpenJDK 21.0.13 |
| Maven ≥ 3.9 | `mvn -v` | 3.9.9 |
| Node + npm present | `node -v`, `npm -v` | v26.12.1, npm 11.20.0 |
| Backend health endpoint | `GET /actuator/health` | `200 {"status":"UP","groups":["liveness","readiness"]}` |
| OpenAPI JSON | `GET /v3/api-docs` | `200` |
| Swagger UI | `GET /swagger-ui.html` | `200` |
| Public reference data | `GET /api/v1/reference` | `200` |
| Frontend dev server | `GET http://localhost:5173/` | `200` |
| Migration set | `backend/src/main/resources/db/migration/` | V1…V8 present |
| Environment variables | `application.yml`, `application-dev.yml`, `application-prod.yml` | every variable in §5 comes from these files |
| Frontend package manager | `frontend/package-lock.json`, no `pnpm-lock.yaml` | npm is correct; pnpm is not used |
| Password rule | `StrongPassword` / `StrongPasswordValidator` | 12–72 chars, letter + digit |

**Not verified in this session — do this yourself:**

1. **Installing PostgreSQL natively and running the `CREATE USER` / `CREATE DATABASE` SQL.** This machine
   already had a database and no `psql` client. Check with the `psql -U operonix ... -c "select 1;"`
   command in §4.1.
2. **A fresh `mvn spring-boot:run` on port 8080.** A development instance was already running on port
   8090, and starting a second one would have collided. The same application and profile answered the
   health, docs and reference checks above.
3. **`npm ci` from scratch.** `node_modules` was already installed. Run it once yourself; if it fails, the
   fix is in §10.7.
4. **The registration and onboarding flow in a browser for this session.** The screens, fields and
   validation rules above were read from the current source (`Register.tsx`, `Login.tsx`,
   `StrongPasswordValidator`), and the API that backs them is the running one.
