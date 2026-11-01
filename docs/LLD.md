# Operonix — Low-Level Design (LLD)

| Field | Value |
| --- | --- |
| Version | 1.0 |
| Last updated | 2026-10-30 |
| Companion documents | [HLD.md](HLD.md), [ERD.md](ERD.md) |
| API base path | `/api/v1` |
| Media type | `application/json` (errors: `application/problem+json`) |

This document is the implementation contract. Table names and columns referenced here are defined
in [ERD.md](ERD.md); any change to one requires a change to the other.

---

## 1. Repository and package layout

```
operonix/
  backend/
    pom.xml
    src/main/java/com/operonix/
      OperonixApplication.java
      common/        api (PageResponse, ProblemDetailsFactory), error (exceptions),
                     money (MoneyCalculator), time (ClockConfig), web (RequestIdFilter),
                     validation (PasswordPolicy, Slug)
      config/        SecurityConfig, JwtProperties, WebConfig, OpenApiConfig, AppProperties
      security/      JwtService, JwtAuthenticationFilter, AuthenticatedUser, TenantContext,
                     AccessRules (role helpers)
      audit/         AuditEvent, AuditAction, AuditEventRepository, AuditService, AuditController
      document/      DocumentSequence, DocumentSequenceRepository, DocumentNumberService, DocumentType
      tenancy/       Tenant, TenantRepository, TenantService, WorkspaceService, WorkspaceController, dto
      identity/      AppUser, Role, RefreshToken, repositories, AuthService, SessionRevocationService,
                     AuthController, TeamService, TeamController, dto
      customer/      Customer, ServiceLocation, repositories, CustomerService, CustomerController,
                     LocationController, dto
      catalog/       ServiceItem, ServiceItemRepository, CatalogService, CatalogController, dto
      quoting/       Quote, QuoteLine, QuoteStatus, repositories, QuoteService, QuoteController, dto
      scheduling/    Job, JobAssignment, JobStatus, Recurrence, repositories, JobService,
                     JobController, dto
      billing/       Invoice, InvoiceLine, Payment, InvoiceStatus, PaymentMethod, repositories,
                     InvoiceService, PaymentService, InvoiceController, dto
      reporting/     ReportService, ReportController, dto
    src/main/resources/
      application.yml, application-dev.yml, application-prod.yml, logback-spring.xml
      db/migration/V1__schema.sql (the only migration; no seed data)
    src/test/java/com/operonix/
      common/money/MoneyCalculatorTest.java
      identity/AuthFlowIT.java
      sales/SalesFlowIT.java
      support/AbstractIntegrationTest.java (embedded PostgreSQL + MockMvc fixtures)
  frontend/
    index.html, vite.config.ts, tailwind.config.js, tsconfig*.json
    src/api/         client.ts, types.ts, endpoints.ts
    src/auth/        AuthProvider.tsx (session + token restore), RequireAuth.tsx (guards)
    src/components/  Layout.tsx, AuthShell.tsx, *Dialog.tsx, JobDetail.tsx, LineItemsEditor.tsx,
                     ui/ (primitives, Modal, toast, misc, cn)
    src/pages/       Login, Register, Dashboard, Customers, CustomerDetail, Quotes, QuoteEditor,
                     QuoteDetail, Schedule, Invoices, InvoiceDetail, Catalog, Team, Settings,
                     AuditLog, NotFound
    src/lib/         queryClient.ts, format.ts, money.ts
    scripts/         smoke.mjs (API lifecycle check), demo-seed.mjs (reviewable demo workspace)
  docs/              HLD.md, LLD.md, ERD.md, deep-research-report.md
  memory.md
  compose.yaml
```

Module rule: controllers depend on services in the same module; services may call other modules'
services, never their repositories (reporting is the only cross-module repository reader and is
read-only).

---

## 2. Domain model and invariants

| Aggregate | Invariants |
| --- | --- |
| Tenant | `slug` unique, lowercase `^[a-z0-9][a-z0-9-]{1,62}$`; `currency` ISO-4217 (v1: `USD`, `EUR`, `GBP`, `INR`, `AUD`, `CAD`); `timezone` IANA id |
| AppUser | Email globally unique and stored lowercase; one tenant per user; exactly one `OWNER` per tenant minimum; password hash BCrypt; deactivated users cannot authenticate |
| Customer | Belongs to one tenant; `name` required; archive is a status change, not a delete; at most one primary location per customer |
| ServiceLocation | Belongs to a customer in the same tenant; deleting a location with scheduled jobs is rejected |
| ServiceItem | Prices are non-negative; `durationMinutes` 15–1440; `taxRate` 0–1 (4 dp); deactivation hides it from new quotes but keeps history |
| Quote | Lines ≥ 1 for non-draft; totals always recomputed server-side; `validUntil ≥ issueDate`; only `DRAFT` is editable; `CONVERTED` is terminal |
| Job | `scheduledEnd > scheduledStart`; technicians assigned must be active users in the tenant with role `TECHNICIAN`, `MANAGER`, `ADMIN` or `OWNER`; no overlapping assignment for a technician; status transitions restricted; completed jobs are immutable except notes |
| Invoice | Lines ≥ 1; `total > 0` to send; `amountPaid ≤ total`; void only when `amountPaid = 0`; currency inherited from tenant |
| Payment | `amount > 0` and ≤ current invoice balance; recorded in the invoice's tenant; captured `paidAt` not in the future (tolerance 5 min) |
| DocumentSequence | One row per `(tenant, docType, year)`; allocation inside the writing transaction with a pessimistic lock |

---

## 3. API conventions

### 3.1 Request/response

- IDs are UUID strings. Timestamps are ISO-8601 UTC (`2026-10-30T14:05:00Z`). Dates are `yyyy-MM-dd`.
- Money is a JSON number with 2 decimals (`"total": 1234.50`), quantities up to 3 decimals.
- Requests never contain `tenantId`, `id` for creation, `createdAt`, `amountPaid`, or computed totals.
- Successful creates return `201` + `Location` header; updates `200`; deletes/voids `204` or `200` with body as documented.
- Lists return a page envelope:

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 137, "totalPages": 7 }
```

- Pagination: `page ≥ 0` (default 0), `size` 1–100 (default 20). `sort` is `field,asc|desc` restricted to
  a per-endpoint whitelist (default documented per endpoint). Unknown sort field → `400 VALIDATION_FAILED`.
- `q` performs a case-insensitive contains search over documented columns; blank is ignored.
- Concurrency: mutable aggregates expose `version`; `PUT` accepts optional `version`; a stale value
  returns `409 CONCURRENT_MODIFICATION`.

### 3.2 Errors (RFC 7807)

```json
{
  "type": "https://operonix.app/errors/validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid",
  "instance": "/api/v1/customers",
  "code": "VALIDATION_FAILED",
  "requestId": "0f4c…",
  "errors": [ { "field": "email", "message": "must be a well-formed email address" } ]
}
```

| Code | HTTP | Meaning |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Bean Validation or malformed parameter |
| `MALFORMED_REQUEST` | 400 | Unreadable/invalid JSON or bad UUID/enum |
| `UNAUTHENTICATED` | 401 | Missing/invalid access token |
| `TOKEN_EXPIRED` | 401 | Access token expired (client should refresh) |
| `INVALID_CREDENTIALS` | 401 | Login failed (generic message, no user enumeration) |
| `REFRESH_TOKEN_INVALID` | 401 | Unknown/expired refresh token |
| `REFRESH_TOKEN_REUSED` | 401 | Revoked token replayed; all sessions revoked |
| `FORBIDDEN` | 403 | Authenticated but role/ownership denies the action |
| `NOT_FOUND` | 404 | Resource absent **or outside the caller's tenant** |
| `CONFLICT` | 409 | Generic uniqueness/state conflict |
| `EMAIL_ALREADY_EXISTS` | 409 | Registration or team creation with an existing email |
| `INVALID_STATE_TRANSITION` | 409 | e.g. accepting a draft quote, completing a cancelled job |
| `IMMUTABLE_RESOURCE` | 409 | Editing a non-draft document, completed job, or sent invoice |
| `JOB_SCHEDULE_CONFLICT` | 409 | Technician double-booked (payload lists conflicts) |
| `LAST_OWNER_PROTECTED` | 409 | Demoting/deactivating the last active owner |
| `SELF_MODIFICATION` | 409 | Deactivating/demoting oneself |
| `PAYMENT_EXCEEDS_BALANCE` | 422 | Payment greater than invoice balance |
| `INVOICE_HAS_PAYMENTS` | 409 | Void/delete attempted with payments recorded |
| `NO_INVOICEABLE_LINES` | 422 | Job has no quote lines and none supplied |
| `RATE_LIMITED` | 429 | Too many auth attempts (`Retry-After` header) |
| `CONCURRENT_MODIFICATION` | 409 | Optimistic lock failure |
| `INTERNAL_ERROR` | 500 | Unexpected failure (no stack traces to clients) |

Access token expiry returns `401` with `WWW-Authenticate: Bearer error="invalid_token"` and the
`TOKEN_EXPIRED` code so the client can refresh exactly once.

---

## 4. Endpoint catalogue

### 4.1 Authentication (`/api/v1/auth`)

| Method | Path | Auth | Request | Response |
| --- | --- | --- | --- | --- |
| POST | `/register` | public | `{orgName, fullName, email, password, countryCode?, currency?, timezone?, locale?, industryTemplate?, legalName?}` | `201 AuthResponse` |
| POST | `/login` | public | `{email, password, workspace?}` | `200 AuthResponse` or `400 WORKSPACE_REQUIRED` |
| POST | `/refresh` | public (token) | `{refreshToken}` | `200 AuthResponse` (rotated) |
| POST | `/logout` | bearer | `{refreshToken}` | `204` |
| GET | `/me` | bearer | — | `200 MeResponse` |

```jsonc
// AuthResponse
{
  "accessToken": "eyJ…",
  "expiresIn": 900,
  "refreshToken": "6f3c…",          // returned once; stored client-side
  "user": { "id": "…", "email": "owner@northwind.co", "fullName": "Dana Reed",
            "role": "OWNER", "tenantId": "…" },
  "tenant": { "id": "…", "name": "Northwind Field Services", "slug": "northwind-field-services",
              "currency": "USD", "timezone": "America/New_York", "countryCode": "US",
              "locale": "en-US", "industryTemplate": "GENERAL_SERVICE" }
}
```

`register` creates tenant + owner atomically and returns the same payload. Registration is rate
limited per IP as well as login. `tenant.taxNumber` is deliberately excluded from every session
payload; it is only readable through `GET /workspace` (owner/admin).

**Global defaults.** Omitted regional fields are derived from `countryCode` (default `US`) using the
`country` table: `currency` and `locale` come from the country row, `timezone` defaults to `UTC` when
absent, `industryTemplate` to `GENERAL_SERVICE`. Explicit values are validated against the active
`currency` and `country` tables; unknown codes return `400 VALIDATION_ERROR` naming the field.

**Multi-workspace sign-in.** Email uniqueness is per workspace, so one address may map to several
memberships. With exactly one match, login proceeds. With several and no `workspace` slug, the API
returns:

```jsonc
// 400 WORKSPACE_REQUIRED
{ "type": "about:blank", "title": "Workspace required", "status": 400,
  "code": "WORKSPACE_REQUIRED",
  "detail": "This email belongs to more than one workspace. Choose one to continue.",
  "workspaces": [ { "slug": "northwind-field-services", "name": "Northwind Field Services" } ] }
```

The client re-submits with the chosen slug. A wrong slug for that email is `401` like any other
credential failure, so workspace existence is not disclosed.

### 4.2 Workspace and team

| Method | Path | Roles | Notes |
| --- | --- | --- | --- |
| GET | `/workspace` | OWNER, ADMIN | Current tenant settings + usage counts |
| PUT | `/workspace` | OWNER, ADMIN | `{name, timezone, currency, countryCode, locale, industryTemplate, legalName?, taxNumber?}`; slug immutable |
| GET | `/team` | OWNER, ADMIN | Active + inactive users, no password data |
| GET | `/team/assignable` | OWNER, ADMIN, MANAGER | `[{id, fullName}]`, active users only — the scheduling picker, so managers never see team administration data |
| POST | `/team` | OWNER, ADMIN | `{fullName, email, password, role}`; cannot create `OWNER` (v1) |
| PUT | `/team/{userId}` | OWNER, ADMIN | `{fullName?, role?, active?}`; last-owner and self rules apply |
| GET | `/audit-events` | OWNER, ADMIN | `page,size,action,from,to` |

### 4.3 Customers and locations

| Method | Path | Roles | Notes |
| --- | --- | --- | --- |
| GET | `/customers` | OWNER, ADMIN, MANAGER | `q, status(ACTIVE/ARCHIVED/ALL), page, size, sort(name,createdAt)` default `name,asc` |
| POST | `/customers` | OWNER, ADMIN, MANAGER | `{name, contactName?, email?, phone?, notes?, billingAddress?, primaryLocation?}` |
| GET | `/customers/{id}` | OWNER, ADMIN, MANAGER | Includes locations |
| PUT | `/customers/{id}` | OWNER, ADMIN, MANAGER | Full update of editable fields |
| POST | `/customers/{id}/archive` | OWNER, ADMIN, MANAGER | Blocked while jobs are `SCHEDULED`/`IN_PROGRESS` |
| POST | `/customers/{id}/restore` | OWNER, ADMIN, MANAGER | |
| GET | `/customers/{id}/locations` | OWNER, ADMIN, MANAGER | |
| POST | `/customers/{id}/locations` | OWNER, ADMIN, MANAGER | `{label, line1, line2?, city, region?, postalCode, country, accessNotes?, isPrimary}` |
| PUT | `/locations/{id}` | OWNER, ADMIN, MANAGER | Setting `isPrimary` unsets the previous primary |
| DELETE | `/locations/{id}` | OWNER, ADMIN, MANAGER | `409 CONFLICT` if referenced by non-cancelled jobs |

Customer JSON:

```jsonc
{
  "id": "…", "name": "Brightline Offices", "contactName": "Mia Chen",
  "email": "mia@brightline.example", "phone": "+1-555-0142", "status": "ACTIVE",
  "notes": "Key at reception. No bleach on stone.",
  "billingAddress": { "line1": "18 Harbor St", "line2": "Suite 400",
                      "city": "Boston", "region": "MA", "postalCode": "02110", "country": "US" },
  "locations": [ { "id": "…", "label": "HQ — Floor 4", "line1": "18 Harbor St", …,
                   "accessNotes": "Badge at dock", "isPrimary": true } ],
  "version": 3, "createdAt": "…", "updatedAt": "…"
}
```

### 4.4 Service catalog

| Method | Path | Roles | Notes |
| --- | --- | --- | --- |
| GET | `/services` | all | `active=true|false|all`, `q`; sorted by name |
| POST | `/services` | OWNER, ADMIN, MANAGER | `{name, description?, unit, basePrice, durationMinutes, taxRate, active}` |
| PUT | `/services/{id}` | OWNER, ADMIN, MANAGER | Deactivate via `active=false` (history preserved) |

Units: `HOUR`, `VISIT`, `SQUARE_FOOT`, `ROOM`, `FLAT`.

### 4.5 Quotes

| Method | Path | Roles | Notes |
| --- | --- | --- | --- |
| GET | `/quotes` | OWNER, ADMIN, MANAGER | `status, customerId, q, page, size, sort(issueDate,number,total)` default `issueDate,desc` |
| POST | `/quotes` | OWNER, ADMIN, MANAGER | `{customerId, locationId?, issueDate?, validUntil?, discountAmount?, notes?, lines[]}` |
| GET | `/quotes/{id}` | OWNER, ADMIN, MANAGER | |
| PUT | `/quotes/{id}` | OWNER, ADMIN, MANAGER | Draft only; replaces lines |
| DELETE | `/quotes/{id}` | OWNER, ADMIN | Draft only |
| POST | `/quotes/{id}/send` | OWNER, ADMIN, MANAGER | `DRAFT → SENT` |
| POST | `/quotes/{id}/accept` | OWNER, ADMIN, MANAGER | `SENT → ACCEPTED`; rejects expired (`409`) |
| POST | `/quotes/{id}/decline` | OWNER, ADMIN, MANAGER | `SENT → DECLINED` |
| POST | `/quotes/{id}/convert` | OWNER, ADMIN, MANAGER | `ACCEPTED → CONVERTED`; body `{scheduledStart, scheduledEnd, technicianIds[], title?}`; returns created job |

Line input: `{serviceItemId?, description, quantity, unitPrice, taxRate}`. When `serviceItemId` is
present the server **snapshots** the catalog description/price/tax only if the client omits the
field; explicitly supplied values win (allows negotiated pricing). Response lines include computed
`lineSubtotal`, `lineTax`, `lineTotal`, and `position`.

Quote JSON adds: `number` (`Q-2026-0001`), `status`, `subtotal`, `discountAmount`, `taxAmount`,
`total`, `currency`, `convertedJobId`, `customer` summary, `location` summary, `lines`, `version`.

### 4.6 Jobs and scheduling

| Method | Path | Roles | Notes |
| --- | --- | --- | --- |
| GET | `/jobs` | all | `from, to (ISO date), status, technicianId, customerId, mine=true, page, size`; sorted by `scheduledStart,asc` |
| POST | `/jobs` | OWNER, ADMIN, MANAGER | Ad-hoc job: `{customerId, locationId, title, scheduledStart, scheduledEnd, technicianIds[], notes?}` |
| POST | `/jobs/series` | OWNER, ADMIN, MANAGER | Same body + `recurrence` (`WEEKLY`,`BIWEEKLY`,`MONTHLY`) + `occurrences` (2–26) |
| GET | `/jobs/{id}` | all | Technicians may only read jobs assigned to them |
| PUT | `/jobs/{id}` | OWNER, ADMIN, MANAGER | Editable while `SCHEDULED`; triggers conflict check |
| POST | `/jobs/{id}/assignments` | OWNER, ADMIN, MANAGER | `{userId}` |
| DELETE | `/jobs/{id}/assignments/{userId}` | OWNER, ADMIN, MANAGER | |
| POST | `/jobs/{id}/transitions` | all (technician: assigned only) | `{to: IN_PROGRESS|COMPLETED|CANCELLED, note?}` |
| POST | `/jobs/{id}/invoice` | OWNER, ADMIN, MANAGER | `{dueDate?, lines?}`; defaults to quote lines; returns `201 Invoice` |

Job JSON:

```jsonc
{
  "id": "…", "number": "J-2026-0042", "title": "Weekly office clean",
  "status": "SCHEDULED", "scheduledStart": "2026-11-02T13:00:00Z",
  "scheduledEnd": "2026-11-02T16:00:00Z",
  "customer": { "id": "…", "name": "Brightline Offices" },
  "location": { "id": "…", "label": "HQ — Floor 4", "line1": "18 Harbor St", "city": "Boston" },
  "quoteId": "…", "recurrence": "NONE", "seriesId": null,
  "technicians": [ { "id": "…", "fullName": "Sam Ortiz" } ],
  "notes": "…", "completedAt": null, "version": 1
}
```

### 4.7 Invoices and payments

| Method | Path | Roles | Notes |
| --- | --- | --- | --- |
| GET | `/invoices` | OWNER, ADMIN, MANAGER | `status, customerId, overdue, q, page, size`; default `issueDate,desc` |
| POST | `/invoices` | OWNER, ADMIN, MANAGER | Ad hoc: `{customerId, jobId?, issueDate?, dueDate, notes?, discountAmount?, lines[]}` |
| GET | `/invoices/{id}` | OWNER, ADMIN, MANAGER | |
| PUT | `/invoices/{id}` | OWNER, ADMIN, MANAGER | Draft only |
| POST | `/invoices/{id}/send` | OWNER, ADMIN, MANAGER | `DRAFT → SENT`; sets `dueDate` default +14d if absent |
| POST | `/invoices/{id}/void` | OWNER, ADMIN, MANAGER | `{reason}`; requires `amountPaid = 0` |
| POST | `/invoices/{id}/payments` | OWNER, ADMIN, MANAGER | `{amount, method, reference?, paidAt?}` → `201 Payment` |
| DELETE | `/invoices/{id}/payments/{paymentId}` | OWNER, ADMIN | Reversal (audited), recomputes status |

Invoice JSON includes `number` (`INV-2026-0007`), `status`, `overdue`, `subtotal`, `discountAmount`,
`taxAmount`, `total`, `amountPaid`, `balance`, `issueDate`, `dueDate`, `jobId`, `customer`, `lines`,
`payments[]`, `version`.

### 4.8 Reporting

| Method | Path | Roles | Response |
| --- | --- | --- | --- |
| GET | `/reports/dashboard` | all | `{revenueThisMonth, revenuePreviousMonth, revenueDeltaPct, outstandingTotal, overdueTotal, jobsToday, jobsThisWeek, quotesPending, quoteAcceptanceRate, activeCustomers, revenueByDay[14], topServices[5], upcomingJobs[5]}` |
| GET | `/reports/revenue` | OWNER, ADMIN, MANAGER | `from,to,groupBy=day|month|service` → `{buckets:[{key,label,revenue,invoiceCount}]}` |
| GET | `/reports/technicians` | OWNER, ADMIN, MANAGER | `from,to` → `[{userId, fullName, jobsCompleted, scheduledMinutes}]` |

Metrics are computed from `PAID`/`PARTIALLY_PAID` payments (money) and job rows (counts); revenue is
cash collected, not invoiced. Technicians see only operational counters on the dashboard and no
money figures.

### 4.9 Health and docs

`GET /actuator/health` (public, liveness/readiness), `/actuator/info`, `/v3/api-docs`,
`/swagger-ui.html` (dev/staging only by default).

---

## 5. Business rules

### 5.1 Money and tax (single implementation: `MoneyCalculator`)

- `lineSubtotal = round(quantity × unitPrice, 2)`
- `lineTax = round(lineSubtotal × taxRate, 2)`
- `subtotal = Σ lineSubtotal`
- `discount = clamp(requestedDiscount, 0, subtotal)`
- `taxableFactor = subtotal == 0 ? 0 : (subtotal − discount) / subtotal` (6 dp)
- `taxAmount = round(Σ lineTax × taxableFactor, 2)` — discount reduces the taxable base proportionally
- `total = subtotal − discount + taxAmount`
- Rounding is `HALF_UP`, scale 2 for money, 3 for quantity, 4 for tax rate. No float/double anywhere.

### 5.2 Document numbering

Format `PREFIX-YYYY-NNNN` (`Q`, `J`, `INV`), zero-padded to 4, allocated per tenant/year.
`DocumentNumberService.next(tenantId, docType)` locks the sequence row (`SELECT … FOR UPDATE`),
increments, and returns the formatted value inside the caller's transaction. Gaps are acceptable;
duplicates are not (unique constraint `(tenant_id, number)`).

### 5.3 Quote lifecycle

```
DRAFT ──send──▶ SENT ──accept──▶ ACCEPTED ──convert──▶ CONVERTED
  │              │  └──decline──▶ DECLINED
  │              └──(validUntil < today ⇒ EXPIRED on read/accept)
  └──delete (hard, drafts only)
```

- Editing is allowed only in `DRAFT`; lines are replaced wholesale.
- `send` requires ≥ 1 line and `total > 0`; sets `issueDate` if absent and `validUntil` default +14 days.
- `accept`/`decline` only from `SENT`; `accept` re-evaluates expiry and transitions to `EXPIRED` first.
- `convert` requires `ACCEPTED`, creates one job (with quote lines carried for later invoicing),
  assigns technicians with conflict checks, and sets `CONVERTED`.
- Expiry is materialised lazily: any read that sees `SENT` + past `validUntil` presents `EXPIRED`
  (and persists the transition).

### 5.4 Job lifecycle

```
SCHEDULED ──start──▶ IN_PROGRESS ──complete──▶ COMPLETED
    │                    │
    └──cancel────────────┴──cancel──▶ CANCELLED
```

- `COMPLETED` sets `completedAt`; further edits are rejected except adding notes by an authorised user.
- `CANCELLED` requires a note; assignments remain for history but no longer conflict.
- Rescheduling allowed only from `SCHEDULED`; the conflict check excludes the job itself.
- Conflict check: any assigned technician with an existing non-cancelled job where
  `existing.start < new.end AND existing.end > new.start` → `409 JOB_SCHEDULE_CONFLICT` with a
  `conflicts[]` array of `{userId, fullName, jobId, number, scheduledStart, scheduledEnd}`.
- Recurrence: occurrences are generated at `+7d`, `+14d` (or `+1 month` calendar-month arithmetic),
  each validated before insert; any conflict aborts the entire series (single transaction). Series
  jobs share `series_id` (the first job's id) and `recurrence`; each gets its own number.
- Technician assignment requires an active user in the tenant whose role is not `TECHNICIAN`-blocked
  (`TECHNICIAN`, `MANAGER`, `ADMIN`, `OWNER` all assignable). Duplicate assignment → `409`.

### 5.5 Invoicing

- Creating from a job: lines are copied from the job's source quote (snapshot values), unless the
  request supplies lines; a job can be invoiced **once** (`409 CONFLICT` if an active invoice exists).
- `dueDate` default = `issueDate + 14 days`; must be ≥ issue date.
- Sending requires ≥ 1 line and `total > 0`; invoice becomes visible to reporting as receivable.
- Payments: `amount ≤ balance` (`422 PAYMENT_EXCEEDS_BALANCE`), `paidAt ≤ now + 5 min`, amount > 0.
  Status is recomputed transactionally: 0 → previous (`SENT`/`DRAFT`), `< total` → `PARTIALLY_PAID`,
  `≥ total` → `PAID` with `paidAt` set.
- Void requires `amountPaid = 0`, `status != VOID`; reason required; voids are terminal.
- Reversing a payment recomputes status backwards (e.g. `PAID → PARTIALLY_PAID`).
- Deleting an invoice is not exposed in v1; void instead.

### 5.6 Archive / deactivate semantics

- Customer archive: blocked while a `SCHEDULED` or `IN_PROGRESS` job exists. Archived customers are
  hidden from default lists and cannot receive new quotes/jobs (`409 IMMUTABLE_RESOURCE`).
- Service deactivation: hidden from pickers, still resolvable on historical quotes.
- User deactivation: login blocked, refresh tokens revoked, assignments preserved. Cannot deactivate
  self or the last active `OWNER`.

### 5.7 Team and roles

- Registration creates the first `OWNER`.
- `OWNER`/`ADMIN` can create users with roles `ADMIN`, `MANAGER`, `TECHNICIAN` (not `OWNER` in v1).
- Role changes and deactivations are audited; the last active owner cannot be demoted or disabled.
- Email uniqueness is global; adding an existing email → `409 EMAIL_ALREADY_EXISTS`.

---

## 6. Validation rules

All request DTOs are Java records with Jakarta Validation; enum values are parsed case-insensitively
but must be in the allowed set. Validation messages are field-scoped.

| Field | Rule |
| --- | --- |
| `orgName` | 2–120 chars, not blank |
| `fullName` | 2–120 chars |
| `email` | RFC-ish email, ≤ 254 chars, normalised to lowercase |
| `password` | 12–72 chars, at least one letter and one digit; not equal to email; rejected list (`password`,`123456789012`) |
| `customer.name` | 1–160 chars |
| `phone` | ≤ 32 chars, pattern `^[0-9+()\-.\s]{3,32}$` when present |
| `postalCode` | ≤ 16 chars (international) |
| `country` | 2-letter ISO code |
| `quantity` | > 0, ≤ 100000, scale ≤ 3 |
| `unitPrice` / `basePrice` | ≥ 0, ≤ 1,000,000, scale ≤ 2 |
| `taxRate` | 0.0000–1.0000 |
| `discountAmount` | ≥ 0, ≤ subtotal (validated after pricing) |
| `validUntil` | ≥ `issueDate` |
| `scheduledStart` / `scheduledEnd` | required; end > start; duration 15 min–24 h; start not more than 2 years ahead |
| `occurrences` | 2–26 (series only) |
| `amount` (payment) | > 0, scale ≤ 2, ≤ balance |
| `reason` (void/cancel) | 3–500 chars |
| `size` | 1–100; `page` ≥ 0 |
| IDs in path | must parse as UUID → else `400 MALFORMED_REQUEST` |

Domain validations that need database state (uniqueness, balance, conflicts, transitions) run in the
service layer and raise typed exceptions mapped to the codes in §3.2.

---

## 7. Transaction, concurrency and consistency

- One HTTP request = one transaction at the service boundary (`@Transactional`), read-only for GETs.
- Aggregate mutations use JPA `@Version`; `ObjectOptimisticLockingFailureException` →
  `409 CONCURRENT_MODIFICATION`.
- Number allocation uses a pessimistic row lock inside the same transaction as the insert, so
  numbering can never duplicate; concurrent creation serialises briefly on that row.
- Quote conversion, invoice creation from a job, payment recording and series generation are atomic.
- Reporting reads use aggregate JPQL queries (no N+1) and a read-only transaction.
- A request-scoped `Clock` bean (UTC) is injected so date logic is testable.

---

## 8. Multi-tenancy implementation

1. `JwtAuthenticationFilter` validates the token, builds `AuthenticatedUser(userId, tenantId, role,
   email, fullName)`, and sets `TenantContext` for the request (cleared in `finally`).
2. Controllers take `@AuthenticationPrincipal AuthenticatedUser`; they never accept tenant input.
3. Services obtain the tenant id from the principal and call tenant-scoped repositories:
   `findByIdAndTenantId`, `findAllByTenantId…`, `existsByTenantIdAnd…`. A miss returns `NOT_FOUND`.
4. New aggregates are constructed with `tenantId` from the principal; JPA `@PrePersist` asserts the
   tenant matches `TenantContext` (fails closed on programming error).
5. Cross-tenant references are rejected by validating the referenced customer/location/job/quote in
   the same tenant before use.
6. `TenantContext` is empty for public endpoints (`/auth/**`, actuator) and cleared per request;
   any authenticated service call with an empty tenant raises an internal error rather than querying
   unscoped data.

Planned hardening (not v1): composite foreign keys `(id, tenant_id)`, PostgreSQL row-level security
with `SET LOCAL app.tenant_id`, and per-tenant encryption keys.

---

## 9. Security controls

### 9.1 Tokens

| Property | Access token | Refresh token |
| --- | --- | --- |
| Format | JWT HS256 | 32 random bytes, base64url (opaque) |
| Claims | `iss=operonix`, `aud=operonix-web`, `sub=userId`, `tid`, `role`, `email`, `name`, `iat`, `exp`, `jti` | — |
| TTL | 15 min (configurable 5–60) | 30 days |
| Storage | client memory | client secure storage; SHA-256 hash server-side |
| Rotation | — | single use; each refresh issues a new token and revokes the old one |
| Chain | — | `family_id` shared by every rotation of one login; `replaced_by` links the sequence |
| Revocation | stateless (short TTL) | `revoked_at`; logout, password change, deactivation and reuse detection |

Reuse detection: presenting a token that was already rotated revokes every token in that `family_id`
(not the user's other sessions, which keep their own families) and returns `REFRESH_TOKEN_REUSED`; the
SPA forces re-login. The event is audited as `REFRESH_TOKEN_REUSE`. A concurrent refresh race — two tabs
rotating the same token — therefore ends in one success and one forced re-login, which is the intended
trade against silent token replay.

Secret handling: `OPERONIX_JWT_SECRET` must be ≥ 32 bytes. In `dev`, a documented non-secret default
is allowed; in `prod` the application refuses to start without a secret or with the dev default.

### 9.2 Role matrix

| Capability | OWNER | ADMIN | MANAGER | TECHNICIAN |
| --- | --- | --- | --- | --- |
| View dashboard (money) | ✓ | ✓ | ✓ | — (operational counters only) |
| Customers / locations | RW | RW | RW | read assigned jobs' customer contact |
| Catalog | RW | RW | RW | read |
| Quotes | RW | RW | RW | — |
| Jobs | RW | RW | RW | read/transition own assignments |
| Invoices / payments | RW | RW | RW | — |
| Team management | RW | RW | — | — |
| Workspace settings | RW | RW | — | — |
| Audit log | ✓ | ✓ | — | — |

Technicians receive `403` on financial endpoints and customer list endpoints; `GET /jobs/{id}`
returns `404` for jobs they are not assigned to (no existence leak).

### 9.3 Transport and browser protections

- Security headers on all responses: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
  for API responses, HSTS when `https` is detected.
- CORS: explicit allowed origins from `OPERONIX_CORS_ORIGINS` (default `http://localhost:5173`);
  methods `GET,POST,PUT,DELETE,OPTIONS`; headers `Authorization,Content-Type,X-Request-Id`; no
  credentials (bearer tokens, not cookies).
- No server session, no CSRF token (stateless bearer API); state-changing endpoints still require a
  valid bearer token.
- Rate limiting: login 10 attempts / 5 min per `(ip, email)`, register 5 / 10 min per IP,
  refresh 30 / 5 min per IP. Counters live in the shared `rate_limit_counter` table (atomic upsert per
  fixed window) so the limit holds across every Cloud Run instance instead of multiplying with replica
  count; `429` + `Retry-After` computed from the window end. Windows older than 24 h are pruned
  opportunistically every 500 calls.

### 9.4 Password handling

BCrypt strength 12. Password policy per §6. Login uses a constant-time comparison via the encoder and
returns the same message for unknown email and wrong password; unknown-email attempts still run a
dummy hash to reduce timing leakage. Passwords, hashes and tokens are never logged.

### 9.5 Audit events

Recorded in the same transaction as the change: `TENANT_CREATED`, `USER_REGISTERED`, `USER_LOGIN`,
`USER_LOGIN_FAILED`, `USER_CREATED`, `USER_UPDATED`, `USER_DEACTIVATED`, `PASSWORD_CHANGED`
(reserved), `WORKSPACE_UPDATED`, `CUSTOMER_CREATED`, `CUSTOMER_UPDATED`, `CUSTOMER_ARCHIVED`,
`CUSTOMER_RESTORED`, `LOCATION_CREATED`, `LOCATION_UPDATED`, `LOCATION_DELETED`, `SERVICE_CREATED`,
`SERVICE_UPDATED`, `QUOTE_CREATED`, `QUOTE_UPDATED`, `QUOTE_SENT`, `QUOTE_ACCEPTED`,
`QUOTE_DECLINED`, `QUOTE_CONVERTED`, `QUOTE_DELETED`, `JOB_CREATED`, `JOB_UPDATED`,
`JOB_STATUS_CHANGED`, `JOB_ASSIGNED`, `JOB_UNASSIGNED`, `INVOICE_CREATED`, `INVOICE_SENT`,
`INVOICE_VOIDED`, `PAYMENT_RECORDED`, `PAYMENT_REVERSED`.

`detail` stores a small JSON object (changed fields, amounts, reason) — never secrets or full
customer records. Audit rows are append-only; no update/delete API.

---

## 10. Observability

- `RequestIdFilter` accepts `X-Request-Id` (validated `[A-Za-z0-9-]{8,64}`) or generates a UUID,
  puts it in MDC (`requestId`), response header, and error payloads.
- `JwtAuthenticationFilter` adds `tenantId`, `userId` to MDC after authentication.
- Log pattern: timestamp, level, logger, `requestId`, `tenantId`, `userId`, message — JSON in `prod`.
- Log levels: `INFO` for lifecycle, `WARN` for denied auth/validation spikes, `ERROR` for 5xx with
  stack trace server-side only.
- Actuator exposes `health` (DB check), `info`; metrics/Prometheus are opt-in via config.

---

## 11. Testing strategy

| Layer | Tooling | Coverage |
| --- | --- | --- |
| Unit | JUnit 5 + AssertJ | `MoneyCalculatorTest`: line rounding, header discount allocation, tax, half-up behaviour, zero/negative guards |
| API integration | `@SpringBootTest` + MockMvc + embedded PostgreSQL + Flyway | `AuthFlowIT`: registration, login, refresh rotation + reuse detection, logout, cross-tenant 404s, role enforcement (technician/manager/owner matrix), audit filters, create-response timestamps. `GlobalFoundationIT`: public reference data, country-derived registration defaults, workspace regional update round-trip, shared rate-limit counter, multi-workspace membership resolution. `SalesFlowIT`: quote totals and transitions, discount inheritance into the derived invoice, job conflicts and series rollback, invoice status/balance maths, over-payment rejection, payment reversal, dashboard metrics |
| Static hosting / packaging | `@SpringBootTest` + MockMvc (fixture bundle) and a plain unit test | `SpaHostingIT`: shell at `/`, deep-link fallback, asset served with its own content type, missing asset `404`, `/api/**` never rewritten, readiness public, CSP includes the font origins. `WebStaticConfigTest`: resolver rules (`""`, `"."`, routes, dirs, assets, traversal, server prefixes) |
| Frontend | `tsc -b` + `vite build` | Type-safe API client and pages compile; no automated browser suite in v1 |

Manual/HTTP verification used during development: `frontend/scripts/smoke.mjs` (full quote→cash
lifecycle plus isolation checks against a running API) and `frontend/scripts/demo-seed.mjs`
(seeds a reviewable workspace).

Test fixtures create tenants through the public registration API so tenant stamping is exercised.
Integration tests assert HTTP status **and** persisted state; isolation tests attempt cross-tenant
access for every module (`404`).

Known limitation: no automated test spins up a browser; UI verification is manual.

---

## 12. Frontend architecture

- **State:** TanStack Query for server state; React context for auth/session; local component state
  for forms. No global mutable store.
- **API client:** `fetch` wrapper adds `Authorization`, parses problem+json into `ApiError`,
  performs a single-flight refresh on `401`, then retries once. Only a rejected token (400/401/403)
  clears the stored refresh token: a network error or 5xx keeps it, so a backend restart does not
  sign the user out. Non-JSON 5xx responses surface an "API unavailable" message instead of a raw
  status code.
- **Routes:** `/login`, `/register` (public); `/` → dashboard; `/customers`, `/customers/:id`,
  `/quotes`, `/quotes/new`, `/quotes/:id`, `/schedule`, `/invoices`, `/invoices/:id`, `/catalog`,
  `/team`, `/settings`, `/audit` (guarded). Unauthorised role → friendly 403 panel, not a crash.
- **Layout:** fixed sidebar (desktop) / top bar + drawer (mobile), workspace switcher placeholder,
  user menu with logout. Global "New" menu (customer, quote, job) for permitted roles.
- **Forms:** controlled inputs with client-side required/format checks mirroring server rules; server
  `errors[]` are mapped back onto fields; server is authoritative.
- **Money/date display:** one formatter (`Intl.NumberFormat` with tenant currency; dates in tenant
  timezone); inputs send ISO strings/dates.
- **Design system:** Tailwind tokens (ink, surface, brand emerald, accent amber, danger), 12-column
  responsive grid, keyboard-accessible dialogs, empty states with a primary action, skeleton loading,
  inline error banners, toast for success.
- **Accessibility:** labelled inputs, focus ring, `aria-live` for toasts, dialogs close on Escape.

---

## 13. Configuration

| Property | Env var | Default (dev) | Notes |
| --- | --- | --- | --- |
| `operonix.jwt.secret` | `OPERONIX_JWT_SECRET` | dev-only constant | ≥ 32 bytes; prod fails fast |
| `operonix.jwt.access-token-ttl` | `OPERONIX_ACCESS_TTL` | `15m` | 5m–60m |
| `operonix.jwt.refresh-token-ttl` | `OPERONIX_REFRESH_TTL` | `30d` | 1d–90d |
| `spring.datasource.url` | `OPERONIX_DB_URL` | `jdbc:postgresql://localhost:5432/operonix` | |
| `spring.datasource.username` | `OPERONIX_DB_USER` | `operonix` | |
| `spring.datasource.password` | `OPERONIX_DB_PASSWORD` | `operonix` | secret |
| `operonix.cors.allowed-origins` | `OPERONIX_CORS_ORIGINS` | `http://localhost:5173` | comma separated |
| `operonix.api-docs.enabled` | `OPERONIX_API_DOCS` | `true` dev, `false` prod | also toggles `springdoc.*.enabled` in prod |
| `operonix.web.static-locations` | `OPERONIX_STATIC_LOCATIONS` | `classpath:/static/` | where the built SPA bundle is served from |
| `operonix.migrate-only` | — (CLI flag) | `false` | with `--spring.main.web-application-type=none`: run Flyway, exit 0 (Cloud Run migration job) |
| `spring.datasource.url` (prod default) | `OPERONIX_DB_INSTANCE`, `OPERONIX_DB_NAME` | socket `?host=/cloudsql/<instance>` | Cloud Run built-in connector |
| `spring.datasource.hikari.maximum-pool-size` | `OPERONIX_DB_POOL` | `10` (base), `5` (prod) | per instance; see connection budget |
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | `dev` | `prod` enables strict checks, JSON logs, graceful tuning |

---

## 14. Implementation checklist mapping

| LLD section | Implemented in | Verified by |
| --- | --- | --- |
| §4.1–4.2 auth/team | `identity` | `AuthFlowIT` |
| §4.3 customers | `customer` | `AuthFlowIT` (isolation), `SalesFlowIT` (usage in quotes/jobs) |
| §4.4 catalog | `catalog` | `AuthFlowIT` (timestamped create), `SalesFlowIT` (catalog-priced quote line) |
| §4.5 quotes | `quoting` | `SalesFlowIT` |
| §4.6 jobs | `scheduling` | `SalesFlowIT` |
| §4.7 invoices | `billing` | `SalesFlowIT` |
| §4.8 reporting | `reporting` | `SalesFlowIT` (dashboard), `AuthFlowIT` (technician view) |
| §8 tenancy | `security`, all services | `AuthFlowIT`, `SalesFlowIT` |
| §9 security | `config`, `security`, `identity` | `AuthFlowIT` (role matrix, audit, token lifecycle) |
| §5 money | `common.money` | `MoneyCalculatorTest`, `SalesFlowIT` |

---

## 15. Implementation notes and deviations

Decisions taken during implementation that refine (or relax) the design above. Each item was
verified against the running application, not just compiled.

| # | Area | Decision | Why |
| --- | --- | --- | --- |
| 1 | Persistence | `BaseEntity` implements `Persistable<UUID>`; ids are assigned in the constructor | Spring Data treats a non-null id as detached and routes `save()` through `merge()`. That returned a copy, so mutation responses omitted `createdAt`/`updatedAt` and every insert cost an extra select. Entities now persist directly, and services flush before mapping a response so generated timestamps are always present. |
| 2 | Filtering | List endpoints with optional filters use Criteria `Specification`s (`customers`, `quotes`, `jobs`, `invoices`, audit) | PostgreSQL cannot infer the type of a null bind parameter, so the JPQL `(:param is null or column = :param)` pattern failed with `could not determine data type of parameter`. Predicates are type-safe and index-friendly. |
| 3 | Quotes → invoices | `POST /jobs/{jobId}/invoice` copies the quote's header discount unless the request supplies one | The invoice must reproduce the accepted total, not just the line prices. Regression-tested in `SalesFlowIT` (`253.00` total with a `20.00` discount). |
| 4 | Authorization | `/workspace` and `/team` are OWNER/ADMIN; new `GET /team/assignable` serves scheduling pickers for MANAGER and above | Managers schedule crews but must not read team administration data. The narrow endpoint keeps that boundary without breaking the job and convert dialogs. |
| 5 | Audit | Search lives in `AuditService.search(...)` with `Specification`s; `from` after `to` returns `400` | Same null-parameter fix; the range check removes an ambiguous empty result. |
| 6 | Payments | Payments are accepted only while an invoice is `SENT` or `PARTIALLY_PAID`; reversal recomputes status and balance | Tightened during implementation. Reversal moves the invoice back to `PARTIALLY_PAID` (or `SENT`) and is audited as `PAYMENT_REVERSED`. |
| 7 | Frontend versions | React 18.3, Vite 5.4, React Router 6.28, Tailwind 3.4 | Stable versions available in this environment; nothing in the design depends on newer APIs. |
| 8 | Migrations | `V1__schema.sql` only; no `V2__seed_reference.sql` | There is no reference data to seed: service catalogues are tenant-owned and currencies are validated codes. |
| 9 | Session resilience | A network or 5xx failure while refreshing keeps the refresh token; only `400/401/403` clears it | A backend restart must not sign users out. Verified by stopping the API, reloading, restarting it, and reloading again (session restored without re-login). |
| 10 | Role model | One user belongs to exactly one workspace in v1; a `membership` table is the documented v2 extension | Unchanged from the design; invitations and SSO are out of scope for v1. |

---

## 16. Deployment packaging (Google Cloud)

Added for the production packaging milestone; no domain rule, endpoint or schema changed.

### 16.1 Same-origin static hosting

One Cloud Run container serves the built SPA and `/api/v1`. The rules live in three places:

| Component | Responsibility |
| --- | --- |
| `config/WebStaticConfig` | Registers the single `/**` resource handler for `operonix.web.static-locations` and a `PathResourceResolver` that falls back to `index.html` **only** for client-side routes |
| `config/SpaRootController` | `GET /` forwards to `index.html` (`ResourceHttpRequestHandler` skips resolution when the path is empty, so the root cannot be covered by the resolver) |
| `config/SecurityConfig` | `/api/**` and `/actuator/**` require a token; the static shell is public (it contains no tenant data); `/actuator/health{,/**}` and `/actuator/info` are public for probes |

Fallback is refused when the path starts with `api/`, `actuator/`, `v3/`, `swagger-ui/`, `error`,
`webjars/`, or when the last segment contains a `.` (a missing hashed asset must 404, not return
HTML with the wrong MIME type). Root requests arrive as `""`, `"/"` or `"."` depending on the path
match and are normalised. `spring.web.resources.add-mappings=false` keeps this the only resource
handler, removing any ambiguity about which handler serves a deep link.

The production CSP allows exactly two extra origins for the web fonts in `index.html`
(`fonts.googleapis.com` for the stylesheet, `fonts.gstatic.com` for the files) and adds
`connect-src 'self'` and `form-action 'self'`. Without those directives `default-src 'self'` blocks
the fonts.

### 16.2 Container and runtime

* `Dockerfile` — `node:22-alpine` (SPA) → `maven:3.9.9-eclipse-temurin-21` (jar, with
  `frontend/dist` copied into `src/main/resources/static`, asserted with `jar tf`) →
  `eclipse-temurin:21-jre-alpine` running as the non-root `operonix` user.
* `server.port=${PORT:8080}`; graceful shutdown with a 10 s phase timeout in `prod` (Cloud Run sends
  SIGTERM and kills after 10 s); `exec java` keeps the JVM as PID 1 so the signal lands.
* Health/readiness: `/actuator/health/liveness` and `/actuator/health/readiness` (readiness includes
  the `db` indicator).
* Migrations: Flyway on startup (lock-protected) plus `config/MigrationRunner`, which runs only when
  `--operonix.migrate-only=true` and exits 0 after logging the applied version — used by the
  `operonix-migrate-<env>` Cloud Run job so schema changes land before a revision serves traffic.
* `logback-spring.xml` — `prod` selects the Logstash JSON encoder on stdout (`severity`, `logger_name`,
  `message`, MDC `requestId`/`tenantId`/`userId`, stack trace) for Cloud Logging; every other profile
  keeps the human-readable single-line pattern.
* Dependencies added: `logstash-logback-encoder` 8.0 (structured logs) and
  `com.google.cloud.sql:postgres-socket-factory` 1.21.0 (optional Cloud SQL Java Connector path).

### 16.3 Configuration in production

`application-prod.yml` contains no secret. The datasource defaults to
`jdbc:postgresql:///${OPERONIX_DB_NAME}?host=/cloudsql/${OPERONIX_DB_INSTANCE}`; the JWT secret has
no default (startup fails without it) and the dev value is rejected under `prod` by `JwtService`.
Secrets are injected from Secret Manager as `OPERONIX_JWT_SECRET` and `OPERONIX_DB_PASSWORD`.
`OPERONIX_CORS_ORIGINS` is empty by default because the deployment is same-origin.

### 16.4 Deployment automation

| Artifact | Purpose |
| --- | --- |
| `cloudbuild.yaml` | Build + push to Artifact Registry; with `_DEPLOY=true` also updates the migration job, executes it, then deploys Cloud Run |
| `deploy/gcp/deploy.sh`, `deploy/gcp/deploy.ps1` | Bootstrap (APIs, registry, Cloud SQL, secrets, service account), build, job, migrate, deploy, smoke — destructive steps require `--yes`/`-Yes` |
| `deploy/gcp/.env.example` | Every configurable value; `.env` is git-ignored |
| `infra/terraform/` | Minimal IaC for Cloud Run, Cloud SQL, Artifact Registry, Secret Manager, IAM, optional bucket and GitHub OIDC |
| `.github/workflows/ci.yml`, `deploy.yml` | Verification (tests, typecheck, build, image) and WIF-based deployment |

See [GOOGLE_CLOUD_DEPLOYMENT.md](GOOGLE_CLOUD_DEPLOYMENT.md) for the architecture and
[OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md) for day-2 operations.

---

## 17. Operonix 2.0 P0 — implemented changes

Scope: industry neutrality, global tenant configuration, multi-workspace identity and auth hardening.
No financial, scheduling or reporting rule changed. See HLD §14 for the architectural view.

### 17.1 New module `com.operonix.global`

| Class | Responsibility |
| --- | --- |
| `Country` / `Currency` | Read-only platform entities mapped to the V2 tables |
| `IndustryTemplate` | Enum of 10 profiles: label, description, default job title; `from(null)` → `GENERAL_SERVICE`, unknown → `null` (caller turns that into a validation error) |
| `ReferenceDataService` | `all()` for the public catalogue; `requireCountry` / `requireCurrency` normalise and validate; `resolveIndustry` maps the request value onto the enum |
| `ReferenceDataController` | `GET /api/v1/reference`, public, no tenant context |

Platform tables carry no `tenant_id`. They are the one category of data the platform owns outright, so
`TenantContext` remains empty for `/reference` exactly as it does for `/auth/**`.

### 17.2 Regional configuration rules

- `countryCode` drives defaults only. Changing it in settings does **not** silently rewrite `currency`,
  `locale` or `timezone`; it seeds them on registration and is otherwise an independent choice.
- `locale` must match `^[a-z]{2}(-[A-Z]{2})?$` (DB check + DTO validation); it is the single input to
  `Intl` formatting on the client.
- `industryTemplate` is constrained by `ck_tenant_industry`; a value outside the catalogue is `400`.
- `legalName` and `taxNumber` are optional on every path. `taxNumber` is never returned in session
  payloads and only appears on `GET /workspace` for owners/admins.
- `onboardedAt` is stamped when the workspace completes the onboarding step; it is informational in P0.

### 17.3 Identity changes

`AuthService.register` now: validates regional inputs → creates tenant with derived defaults → creates
the owner row → issues the first token family. `AuthService.login` resolves candidates by lowercased
email, requires a workspace slug when more than one exists (`WORKSPACE_REQUIRED`), then verifies the
password against that membership only. `TeamService` keeps per-workspace email uniqueness and returns
`409 EMAIL_IN_USE` when the address already exists in *this* workspace (an address in another workspace
is allowed and must not be disclosed).

### 17.4 Rate limiting

`AuthRateLimiter.tryAcquire(key, limit, window)` performs one `insert … on conflict … returning hits`, so
counting is atomic across instances. Keys are namespaced by the caller before the call
(`login:<ip>:<email>`, `register:<ip>`, `refresh:<ip>`). `retryAfterSeconds` derives `Retry-After` from the
window end. `reset(key)` exists for tests only.

### 17.5 Frontend contract additions

`/reference` is fetched once per session and cached by TanStack Query; onboarding and workspace settings
render from it. See [FRONTEND_GLOBALIZATION.md](FRONTEND_GLOBALIZATION.md) for the formatting authority
(`lib/format.ts`), the i18n catalogue and the tenant-locale binding.

### 17.6 Known limitations (deliberate, P0)

- One `app_user` row per membership: a person with two workspaces has two rows and two passwords. A
  `membership` table with a single credential is the P1 target.
- No per-industry terminology overrides yet; the template changes the default job title only.
- `country.postal_code_regex` is stored but not enforced on customer addresses, to avoid rejecting
  legitimate international formats before address handling is redesigned.
- Only English copy is catalogued; `locale` currently changes formatting, not language.

---

## 18. P1a — global financial foundation (implemented)

### 18.1 Module map

New `com.operonix.finance` module, plus a rule-driven `com.operonix.document` addition:

| Class | Responsibility |
| --- | --- |
| `FinanceService` | Builds the immutable `DocumentSetup` (entity, currency + precision, tax profile + mode, base currency, FX rate) that quoting and billing snapshot before pricing. |
| `TaxService` | Lists the platform catalogue, owns tenant tax profiles, resolves `LineTax` (rate, components, mode) for a line, and exposes currency precision. |
| `OrganisationService` | Legal entities, business locations and `tenant_settings`. |
| `ExchangeRateService` | Dated rate lookup (direct, inverse, identity) and conversion. |
| `TaxSnapshotWriter` | Writes and reads `line_tax_component` rows for a document. |
| `WorkspaceFinanceBootstrap` | Seeds entity, country-default tax profile, settings row and "Main location" for a new workspace, driven by `WorkspaceProvisionedEvent` so `tenancy` never imports `finance`. |
| `DocumentNumberService` | Formats numbers from `document_number_rule`; the default reproduces v1 exactly. |

### 18.2 Money rules

- **Scale comes from the currency.** `MoneyCalculator.calculate(lines, discount, scale)` where `scale =
  currency.minor_units`: JPY 0, USD/INR/EUR 2, KWD 3. The two-argument overload keeps 2 dp for legacy
  callers. All arithmetic is `BigDecimal` with `HALF_UP`.
- **Exclusive vs inclusive.** Inclusive derives the net as `gross / (1 + Σrate)` rounded at the currency
  scale and the tax as `gross − net`, so the entered gross is exactly the total. Exclusive adds
  `round(net × Σrate)` on top.
- **Discount** is clamped to the subtotal, then the tax is scaled by `(subtotal − discount) / subtotal`
  (6 dp) exactly as before, so pre-P1a outputs are bit-identical (characterization tests in
  `MoneyCalculatorTest` were kept unchanged).
- **Components.** Each line's discounted tax is allocated per component and reconciled into the last
  taxable row; the document total is the roll-up of those rows, reconciled again. Printed rows always
  sum to `taxAmount`.
- **Compound components are summed**, not compounded; all seeded jurisdictions are additive.

### 18.3 Document snapshots

A quote or invoice stores the entity, profile name, tax mode, currency, `fx_rate`, `base_currency`,
`base_total` (and for invoices `tax_number`, `reverse_charge`, `base_amount_paid`) at creation. Pricing
never re-reads configuration afterwards: changing a profile, rate, or numbering rule cannot alter an
issued document. `base_amount_paid` moves with each payment and reversal at the snapshotted rate.

### 18.4 API surface (all tenant-scoped, `Authorization: Bearer`)

| Method + path | Roles | Notes |
| --- | --- | --- |
| `GET /api/v1/tax/definitions` | any member | Platform catalogue with components. |
| `GET/POST/PUT/DELETE /api/v1/tax/profiles[/{id}]` | read: any, write: OWNER/ADMIN | Delete deactivates; a default profile cannot be removed. |
| `GET/POST/PUT/DELETE /api/v1/legal-entities[/{id}]` | read: any, write: OWNER/ADMIN | Retires instead of deleting; default is unique per workspace. |
| `GET/POST/PUT/DELETE /api/v1/business-locations[/{id}]` | read: any, write: OWNER/ADMIN | Requires an entity; timezone validated against IANA. |
| `GET/PUT /api/v1/workspace/financial-settings` | read: any, write: OWNER/ADMIN | Locale, fiscal year, defaults; returns `currencyPrecision`. |
| `GET /api/v1/number-rules`, `PUT /api/v1/number-rules/{docType}` | read: any, write: OWNER/ADMIN | `prefix` matches `^[A-Za-z0-9/_-]{1,12}$`, padding 1–10. |
| `GET/POST /api/v1/exchange-rates` | read: any, write: OWNER/ADMIN | Unique per `(base, quote, asOf)`; `base ≠ quote`. |

Quote/invoice create accepts an optional `currency`; the document is issued in it and converted to the
entity's base currency at that day's rate. A missing rate fails with
`422 FX_RATE_MISSING` naming the pair — never a silent 1:1 conversion.

### 18.5 Validation and error handling

- `countryCode` must exist in `country`; `baseCurrency`/`currency` must exist in `currency`.
- Duplicate entity, location or profile names (case-insensitive, per tenant) → `400 VALIDATION_FAILED`.
- Two defaults (entity, location, profile) → `409 CONFLICT`; removing the current default → `409`.
- Removing an entity that still has locations → `409`; every cross-tenant id → `404` (never `403`, so
  ids cannot be probed).
- Tax component rates, FX rates and discounts are validated by `CHECK` constraints as well as the DTOs.

### 18.6 Security and tenancy

- Every repository call is `...AndTenantId`; `TenantEntity`'s pre-persist guard still rejects a
  cross-tenant write. Integration tests cover reading and updating another tenant's entity.
- `tax_definition`/`tax_component`/`currency`/`country` are platform read-only reference data.
- No secrets, keys or credentials are introduced by P1a.

### 18.7 Deviations from the roadmap wording

- P1a-7 (country-aware address model) and P1a-8 (fiscal-year *rendering*) are partially delivered:
  `address_format`/postal validation remain unenforced, and the fiscal-year setting is stored but not
  yet used by reports.
- P1a-9 (payment reversals as `payment_reversal` rows) shipped in P1a as delete-the-payment with
  base-currency tracking. **Closed in P2d**: reversal now marks the existing row (`reversed_at`,
  `reversed_by`) instead of deleting it — a dedicated ledger table was not needed, see §21.1.
- P1a-10 (payment gateway abstraction) was not started, as approved.

---

## 19. P1b costing, P2a analytics, P2b AI (implemented)

### 19.1 Migrations

| Version | File | Contents |
| --- | --- | --- |
| V4 | `V4__costing_engine.sql` | `work_log`, `vendor`, `expense`, `job_cost`; `app_user.hourly_cost`; `service_item.cost`; `job.actual_start/actual_end`; indexes on job/date/vendor |
| V5 | `V5__analytics.sql` | `metric_daily` (tenant, metric, day, location), `analytics_refresh`, two scan indexes on `invoice`/`job` |
| V6 | `V6__operonix_ai.sql` | `ai_settings`, `ai_usage`, `ai_query_log` + index on `(tenant_id, created_at DESC)` |

All three are additive: no column is dropped, no existing row is rewritten, and every new column has
a default. Old tenants get `hourly_cost = 0` and `service_item.cost = 0`, which the API interprets as
"cost not recorded" rather than "free".

### 19.2 Costing API (`com.operonix.global`)

| Method | Path | Roles |
| --- | --- | --- |
| GET/POST/PUT | `/api/v1/vendors[/{id}]` | owner, admin, manager |
| GET | `/api/v1/jobs/{jobId}/costing` | any member of the tenant |
| GET | `/api/v1/jobs/{jobId}/profitability` | any member of the tenant |
| POST | `/api/v1/jobs/{jobId}/costs/recompute` | owner, admin, manager |
| GET/POST | `/api/v1/jobs/{jobId}/work-logs` | any member; a technician may only change *their own* time entries |
| POST/DELETE | `/api/v1/work-logs/{id}[/stop]` | owner of the log, or office roles |
| GET/POST | `/api/v1/jobs/{jobId}/expenses` | office roles write |
| POST | `/api/v1/expenses` | office roles (unattached overhead) |
| DELETE | `/api/v1/expenses/{id}` | office roles |

Business rules:

1. A work log must belong to the job's tenant; `start < end`; a technician may only create, stop or
   delete their own entries (office roles may correct anyone's). Overlapping entries are allowed on
   purpose — split shifts and travel time are legitimate — and labour cost is the sum of closed
   minutes rather than a span.
2. Labour cost = `minutes / 60 × hourly_cost`, rounded half-up to the currency's minor units. A member
   with `hourly_cost = 0` makes labour cost **unknown**, not zero.
3. Material/equipment cost = `quantity × service_item.cost`; a line whose catalogue item has
   `cost = 0` is also unknown.
4. `job_cost` is upserted per job (`tenant_id, job_id` unique) inside the same transaction as the
   triggering write. `recompute` deletes and re-inserts the row, so running it twice yields the same
   numbers.
5. `profitability` returns `available=false` + `reasons[]` when any input is unknown; it never
   substitutes zero. `directProfit` is revenue − direct cost and is explicitly **not** net profit.
6. Every mutation writes an `audit_event` (`WORK_LOG_*`, `EXPENSE_*`, `VENDOR_*`, `JOB_COST_RECOMPUTED`).

### 19.3 Analytics API (`com.operonix.analytics`)

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/v1/analytics/catalogue` | metric keys, labels, units, formulas, required inputs |
| GET | `/api/v1/analytics/overview?from&to&locationId` | current vs previous period, reporting currency, notes |
| GET | `/api/v1/analytics/trend?metric&granularity&…` | day/week/month buckets |
| GET | `/api/v1/analytics/breakdown?dimension&…` | customer, service, team member, location |
| GET | `/api/v1/analytics/locations` | per-location totals |
| GET | `/api/v1/analytics/ageing` | 0–30/31–60/61–90/90+ buckets |
| GET | `/api/v1/analytics/jobs` | per-job revenue, cost, margin, drill-down ids |
| GET | `/api/v1/analytics/status` | last refresh window and timestamp |
| POST | `/api/v1/analytics/refresh` | owner/admin/manager; rebuilds a window idempotently |

Class-level `@PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")`; every query is additionally
scoped by `tenant_id` from `CurrentUser`, never from a request parameter. Unknown metrics and
dimensions are rejected by the catalogue (`VALIDATION_FAILED`), which is also the guard that stops the
AI layer from requesting an arbitrary aggregate.

### 19.4 AI API (`com.operonix.ai`)

| Method | Path | Roles | Behaviour |
| --- | --- | --- | --- |
| GET | `/api/v1/ai/status` | all members | enabled, provider configured, quota used |
| GET | `/api/v1/ai/usage` | office | quota + last 20 log entries |
| PUT | `/api/v1/ai/settings` | owner, admin | toggle availability, set daily limit |
| POST | `/api/v1/ai/ask` | office | question → validated intent → server metrics → narrative |
| POST | `/api/v1/ai/quote-assist` | office | suggestions with cost, history, margin, rationale |
| GET | `/api/v1/ai/insights` | office | warnings/positives derived by rules |

Error and degradation behaviour:

| Situation | Result |
| --- | --- |
| AI disabled for the workspace, or globally | `status=DISABLED`, built-in narrative, numbers still returned, no provider call |
| No provider key | `status=DEGRADED`, `narrativeFromModel=false`; the UI labels it "built-in explanation" |
| Daily quota reached | `status=QUOTA_EXCEEDED`; the request is logged, no provider call |
| Provider timeout / non-2xx / bad JSON | `status=DEGRADED`; logged with the reason; the numbers are unaffected |
| Metric unavailable (missing costs) | metric present with `available=false` and `reason`; never rendered as 0 |
| Question outside the catalogue | `VALIDATION_FAILED` with the supported subjects listed |

Security invariants: the provider payload contains metric labels, values, dates and currency only —
no names, emails, addresses or invoice numbers. `AiProperties.apiKey` is never logged, never returned
and never reaches the client bundle; the response exposes only `provider` and `model`. Before a model
answer is used, `AiService.grounded()` checks that every number in the reply appears in the payload it
was given; an ungrounded reply is discarded in favour of the deterministic summary.

### 19.5 Frontend additions

- `JobCostingPanel.tsx` inside the job drawer: work logs (start/stop), expenses, vendors, cost
  breakdown, "cost not recorded" state, recompute action, planned vs actual profitability.
- `pages/Analytics.tsx` (`/analytics`): range + location filters, KPI tiles with change vs previous
  period, trend chart, breakdown table, ageing, job profitability table, refresh status, drill-down
  links to invoices/jobs/customers.
- `pages/Assistant.tsx` (`/assistant`): question box with examples, metric readings, breakdown table,
  narrative provenance, insights list, quotation assistance, AI settings and usage.
- `api/analytics.ts`, `api/ai.ts`; nav entries and route guards (`OFFICE` roles) added to
  `App.tsx`/`Layout.tsx`; `nav.analytics` and `nav.assistant` are in the i18n catalogue.

### 19.6 Tests added

| Test | Covers |
| --- | --- |
| `costing.CostingFlowIT` (2) | work log → labour cost, expense materialisation, unknown-cost handling, cross-tenant 404, recompute idempotency |
| `analytics.AnalyticsFlowIT` (2) | rollup refresh idempotency, overview reconciliation against invoices/payments, unavailable metrics, tenant isolation |
| `ai.AiFlowIT` (2) | ask → server-computed figures, disabled/degraded path, quota exhaustion, quote assist, insights, audit logging |
| `ai.AiGuardTest` (4) | intent parsing: whitelisted metrics only, relative ranges, unknown subject rejection, dimension extraction |

`mvn test` → **66 tests, 0 failures** (JDK 21, embedded PostgreSQL 16).

---

## 21. P2c/P2d — acceptance hardening (implemented)

### 21.1 Payment reversal is a mark, not a delete

`DELETE /api/v1/invoices/{invoiceId}/payments/{paymentId}` (OWNER/ADMIN) sets `payment.reversed_at` and
`payment.reversed_by` and recomputes `invoice.amount_paid` by summing the payments where
`reversed_at IS NULL` — never by subtracting, so a repeated or adjusted reversal cannot drift the stored
balance away from the rows behind it. Reversing an already-reversed payment returns
`409 CONFLICT`. The invoice response carries `reversedAt`/`reversedBy` in `payments[]`, and the UI lists
the row struck through with a "Reversed" badge and the reversal timestamp.

Every money aggregate filters reversed rows: `PaymentRepository.sumBetween/findAllBetween/
revenueByCustomer` (owner dashboard, reports), the analytics rollup `PAYMENTS_SQL`
(`metric_daily.payments_collected`), and the invoice balance itself.

### 21.2 Analytics refresh is concurrency-safe

`AnalyticsRollupRepository.rebuild` runs inside the caller's transaction (every analytics read is
`@Transactional`) and first takes `pg_try_advisory_xact_lock` keyed on the workspace. A second concurrent
rebuild writes nothing and reads the rows the first one is producing, instead of interleaving its own
delete/insert on the same keys. The batch insert additionally uses
`ON CONFLICT (tenant_id, metric_key, dimension_type, dimension_key, day) DO UPDATE`, so a rollup write can
never turn a read into a `409`/`500`; both rebuilds compute from the same source rows, so the value
converges either way. Regression: `AnalyticsFlowIT.parallelAnalyticsCallsShareOneRollupInsteadOfFailing`
fires nine concurrent overview/trend/breakdown requests on a cold rollup.

### 21.3 Public registration has no request tenant

`/auth/register` is `permitAll`, but the JWT filter still installs the session's tenant when a caller
presents a valid bearer token. The tenant guard then rejects the new workspace's *own* rows
(`app_user`, audit events) and the call failed with `500 INTERNAL_ERROR`. `AuthService.register` now
clears `TenantContext` for the duration of provisioning and restores it afterwards, because a
create-workspace call genuinely has no request tenant. Regression:
`AuthFlowIT.registeringAnotherWorkspaceWhileSignedInSucceedsAndStaysIsolated`.

### 21.4 Verified behaviour (session 8)

| Area | Evidence |
| --- | --- |
| Fresh migration | empty database → Flyway V1…V7, `ddl-auto=validate` on start |
| Upgrade migration | V3 database carrying P1a documents (1 invoice 319.34, 1 quote 319.34) → V4…V7, totals unchanged; V6 → V7 on the live database |
| Lifecycle over HTTP | `node frontend/scripts/smoke.mjs` → SEED OK (quote 319.34, invoice 319.34, partial 100 → balance 219.34 → paid, cross-tenant 404, anonymous 401) |
| Browser journeys | login, onboarding steps, customer/quote/job/expense/labour/timer/invoice/payment/reversal, analytics, AI assistant, technician scope, mobile width |
| Financial reconciliation | quote 360 − 20 + 28.05 = 368.05; invoice paid 50 / balance 318.05; job cost 42.50 material → profit 325.55, margin 88.45%; rollup revenue 773.99 = invoiced total 773.99 for the workspace |
| Regression suite | `mvn verify` → 68 tests, 0 failures; `npm run typecheck` → clean; `npm run build` → 452.01 kB JS |
| API acceptance probes | 22/22: reversal trail, repeated reversal 409, technician 403 on invoices/analytics/audit/team/payments/void, cross-tenant 404, 9 concurrent analytics reads 200, anonymous 401 |
| Browser responsiveness | invoice list/detail and the job drawer at 390 px carry no document-level horizontal scroll; wide tables scroll inside their own card |

### 21.4.1 Defects fixed in the acceptance pass

| Defect | Evidence | Fix |
| --- | --- | --- |
| The job drawer hid **Create invoice** as soon as a job was `COMPLETED`, which is exactly when invoicing happens; the backend accepts it (`requireInvoiceable` only refuses `CANCELLED`). | Completed J-2026-0004, drawer offered only “Start timer / Record expense / Close”. | Gate is now `job.status !== "CANCELLED"`; the job was then invoiced from the drawer (INV-2026-0003, 389.70). |
| The drawer rendered the job date from the **UTC** day but the time in the workspace zone, so a Los Angeles workspace showed “Nov 2, 2026 · 7:30 PM” for an instant that is Nov 1, 19:30 there. | J-2026-0004 (`2026-11-02T03:30Z`) rendered as Nov 2 while `formatTime` rendered 7:30 PM. | `formatCalendarDate(value, timezone)` in `lib/format.ts` converts the instant before formatting; the drawer and its subtitle use it. `formatDate` stays for date-only values such as issue dates. |
| A rejected over-payment was titled “Something went wrong”, implying a system failure. | Paying 500 against a 389.70 balance. | The validation heuristic in `ErrorBanner` also recognises “remaining balance”/“already”, so the banner reads “Check the highlighted fields”. |

### 21.5 Known limitations recorded, not fixed

- Labour cost uses whole minutes (`Duration.toMinutes()`), so a sub-minute timer entry costs `$0.00` and
  `job_cost.labor_cost` stays zero for it.
- The onboarding timezone defaults to the **browser** timezone; the country reference data carries
  currency/locale only, so choosing Japan from a device set to another zone needs a manual timezone edit.
- There is no idempotency key on `POST /invoices/{id}/payments`; the dialog's pending state prevents the
  common double-click (verified: two synchronous clicks created one payment), but a retried request from a
  flaky client would record twice.
- Analytics rollup rows are per tenant and built lazily on read, so a workspace that never opens analytics
  has an empty `metric_daily` until the first read or the scheduled job runs.
- Scheduling inputs (`datetime-local` in the quote conversion, job and payment dialogs) are interpreted in
  the **device** time zone, while every display uses the workspace zone. With device and workspace in the
  same zone — the normal case — this is invisible; a device set to IST scheduling “09:00” for a Los
  Angeles workspace produced a 19:30 local visit. Converting the input to the workspace zone is a
  behaviour change that needs an owner decision, so it is recorded here rather than changed.
- `revenue` is defined in the metric catalogue as the sum of issued invoice totals, so job profit and
  margin include any tax collected on behalf of a tax authority. This is the documented model; a
  net-of-tax basis would be a definition change, not a defect fix.
- The customer detail page still renders `createdAt`/`updatedAt` with the date-only helper, so a customer
  created in the last hours of a UTC day can show the next day's date in a westward workspace. The same
  mechanism as the fixed job-drawer defect, but it was not reproduced on live data.
- Line “Total” cells on an invoice show quantity × price plus tax while the invoice discount reduces the
  tax base, so their sum does not equal the invoice total when a discount exists (INV-2026-0001: lines 433.00
  vs total 405.94). The summary shows the authoritative figures; the column mixes bases.
