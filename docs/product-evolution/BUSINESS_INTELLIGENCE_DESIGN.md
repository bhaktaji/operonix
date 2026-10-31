# Operonix — Business Intelligence Design

| Field | Value |
| --- | --- |
| Version | 1.0 (proposal — not approved) |
| Date | 2026-10-31 |
| Baseline | `reporting` module: `ReportController` (3 endpoints), `ReportService` (live queries), `ReportDtos` |
| Goal | Turn recorded transactions into **defined, reconcilable metrics** — not a second opinion about them |

---

## 1. Current reporting baseline (verified)

| Endpoint | What it returns today | Implementation detail |
| --- | --- | --- |
| `GET /api/v1/reports/dashboard` | collected this month vs last, delta %, outstanding, overdue, jobs today/this week, pending quotes, quote acceptance %, active customers, 14-day revenue series, top 5 services (30 d), upcoming jobs | `ReportService.dashboard`; technicians get counters only |
| `GET /api/v1/reports/revenue?from&to&groupBy=day\|month\|service` | bucketed **collected** revenue (payments), total | `payments.sumBetween`, `payments.findAllBetween` (loads entities), `invoices.revenueByService` |
| `GET /api/v1/reports/technicians?from&to` | completed jobs + **scheduled** minutes per technician | `jobs.findAllInRange` + assignments |

Gaps that the BI module must close (all verified, not assumed):

1. **Only "collected" revenue exists.** Invoiced value, recognized revenue and outstanding-at-date history are absent.
2. **Buckets are computed in Java** from entity lists (`revenueByDay`, `monthBuckets`) — memory grows with volume.
3. **No history.** Change a payment today and yesterday's chart changes; there is no snapshot of "what we reported then".
4. **Productivity uses scheduled minutes**, not actual work time (the schema has no actual start/finish).
5. **No cost data**, so no profit metric can exist yet (`job` has no cost fields; no expense entity).
6. **No per-customer/per-location/per-business-category breakdowns** except top services.
7. Labels are English-formatted server-side (`Locale.ENGLISH`).
8. No export, no comparisons across arbitrary periods, no aging buckets.

## 2. Revenue concepts (the distinction the owner must be able to trust)

| Concept | Definition | Source | When it moves |
| --- | --- | --- | --- |
| **Booked revenue** (pipeline value) | Sum of accepted quote totals in the period | `quote` where status ∈ {ACCEPTED, CONVERTED}, by `issue_date` or acceptance date | When a quote is accepted |
| **Invoiced revenue** | Sum of invoice totals (excluding VOID) by `issue_date` | `invoice` | When an invoice is issued |
| **Collected revenue** | Sum of payment amounts by `paid_at`, minus reversals | `payment` (payments are rows; reversal is a status/negative entry — see note) | When money is received |
| **Outstanding receivables** | `Σ(total − amount_paid)` for open invoices (SENT, PARTIALLY_PAID) as of a date | `invoice` | When invoices are issued or paid |
| **Recognized revenue** (optional, P3) | Revenue attributed to the period in which work was completed | `job.completed_at` × linked invoice lines | Only if accounting-grade recognition is requested |

> **Note on reversals.** In v1 a payment reversal is recorded as a status change on the payment (see
> `PAYMENT_REVERSED` audit action and `SalesFlowIT`). Before BI ships, confirm whether reversed
> payments are excluded by status or stored as negative amounts; the metric definition must match
> exactly one of those, and a reconciliation test must prove the two paths agree.

**Rule:** three different revenue numbers are legitimate. The BI layer must label them explicitly and
never show "Revenue" without saying which one it is.

## 3. Metrics catalogue (definitions are the contract)

Grain: `tenant × period × dimension`. All money in tenant base currency (see §7).

### A. Revenue analytics

| Metric | Formula | Source |
| --- | --- | --- |
| `revenue.collected` | `Σ payment.amount` in period | `payment` |
| `revenue.invoiced` | `Σ invoice.total` where status ≠ VOID | `invoice` |
| `revenue.booked` | `Σ quote.total` where status ∈ {ACCEPTED, CONVERTED} | `quote` |
| `revenue.by_customer` | collected/invoiced grouped by `customer_id` | `invoice`, `payment` |
| `revenue.by_service` | `Σ invoice_line.line_subtotal` grouped by `service_item_id` (label snapshot when the item is deleted) | `invoice_line` |
| `revenue.by_location` | grouped by `job.location_id` (via invoice → job), else customer's primary location | `job`, `invoice` |
| `revenue.by_business_category` | grouped by `service_item.category_id` (new, optional taxonomy) | `service_item` |
| `revenue.growth_pct` | `(current − previous) / previous × 100`, `null` when previous = 0 | derived |
| `revenue.mom`, `revenue.yoy` | same formula with month/year offsets, aligned to fiscal periods | derived |
| `aov` | `invoiced revenue / distinct invoiced customers` | derived |
| `invoice.avg_value` | `invoiced revenue / invoices issued` | derived |

### B. Employee productivity (data availability is the constraint)

| Metric | Formula | Available today? |
| --- | --- | --- |
| `jobs.assigned` | count of job assignments in period | ✅ `job_assignment` |
| `jobs.completed` | count where `job.status = COMPLETED` and `completed_at` in period | ✅ |
| `jobs.completion_rate` | `completed / (completed + cancelled + scheduled-past)` | ✅ (needs "past" definition) |
| `workload.scheduled_hours` | `Σ (scheduled_end − scheduled_start)` per assignee | ✅ |
| `workload.actual_hours` | `Σ time_entry.minutes` | ❌ needs `job_time_entry` (P2) |
| `jobs.on_time_rate` | completed_at ≤ scheduled_end | ✅ (approximate: completion time is a status stamp, not a work log) |
| `efficiency.actual_vs_scheduled` | `actual_hours / scheduled_hours` | ❌ P2 |
| `labour.cost` | `Σ actual_hours × user.hourly_cost` | ❌ P2 (`app_user.hourly_cost`) |

**Guardrail:** until `job_time_entry` exists, the API must not expose "efficiency" or "hours worked".
Scheduled duration may be labelled `scheduled_hours` only, and the UI must say so. Reporting a
misleading productivity number is worse than reporting none.
### C. Customer intelligence

| Metric | Formula | Notes |
| --- | --- | --- |
| `customers.new` | first invoice/quote date in period | "new" = first revenue event, not record creation |
| `customers.recurring` | invoiced in period AND earlier | — |
| `repeat_rate` | `customers.seen_twice_or_more / customers.ever` in trailing 12 months | — |
| `retention_rate` | `(customers active in period ∩ active in previous period) / active in previous` | cohort definition must be fixed and documented |
| `clv` (simple) | `avg invoice value × purchases per year × expected years (configurable, default 2)` | Explicitly a **simple** model; labelled as such |
| `clv` (marginal, P3) | adds gross margin per job | Requires cost data |
| `customers.inactive` | no invoice/job in `N` days (default 90, configurable) | Drives §4D automation |
| `customers.at_risk` | active before, nothing in 60–90 days | — |
| `customer.history` | timeline of quotes, jobs, invoices, payments | Existing data, new read model |

### D. Outstanding payments

| Metric | Formula |
| --- | --- |
| `ar.total` | `Σ (total − amount_paid)` for SENT/PARTIALLY_PAID |
| `ar.overdue` | same where `due_date < today` |
| `ar.aging` | buckets `0–30 / 31–60 / 61–90 / 90+` by `days_past_due` |
| `ar.by_customer` | grouped by `customer_id`, with oldest due date |
| `dso` | `(ar.total / invoiced revenue in period) × days in period` (standard approximation; label it) |
| `collection.avg_days` | `avg(paid_at − issue_date)` over invoices fully paid in period |
| `partial.count` | invoices where `0 < amount_paid < total` |
| `payment.trend` | collected per week/month; payment-method mix |

### E. Job profitability (requires new data)

| Metric | Formula | Needs |
| --- | --- | --- |
| `job.revenue` | linked invoice `Σ line_subtotal` (net of discount) | ✅ |
| `job.labour_cost` | `Σ time_entry.minutes/60 × cost_rate` (snapshot per entry) | `job_time_entry`, `app_user.hourly_cost` |
| `job.material_cost` | `Σ job_cost.amount` where category = MATERIAL | `job_cost`, `cost_category` |
| `job.equipment_cost`, `job.subcontract_cost`, `job.other_cost` | as above by category | same |
| `job.gross_profit` | revenue − (labour + material + equipment + subcontract + other) | all of the above |
| `job.margin_pct` | `gross_profit / revenue × 100` | — |
| `profit.by_customer/service/location` | aggregate of the above | — |

**Definitions to lock before build:** cost is captured *at cost* (no markup), labour rates are
snapshotted per time entry (so later pay changes don't rewrite history), and revenue is net of tax
(tax is not income). Jobs without cost data must show `profit: null`, never `0` or a negative guess.

## 4. API contracts

All endpoints are tenant-scoped by JWT, office roles only (`OWNER, ADMIN, MANAGER`), RFC 7807 errors,
and every response states its `currency`, `period`, `grain` and `generatedAt`.

```
GET /api/v1/insights/overview?from=2026-10-01&to=2026-10-31
→ 200 {
    "period": {"from":"2026-10-01","to":"2026-10-31","timezone":"Asia/Kolkata","fiscalYear":"2026-27"},
    "currency": {"code":"INR","scale":2,"base":"INR"},
    "revenue": {"collected":"412500.00","invoiced":"455000.00","booked":"520000.00",
                "outstanding":"182000.00","overdue":"42000.00","growthPct":"12.4"},
    "jobs": {"scheduled":128,"completed":119,"cancelled":4,"completionRate":"96.0"},
    "customers": {"active":86,"new":9,"atRisk":6,"repeatRate":"61.5"},
    "generatedAt":"2026-10-31T09:12:44Z","freshness":"2026-10-31T08:00:00Z"
  }

GET /api/v1/insights/revenue?from&to&groupBy=day|week|month|service|customer|location|category&currency=base
→ 200 {"grain":"month","series":[{"key":"2026-09","label":"2026-09","collected":"...","invoiced":"...","booked":"..."}],"total":"..."}

GET /api/v1/insights/receivables?asOf=2026-10-31&bucketDays=30
→ 200 {"total":"...","overdue":"...","aging":[{"bucket":"0-30","amount":"...","count":12}, ...],
        "customers":[{"customerId":"...","name":"Acme","outstanding":"...","oldestDueDate":"2026-08-02"}]}

GET /api/v1/insights/productivity?from&to
→ 200 [{"userId":"...","name":"Ravi","assigned":42,"completed":40,"scheduledHours":"168.0",
        "actualHours":null,"onTimeRate":"87.5","dataQuality":"scheduled-only"}]

GET /api/v1/insights/profitability?from&to&groupBy=job|customer|service|location
→ 200 {"costComplete":false,"jobs":[{"jobId":"...","revenue":"...","cost":"...","grossProfit":null,"marginPct":null}]}

GET /api/v1/insights/export?report=revenue&format=csv&from&to
→ 200 text/csv (tenant-scoped, audited as EXPORT_CREATED)
```

Rules:

* Unknown/unauthorized `groupBy` → `400 VALIDATION_FAILED` (same convention as `reports/revenue`).
* Range limits (e.g. day grain ≤ 400 days) mirror the existing validation style.
* Every metric response includes `freshness`; the UI shows "as of hh:mm" rather than implying live data.
* `null` means *not measurable* (documented), never zero.

## 5. User experience (concrete)

| Surface | Content |
| --- | --- |
| **Owner home (replaces dashboard)** | 4 KPI tiles (collected, outstanding, jobs completed, new customers) each with a sparkline; "as of" timestamp; 3 alerts (overdue AR, invoices to send, quotes expiring) |
| **Insights → Revenue** | Period selector (this month / last month / quarter / fiscal year / custom), grain switch, chart + table, breakdown tabs (service, customer, location), export button |
| **Insights → Customers** | New vs recurring stacked bars, retention line, table of inactive customers with "Create follow-up" action (AI-assisted draft, P2) |
| **Insights → Receivables** | Aging bars, top debtors table, average collection days, link to filtered invoice list |
| **Insights → Team** | Assigned vs completed, scheduled hours with an explicit "actual hours not tracked yet" note until time entries ship |
| **Insights → Profit** (P2) | Profit per job/customer/service, coverage indicator ("cost captured for 62% of jobs") |

Mobile: KPIs stack, tables scroll horizontally; charts must render without hover interaction.

## 6. Aggregation architecture

**Decision: derived fact tables in the same PostgreSQL, refreshed by the worker. No warehouse, no
materialized-view-only strategy.**

| Layer | Object | Contents | Refresh |
| --- | --- | --- | --- |
| Transactional | `invoice`, `payment`, `job`, `quote`, `customer` (existing) | source of truth | request-driven |
| Event | `outbox_event` (new) | "invoice paid", "job completed", "payment reversed" | same transaction as the change |
| Derived daily facts | `analytics_daily_revenue`, `analytics_daily_ar`, `analytics_daily_jobs`, `analytics_daily_customers` | one row per `(tenant_id, date, dimension, dimension_key)` with numeric measures | worker, incremental, idempotent upsert |
| Derived monthly rollups | `analytics_monthly_*` (optional at P2) | pre-aggregated for long ranges | worker, recomputed from daily |
| Gauges | `analytics_gauge` | point-in-time values (AR total, overdue) per `(tenant, as_of_date)` | worker |
| Query layer | `analytics` service | reads facts; falls back to transactional computation for "today" only | request |

**Why not materialized views alone:** `REFRESH MATERIALIZED VIEW` is all-or-nothing per view and
blocks or duplicates work across tenants; incremental upserts per tenant/day are cheaper and allow
backfill by tenant. Materialized views can still be added as a P3 optimization for the "all tenants"
reads (e.g. platform benchmarks).

**Why not a warehouse now:** the largest realistic tenant is ~10⁴–10⁵ invoices/year; a daily fact
table of ~365 × dimensions rows per tenant is trivial for PostgreSQL and avoids ETL, another system,
credentials, cost and a consistency gap.

### Refresh flow

```
domain change ──(same tx)──▶ outbox_event(type=invoice.paid, tenant, date)
                                   │
worker (SKIP LOCKED claim) ────────┘
   ├─ recompute affected (tenant, date) bucket from transactional data
   ├─ upsert analytics_daily_*
   ├─ recompute gauge for as_of = today
   └─ mark event done (dedupe_key prevents double apply)
```

* Idempotent: recomputation from source (not increment) means replay is harmless.
* Late changes (payment back-dated, reversal) recompute the impacted date buckets — nothing else.
* Nightly job recomputes yesterday + last 3 days as a safety net, and nightly full-month rebuild for the current month (cheap).
* Backfill CLI: `worker --task=analytics-backfill --tenant=<id> --from=… --to=…`.

## 7. Multi-currency and timezone

| Concern | Rule |
| --- | --- |
| Base currency | `tenant.currency_code`; every fact row stores `currency` and `amount_base` |
| Foreign-currency documents | Convert at the **document's stored FX snapshot**; also retain `amount_original` per currency for detail views |
| Cross-currency totals | Never sum different currencies without conversion; the API rejects mixed-currency aggregation with `400` and requires `currency=base` |
| Day boundaries | Buckets are tenant-local dates (`tenant.timezone`); a payment at 23:30 IST belongs to that IST date |
| Fiscal periods | Quarter/fiscal-year ranges use `tenant.fiscal_year_start_month` |
| DST | `ZoneId` conversions only (never fixed offsets) |

## 8. Performance and indexing

| Requirement | Approach |
| --- | --- |
| Dashboard < 200 ms p95 | Read gauges + last 13 daily facts (≤ 20 rows) |
| Range report < 500 ms p95 for 3 years | Monthly rollups; index `(tenant_id, dimension, date)` |
| No N+1 | Fact reads are single queries per dimension |
| Index additions | `invoice(tenant_id, issue_date)`, `payment(tenant_id, paid_at, invoice_id)`, `job(tenant_id, completed_at)`, `analytics_daily_*(tenant_id, date)` unique |
| Growth control | Daily facts retained 36 months; older data served from monthly rollups (P2) |
| Memory | No entity lists loaded for reporting; JDBC aggregate queries only |

## 9. Tenant isolation and access control

| Rule | Implementation |
| --- | --- |
| Every fact row carries `tenant_id`, PK includes it | Unique `(tenant_id, date, dimension, dimension_key)` |
| Queries always filter by tenant from JWT | `AnalyticsRepository` methods take `tenantId` as the first parameter; no `findAll` |
| Role rules | `insights/*` office roles only; `productivity` also office roles; technicians keep their operational view |
| Exports | Audited (`EXPORT_CREATED` with row count); same scoping as queries |
| Cross-tenant leakage test | Integration test: tenant B cannot read any fact row of tenant A (404/empty, never partial) |

## 10. Testing requirements

| Test | Purpose |
| --- | --- |
| Metric golden tests (fixture dataset → expected numbers) | Proves definitions match the catalogue |
| Reconciliation: `Σ daily facts == transactional Σ` for a seeded month | Catches drift |
| Reversal/back-dating recomputation | Idempotency + correctness |
| Currency conversion snapshot | Historical totals unchanged after rate updates |
| Tenant isolation on every endpoint | No leakage |
| Performance smoke (10⁵ synthetic rows) | Guards the aggregation design |
| Timezone boundary (23:30 payment) | Correct day attribution |

## 11. Phasing

| Phase | BI scope |
| --- | --- |
| **P1** | Fact tables + worker + overview/revenue/receivables APIs; owner home; CSV export; existing `/reports/*` kept for compatibility |
| **P2** | Cost capture (`job_cost`, time entries), productivity with actual hours, profitability APIs, customer intelligence (retention/CLV), aging exports |
| **P3** | Recognized revenue, fiscal-period reports, monthly rollups, benchmark views, materialized views if measured need |
