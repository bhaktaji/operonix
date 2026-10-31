# Operonix — Product Evolution Analysis

**Status: complete and awaiting approval. No application code, migration, dependency or cloud resource has
been changed.** The only changes in this folder are Markdown documents; `memory.md` was updated to record
this milestone.

This folder contains the pre-implementation analysis for evolving Operonix from a cleaning-focused MVP into
a global, industry-independent business operating platform with Business Intelligence and Operonix AI.

---

## Reading order

| # | Document | What it answers |
| --- | --- | --- |
| 1 | [GLOBAL_PRODUCT_VISION.md](GLOBAL_PRODUCT_VISION.md) | Who we serve, what changes in positioning, and the cleaning-specific assumptions actually found in the code |
| 2 | [ARCHITECTURE_IMPACT_ANALYSIS.md](ARCHITECTURE_IMPACT_ANALYSIS.md) | Current vs proposed architecture, impact matrix by area, scalability, what stays |
| 3 | [DATA_MODEL_IMPACT_ANALYSIS.md](DATA_MODEL_IMPACT_ANALYSIS.md) | Every schema change, the proposed ER diagram, isolation rules, indexing, migration plan, and what is deliberately *not* built |
| 4 | [GLOBALIZATION_AND_LOCALIZATION.md](GLOBALIZATION_AND_LOCALIZATION.md) | Currency, tax, numbering, entities, addresses, locales, residency, universal vs country-specific |
| 5 | [BUSINESS_INTELLIGENCE_DESIGN.md](BUSINESS_INTELLIGENCE_DESIGN.md) | Metric definitions, formulas, API contracts, dashboards, aggregation architecture |
| 6 | [OPERONIX_AI_DESIGN.md](OPERONIX_AI_DESIGN.md) | Semantic query layer, model abstraction, quote assistance, insights, automation, quotas, security |
| 7 | [SECURITY_AND_COMPLIANCE_IMPACT.md](SECURITY_AND_COMPLIANCE_IMPACT.md) | Current posture, AI threat model, authorization matrix, residency levels, jurisdiction matrix, hardening backlog |
| 8 | [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) | Phases P0–P3 with effort, dependencies, exit gates, risks, costs, and the decisions needed from the owner |
| 9 | [ARCHITECTURAL_DECISIONS.md](ARCHITECTURAL_DECISIONS.md) | Fourteen proposed ADRs with alternatives and trade-offs |

Existing baseline documents remain authoritative for the current system and were **not** modified:
[HLD.md](../HLD.md), [LLD.md](../LLD.md), [ERD.md](../ERD.md), [GOOGLE_CLOUD_DEPLOYMENT.md](../GOOGLE_CLOUD_DEPLOYMENT.md).

---

## Final recommendations

### 1. Recommended future architecture

A **modular monolith** (unchanged shape) with explicit module boundaries enforced by ArchUnit, one
PostgreSQL database, one React SPA, and background work as scheduled Cloud Run Jobs sharing the same
codebase. New domains added as packages: `locale` (reference data), `tax`, `costing`, `analytics`, `ai`,
`automation`. No microservices, no warehouse, no separate AI service at this stage — each would be a second
system to secure and operate for a workload that fits in one instance.

### 2. Recommended global multi-tenant strategy

Shared schema with mandatory `tenant_id` (as built), plus `tenant.home_region` for future routing,
tenant-scoped uniqueness for users, and configuration-driven localization. Residency starts at L1
(addressable, single active region) and moves to L2 (per-region deployments) only when a market requires
it contractually. Tenant configuration lives in `tenant_settings` + `legal_entity` + `business_location`,
never in code branches.

### 3. Recommended database evolution

Nine additive Flyway migrations (V2–V9) with no table dropped and no existing column removed in the same
release. Highlights: reference tables (`country`, `currency`, `tax_definition`, `tax_component`), tenant
configuration (`legal_entity`, `business_location`, `tenant_settings`, `document_number_rule`), FX
snapshots on documents, `line_tax_component`, costing (`work_log`, `expense`, `vendor`, `job_cost`),
`payment_reversal`, and disposable analytics projections. Deprecated columns (e.g. `service_item.tax_rate`)
survive at least one release.

### 4. Recommended BI architecture

A **metric catalogue as code** (definitions, formulas, required inputs, availability rules) over
delta-maintained Postgres rollups (`metric_daily`, `customer_metric`, `job_fact`,
`invoice_aging_snapshot`) refreshed by a Cloud Run Job, with live queries for drill-down. Metrics declare
their inputs and report *unavailable* rather than zero when inputs are missing — the mechanism that stops
"profit" from appearing before cost data exists.

### 5. Recommended Operonix AI architecture

Natural language → **validated structured intent** → deterministic tenant-scoped query → facts → model
narration. No text-to-SQL anywhere. Provider behind an `LlmProvider` port (Gemini first, fake adapter in
tests). Quotas enforced before the provider call, usage metered per tenant per day, every resolved query
audited, AI never writes prices or sends documents.

### 6. Recommended integration strategy

Integrate through narrow ports, activated per market: `PaymentGateway` (manual provider first), `LlmProvider`,
`EmailSender` (P3), `ExchangeRateSource` (daily import). Nothing country-specific enters core business logic;
market differences are rows in reference tables plus a profile. Gateways hold card data, never Operonix.

### 7. Google Cloud deployment architecture

Unchanged from the deployment-readiness work: one Cloud Run service serving the SPA and the API from the
same origin, Cloud SQL PostgreSQL 16 via the Cloud SQL Java Connector, Artifact Registry, Cloud Build,
Secret Manager, Cloud Logging/Monitoring, Cloud Storage for future documents, region configurable
(`asia-south1` initially). Additions proposed here: Cloud Scheduler + Cloud Run Job for analytics refresh,
retention and automation scanning; Memorystore (or a Postgres counter) for shared rate limiting and AI
quotas; budget alerts. Nothing has been created or deployed.

### 8. Cost considerations

Order-of-magnitude figures and the alerting plan are in
[IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) §14. The structural point: everything except model
tokens is a *fixed* monthly cost in the tens of dollars; AI is the only variable cost that scales with
usage, which is why the per-tenant daily cap is a mandatory item, not a nice-to-have.

### 9. Product development phases

`P0` foundations (industry neutrality, tenancy and auth hardening) → `P1a` globalization and financial
correctness → `P1b` costing → `P2a` BI → `P2b` AI → `P3` automation and depth. Roughly 43 engineer-weeks to
the end of P2b at recommended scope; the mandatory-only path is ~31 weeks. Full detail, exit gates and
critical path in the roadmap.

### 10. Technical debt and migration risks

Highest: the money/tax refactor (R1) — mitigated by characterization tests written *before* the calculator
changes, and a data-equality test on a seeded MVP database. Second: profitability shipping over incomplete
cost data (R3) — mitigated by availability rules and an explicit acceptance gate. Third: AI cost and trust
(R4, R5) — mitigated by pre-call quotas, server-computed numbers, and labelling. Existing debt recorded:
in-process rate limiting, global unique email, no refresh-token reuse detection, tax rate stored per item.

### 11. Keep as-is vs evolve

Detailed comparison in [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) §13. Summary: evolving is
additive (9 migrations, no table dropped, existing APIs preserved, existing tests must keep passing).
A rewrite is only justified if the tenancy model itself must change for contractual data separation.
### 12. Decisions requiring explicit approval

Full table with consequences in [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) §8. Condensed:

| # | Decision | Blocks |
| --- | --- | --- |
| D1 | Launch market order | P1a tax definitions and gateways |
| D2 | Inclusive vs exclusive pricing default | P1a calculator contract |
| D3 | Is job profitability required in the first BI release | P1b ordering |
| D4 | Who may see cost/margin data | Cost-visibility scope |
| D5 | Is AI a paid add-on with quota tiers | Metering vs plans |
| D6 | Accept Gemini as initial provider and its data terms | P2b |
| D7 | Physical residency required for market #1 | L1 vs L2 |
| D8 | MFA before first external customer | Security ordering |

---

## What was verified, and what was not

**Verified by reading the repository:** current schema (15 tables, all constraints quoted in
`DATA_MODEL_IMPACT_ANALYSIS.md` §2), tax arithmetic in `MoneyCalculator`, hardcoded document numbering,
tenant-scoped repository signatures, `@PreAuthorize` roles, CSRF/CSP/HSTS configuration, in-process login
limiter, refresh-token storage design, absence of any cost, analytics, AI or automation code, and the
existing GCP deployment artefacts.

**Estimated, not verified:** effort in engineer-days, calendar durations, cost figures, and the row-count
thresholds at which indexing or a warehouse becomes necessary.

**Cannot be verified from the repository:** which markets launch first, whether inclusive pricing is
required by the first customers, whether any tenant needs multi-entity consolidation, and the legality of
any specific cross-border transfer. These are business and legal questions, listed as D1–D8.

**Explicitly not claimed:** that any jurisdiction's compliance is satisfied. Architecture provides the
controls; certification, DPAs, tax registration and e-invoicing mandates are outside this document set.

---

## Approval statement

Implementation is **not** authorised by this analysis. On approval, work starts with P0-1…P0-3 (industry
neutrality, seed/template profiles, tenant-scoped email) and stops again before P1a until D1 and D2 are
answered.
