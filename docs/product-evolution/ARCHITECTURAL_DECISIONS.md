# Operonix — Architectural Decisions (Proposed ADRs)

**Status:** every ADR below is **Proposed**. None is Accepted or Implemented. An ADR becomes Accepted only
when the owner approves it in §Approval checklist at the end of this document.

Format: Context → Decision → Alternatives considered → Consequences → Reversibility → Phase.

---

## ADR-01 — Remain a modular monolith; do not split into services

**Context.** The MVP is a single Spring Boot application with package-per-domain modules
(`customer`, `quoting`, `scheduling`, `billing`, `identity`, `audit`, `reporting`, `catalog`, `tenancy`)
and one React SPA. New capabilities (BI, AI, automation) mostly read data the monolith already owns.

**Decision.** Stay a modular monolith through P3. Enforce module boundaries by convention plus
compile-time checks (ArchUnit rules: no `billing` → `reporting` dependency, no direct cross-module
repository access), and run background work as **separate deployables of the same codebase** (Cloud Run
Jobs invoking a job entrypoint), not as extra services.

**Alternatives.** (a) Extract BI/AI into services now — adds network hops, distributed transactions on
financial data, and two deployment pipelines for zero current benefit. (b) Serverless functions per
capability — worse cold starts and no shared transaction.

**Consequences.** Single deploy unit to operate and secure; module discipline must be tested, not assumed.
The first genuine split candidate is the analytics refresh job, which is already isolated by being a
batch entrypoint.

**Reversibility.** High — modules extracted later with no data model change.
**Phase.** Holds P0–P3.

---

## ADR-02 — Tenancy: shared schema with mandatory `tenant_id`, no RLS yet

**Context.** Every business table already carries `tenant_id NOT NULL` and every repository takes the
tenant as a parameter. Postgres RLS would need a session GUC threaded through a pooled DataSource.

**Decision.** Keep shared-schema tenancy. Add (i) a tenant-isolation test sweep per phase, (ii) `tenant_id`
as the leading column of every composite index, (iii) a `tenant.home_region` column for future routing.
Defer RLS and per-tenant databases.

**Alternatives.** RLS now (defence in depth, real complexity, no behaviour change today); schema-per-tenant
(migration per tenant, connection routing); database-per-tenant (operationally heavy).

**Consequences.** Isolation depends on application discipline; the compensating control is automated tests
that attempt id substitution on every new endpoint. The known defect — globally unique `app_user.email` —
is fixed in P0-3.
**Reversibility.** Medium: moving to RLS is additive; moving to per-tenant databases is a data migration.
**Phase.** P0 (tests, index rule, region column).

---

## ADR-03 — Money is stored and computed as `numeric`, never floating point

**Context.** `MoneyCalculator` already uses `BigDecimal` with HALF_UP rounding and per-line snapshots.

**Decision.** Keep `numeric` columns and `BigDecimal` arithmetic. Extend with `currency.minor_units` so
precision is data-driven (JPY = 0, KWD = 3). Snapshot the FX rate and converted base amount **on the
document at issue time**; never re-convert historical documents for reporting.

**Alternatives.** Store minor units as `bigint` (safer for arithmetic, but a breaking change to every
column and DTO for no current bug); convert dynamically at report time (destroys historical accuracy when
rates move).

**Consequences.** Reporting in base currency reads a snapshot column instead of joining `exchange_rate`.
A tenant whose rate changes sees stable historical reports, which is the intended behaviour.
**Reversibility.** Low for storage type (migration), high for FX snapshot (drop columns).
**Phase.** P1a.

---

## ADR-04 — Tax is configuration data with a deterministic calculator, not a rules engine

**Context.** Today `service_item.tax_rate` is a single percentage and `MoneyCalculator` pro-rates a header
discount across lines. Inclusive tax and split components (CGST/SGST) are not expressible.

**Decision.** `tax_definition` + `tax_component` + per-tenant/location `tax_profile` are **rows**. The
calculator applies: mode (inclusive/exclusive), component split, rounding strategy (line vs document), and
snapshots components per line into `line_tax_component`. No scripting engine, no expression language, no
per-country code branches. Adding a jurisdiction = insert definitions + a profile; if a jurisdiction needs
arithmetic the model cannot express, that is a design review, not a plugin.

**Alternatives.** (a) Code-per-country strategy classes — fast initially, untestable combinatorially, and
adds a deployment per tax change. (b) A rules DSL (Drools/SpEL) — expressive, but business users cannot
safely edit it and it becomes an unauditable black box on money paths.

**Consequences.** All tax behaviour is covered by a matrix test (mode × components × discount × zero-rate).
Legal invoice *wording* stays out of scope (see security doc §8).
**Reversibility.** Medium.
**Phase.** P1a.

---

## ADR-05 — Costing is a first-class domain, not a BI concern

**Context.** There is no cost data anywhere. Profitability cannot be computed. A BI-first approach would
produce a "profit" metric with a missing input.

**Decision.** `work_log` and `expense` are the sources of truth; `job_cost` is a recomputable roll-up with
provenance (`source`, `source_id`). Metrics declare required inputs and are **unavailable** when inputs are
missing — the UI says "cost not recorded", never "100% margin".

**Alternatives.** (a) Estimate labour cost from scheduled duration × a default rate — plausible numbers that
are simply wrong, and worse than absent numbers for an owner making pricing decisions. (b) Ask users to
enter a job cost manually only — less data entry, no per-type analysis, no productivity metrics.

**Consequences.** Adds data-entry surface (time and expenses), which is the real adoption cost of
profitability. Mitigation: time capture integrated with the job status flow rather than a separate screen.
**Reversibility.** High (cost rows can be ignored; metrics gate themselves).
**Phase.** P1b.

---

## ADR-06 — Analytics: aggregate tables in the same database, no warehouse

**Context.** `ReportService` aggregates transactionally. At MVP volumes that is fast; over years it will
not be.

**Decision.** Maintain delta-updated rollup tables (`metric_daily`, `customer_metric`, `job_fact`,
`invoice_aging_snapshot`) in the same PostgreSQL database, refreshed by a Cloud Run Job. Treat them as
**disposable projections**: rebuildable by truncate + recompute, never the source of truth. Revisit
BigQuery only past ~50M transaction rows per tenant.

**Alternatives.** (a) Materialized views with `REFRESH CONCURRENTLY` — one-shot full recompute, chained
dependencies, awkward correction of a single late payment. (b) A separate warehouse now — a second system
to secure, pay for, and keep consistent, for a dataset that fits comfortably in one instance. (c) Query
transactions live on every dashboard load — simplest, degrades predictably with growth.

**Consequences.** Dashboard latency is independent of transaction volume. Correctness depends on the
refresh job, so an *aggregate-vs-live equality test* is mandatory (roadmap §11).
**Reversibility.** High.
**Phase.** P2a.

---

## ADR-07 — AI never generates SQL; it produces a validated intent against a semantic layer

**Context.** Natural-language reporting is the headline AI feature and the highest-risk one. Text-to-SQL
against a production multi-tenant database is unacceptable: one prompt-injection success or one hallucinated
join is a cross-tenant data breach.

**Decision.** Three-step pipeline: (1) the model returns a **structured intent** (metric codes, filters,
dimensions, period) validated against a server-side whitelist; (2) a deterministic service compiles the
intent into tenant-scoped JPQL/Criteria and computes facts; (3) the model narrates *only* those facts, and
the UI renders system numbers distinctly from AI prose. `tenant_id` is injected from the principal and is
not an addressable filter.

**Alternatives.** (a) Text-to-SQL with a read-only role — RLS-less shared schema means a bad join leaks
across tenants; also unauditable. (b) Pure RAG over documents — cannot answer numeric questions reliably.
(c) No AI — loses the differentiator, but remains the fallback if governance fails.

**Consequences.** Answer quality is bounded by the metric catalogue, which is a feature (consistent with
dashboards) rather than a limitation. Adding a metric is a code change with tests, not a prompt tweak.
**Reversibility.** High (intent layer has standalone value as a query API).
**Phase.** P2b.

---

## ADR-08 — Model provider behind an interface; Gemini first, no data for training

**Context.** Provider choice affects cost, latency, data terms and regional availability. Committing the
codebase to one SDK is the mistake to avoid.

**Decision.** A narrow `LlmProvider` port (generate, embed if needed, token accounting, error
classification) with a Gemini adapter for P2b and a deterministic fake adapter for all tests. Model names,
timeouts and prices are configuration. Provider selection is per deployment, not per request, to keep
auditing simple.

**Alternatives.** Provider SDK calls inline (fastest to write, hardest to change or test); multi-provider
routing at request time (needs cross-provider eval to be meaningful — deferred to P3).

**Consequences.** Provider swap is a config + adapter change. Prompt/response shapes must stay
provider-neutral, which slightly constrains use of vendor-specific features.
**Reversibility.** High.
**Phase.** P2b.

---

## ADR-09 — Automation is approval-gated, idempotent and auditable by construction

**Context.** Reminders and follow-ups send outbound messages. The realistic failure modes are duplicates and
unauthorised sends, not clever attacks.

**Decision.** Rules are declarative rows; every execution is an `automation_run` with a **unique
`idempotency_key` = (rule, subject, period)**, a status machine (`PENDING_APPROVAL → APPROVED → EXECUTED`
or `FAILED`/`SKIPPED`), and an audit entry. Outbound-by-default actions require approval (owner/admin);
tenant-wide rules can be disabled instantly.

**Alternatives.** Fire-and-forget scheduled tasks (duplicate sends on retry, no audit trail); letting the
LLM decide when to send (unacceptable).

**Consequences.** Slightly slower to launch a rule; safe under at-least-once scheduling and worker retries.
**Reversibility.** High.
**Phase.** P3.

---

## ADR-10 — Background work as scheduled Cloud Run Jobs, not in-request work

**Context.** Analytics refresh, retention cleanup and automation scanning must not run in a request or on
application startup (Cloud Run scales instances in and out, and startup work would repeat per instance).

**Decision.** A single job entrypoint in the same codebase, invoked by Cloud Scheduler → Cloud Run Job, with
advisory locks (`pg_try_advisory_lock`) so overlapping runs are harmless. No in-process cron.

**Alternatives.** `@Scheduled` in the web app (runs once per instance — duplicates at scale); a queue service
(premature at this volume).

**Consequences.** Cost is near-zero at low frequency; job runs are visible in Cloud Logging with their own
execution id.
**Reversibility.** High.
**Phase.** P2a (analytics), extended in P2b/P3.

---

## ADR-11 — API evolution: additive, versioned paths, no breaking changes to the MVP contract

**Context.** The MVP frontend and API are a verified pair. Every phase touches documents that already have
clients.

**Decision.** Keep `/api/v1`. Add fields, never remove or repurpose them; new capabilities get new
sub-resources (`/api/v1/bi/...`, `/api/v1/ai/...`). Deprecations are documented with a removal release at
least one phase later. DTOs remain explicit records rather than exposing entities.

**Alternatives.** A v2 namespace per phase (client churn, duplicated controllers); breaking change with a
coordinated deploy (acceptable only pre-customer).

**Consequences.** Some transitional duplication (`service_item.tax_rate` alongside `tax_profile_id`) is
intentional and documented as deprecated.
**Reversibility.** n/a (process decision).
**Phase.** All.

---

## ADR-12 — Industry independence through configuration and seed templates, not per-industry forks

**Context.** The product contains cleaning-specific wording, seeds and defaults. The stated goal is one
platform for many service industries.

**Decision.** One codebase with an `industry_template` concept: a named bundle of default service
categories, item units, terminology overrides, and onboarding copy, stored as data and applied at tenant
creation. No `if (industry == ...)` branches in business logic. Anything that cannot be expressed as
configuration must be justified as a core capability (e.g. `unit_of_measure` rows, not a new module).

**Alternatives.** Per-industry builds (N codebases to maintain); a plugin system (premature for a product
whose domains are the same across industries).

**Consequences.** A new industry is a data exercise, reviewable by a non-engineer. Risk: templates drift from
the code's actual capabilities, so a template validation test is required.
**Reversibility.** High.
**Phase.** P0.

---

## ADR-13 — Internationalization: locale data over hardcoded formats

**Context.** `format.ts` already uses `Intl` with UTC handling for date-only values, but the tenant has a
single currency and timezone, and the API sends raw decimals.

**Decision.** Store amounts as decimals; format exclusively in the client via `Intl` using the tenant/user
locale and the document's own currency. Keep date-only values timezone-free (existing convention). Currency
precision comes from `currency.minor_units`. Translations use a message catalogue; no user-visible string is
composed by concatenation.

**Alternatives.** Server-side pre-formatted strings (breaks caching, harder to test); a formatting library
added on top of `Intl` (redundant for the formats required).

**Consequences.** PDFs (server-rendered) need a mirrored formatter with tests asserting parity with the UI.
**Reversibility.** High.
**Phase.** P1a (P0 scaffolding).

---

## ADR-14 — Defer the subscription/billing engine; meter AI usage first

**Context.** AI quotas imply a plan/entitlement model, but there is no payment collection for Operonix's own
subscription today.

**Decision.** Meter usage (`ai_usage_daily`) and enforce a single configurable cap per tenant now. Introduce
`plan`/`subscription` tables only when Operonix actually charges by plan. Entitlement checks go behind a
`EntitlementService` interface whose first implementation reads configuration.

**Alternatives.** Build a full subscription engine now (weeks of work for a pricing model that is not
decided); no metering (unbounded cost).

**Consequences.** No invoice-to-subscription linkage until a real billing requirement exists. The interface
keeps the call sites stable.
**Reversibility.** High.
**Phase.** P2b (metering), P3 (plans).

---

## Approval checklist

| ADR | Decision | Needs owner? | Status |
| --- | --- | --- | --- |
| ADR-01 | Modular monolith | No | Proposed |
| ADR-02 | Shared-schema tenancy, no RLS | Yes (residency contracts) | Proposed |
| ADR-03 | `numeric` money + FX snapshot | No | Proposed |
| ADR-04 | Data-driven tax, no rules engine | Yes (market compliance) | Proposed |
| ADR-05 | Costing before profitability | Yes (product scope) | Proposed |
| ADR-06 | Postgres aggregates, no warehouse | No | Proposed |
| ADR-07 | Validated intent, no text-to-SQL | No | Proposed |
| ADR-08 | Gemini behind an abstraction | Yes (provider terms) | Proposed |
| ADR-09 | Approval-gated automation | Yes (product behaviour) | Proposed |
| ADR-10 | Cloud Run Jobs for background work | No | Proposed |
| ADR-11 | Additive API evolution | No | Proposed |
| ADR-12 | Industry templates | Yes (go-to-market) | Proposed |
| ADR-13 | Locale formatting in the client | No | Proposed |
| ADR-14 | Metering before subscriptions | Yes (pricing) | Proposed |

**Open decisions that block implementation:** D1–D8 in `IMPLEMENTATION_ROADMAP.md` §8. In particular, P1a
cannot start without D1 (market order) and D2 (inclusive pricing default), because both change the tax
calculator's contract.