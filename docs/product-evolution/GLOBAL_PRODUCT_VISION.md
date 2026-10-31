# Operonix — Global Product Vision

| Field | Value |
| --- | --- |
| Version | 1.0 (proposal — not approved) |
| Date | 2026-10-31 |
| Status | Analysis and design only. No code, schema, dependency or cloud resource was changed. |
| Inputs | `memory.md`, `docs/HLD.md`, `docs/LLD.md`, `docs/ERD.md`, `README.md`, `V1__schema.sql`, backend sources, frontend sources, deployment docs |
| Approvals required | Before any implementation: §9 decisions D1–D12 in [ARCHITECTURAL_DECISIONS.md](ARCHITECTURAL_DECISIONS.md) |

---

## 1. Repositioning

**From:** "quoting, scheduling and invoicing for cleaning companies."
**To:** a **global business operating platform for small and growing service businesses** — one
configurable product where industry, country, currency, tax model, document numbering and terminology
are *configuration*, not a fork.

The engineering consequence is concrete: the v1 codebase already models the generic backbone
(customer → priced catalogue → quote → scheduled job with crew → invoice → payment → audit).
What it does not yet model is **everything that makes a business country-specific and
industry-specific**: legal entity, tax composition, currency precision, document numbering rules,
terminology, business type, and cost capture.

```
Operonix core (one codebase)
 ├─ Platform configuration   business type, terminology, numbering, modules        [NEW]
 ├─ Financial engine         currency precision, tax components, inclusive/exclusive [NEW]
 ├─ Operations               customers, catalogue, quotes, jobs, invoices, payments [EXISTS]
 ├─ Insight                  BI aggregates, gauges, exports                         [NEW]
 └─ Intelligence             Operonix AI: ask, assist, advise, automate             [NEW]
```

## 2. Target segments and personas

| Segment (examples, not exhaustive) | Core loop | What varies from v1 assumptions |
| --- | --- | --- |
| Cleaning companies | recurring visits, crew, sites | baseline: already supported |
| Pest control | scheduled treatments, recurring cycles, compliance records | service intervals, chemical/consumable cost, legal certificates |
| Equipment rental | availability windows, per-day pricing, deposits, damage | pricing by period, item-level inventory, deposit/hold, return inspection |
| Auto repair / maintenance | vehicle intake → estimate → work order → parts/labour → invoice | parts vs labour lines, vehicle entity, estimate approval, supplier costs |
| Plumbing / electrical | emergency dispatch, quote → visit → materials → invoice | on-call scheduling, materials costs, per-visit labour rates |
| Property maintenance | multi-site contracts, recurring visits, site assets | contract-level billing, per-site cost |
| Small construction contractors | project phases, progress billing, subcontractors, retention | progress invoices, subcontractor costs, retention/withholding |
| Local logistics operators | consignments, routes, per-shipment pricing, fuel cost | shipment entity, distance/weight pricing, per-trip cost |
| Independent training centres | cohorts, seats, per-student pricing, attendance | capacity/seat pricing, attendance, no technician crew |

**Personas**

| Persona | Today (v1) | Needs the platform must serve |
| --- | --- | --- |
| **Owner-operator** (1–15 staff) | Owner/Admin/Manager/Technician roles exist; dashboard shows money and ops | "What is my revenue, who owes me, what did I actually earn?" — plain-language answers, mobile-friendly |
| **Office manager / dispatcher** | Schedules crews, builds quotes, records payments | Faster quoting, conflict-free scheduling, reminders, overdue follow-up |
| **Field worker** (technician/engineer/driver/instructor) | TECHNIQUE role, operational view only | Today's jobs, customer/location context, complete + note actual time and materials |
| **Accountant / bookkeeper** (external, occasional) | Not modelled | Export-quality financial data, tax breakdown, numbering that matches statutory rules |
| **Platform operator** (us) | Not modelled | Onboard tenants by country/business type, manage plans, quotas, AI usage, support access with audit |

## 3. Product philosophy

1. **One core, configuration on the outside.** Industry and country differences enter through
   configuration records (`business_type`, `term_set`, `tax_profile`, `numbering_scheme`,
   `module flags`), never through `if (industry == …)` branches in services.
2. **The financial engine is deterministic.** Money, tax and totals are computed by the server in one
   place, currency-precision aware. AI may *suggest*, never *compute* an approved amount.
3. **Terminology is data, not code.** "Job / Service visit / Work order / Shipment / Session" and
   "Technician / Engineer / Driver / Instructor" must come from a term set per business type.
4. **Records are historical.** An issued quote or invoice is immutable: its currency, exchange rate,
   tax composition, numbering and totals are snapshots, not live lookups.
5. **Every tenant-scoped row carries `tenant_id`,** and every query is scoped by it — unchanged from
   v1 and non-negotiable for BI and AI.
6. **Assistive, not autonomous, for money.** AI drafts, explains and recommends; a human approves
   anything that reaches a customer or changes a price.

## 4. Cleaning-specific assumptions found in the code (evidence)

These are the actual artifacts that make the product single-industry. All are cheap to change, and
none require touching business logic — apart from the ones marked *(engine)*.

| # | Location | Assumption | Kind |
| --- | --- | --- | --- |
| A1 | `frontend/index.html:15` | `<title>Operonix — Cleaning operations, in one place</title>` | copy |
| A2 | `frontend/index.html:7` | meta description "…for cleaning businesses" | copy |
| A3 | `frontend/src/components/AuthShell.tsx:51,54` | "Built for cleaning businesses", "Run the whole clean…" | copy |
| A4 | `frontend/src/pages/Customers.tsx:39` | "The homes and businesses you clean…" | copy |
| A5 | `frontend/src/pages/Catalog.tsx:112,194` | "standard cleans", placeholder "Deep clean — 2 bed apartment" | copy + demo |
| A6 | `frontend/src/components/JobDialog.tsx:200,206` | hint "Cleaning service — customer", placeholder "Weekly office clean" | copy |
| A7 | `frontend/src/components/ConvertQuoteDialog.tsx:118` | placeholder "Weekly office clean" | copy |
| A8 | `frontend/src/pages/Register.tsx:64` | placeholder "Sparkle & Co. Cleaning" | copy |
| A9 | `backend/.../scheduling/JobService.java:419` | default job title `"Cleaning service — " + customer` | copy *(engine default)* |
| A10 | `backend/.../config/OpenApiConfig.java:20` | API description "vertical SaaS for cleaning businesses" | copy |
| A11 | `backend/.../catalog/ServiceUnit.java` | `HOUR, VISIT, SQUARE_FOOT, ROOM, FLAT`; DB `ck_service_item_unit` | domain enum |
| A12 | `backend/.../identity/Role.java` | `TECHNICIAN` (field-worker label baked into auth role) | domain naming |
| A13 | `backend/.../scheduling/Recurrence.java`, DB check | `NONE, WEEKLY, BIWEEKLY, MONTHLY` only | domain enum |
| A14 | `backend/.../tenancy/TenantService.java:36-37` | new workspace defaults `USD` + `UTC`; no country/locale/business type | defaults |
| A15 | `backend/.../tenancy/WorkspaceService.java:22` + DB `ck_tenant_currency` | currency allow-list `USD,EUR,GBP,INR,AUD,CAD` — **no JPY**, no configurable list | hard constraint |
| A16 | `frontend/src/pages/Settings.tsx:10-35` | same 6 currencies and a 12-entry timezone list | hardcoded UI |
| A17 | `frontend/scripts/demo-seed.mjs` | "Harbor Cleaning Co", "Deep clean — treatment rooms" | demo data |
| A18 | `README.md:3` | "v1 serves **cleaning companies**" | positioning |
| A19 | `V1__schema.sql` `service_item.tax_rate numeric(5,4)` | exactly one tax rate per line; no components, no inclusive/exclusive flag | **tax engine** |
| A20 | `MoneyCalculator` `MONEY_SCALE = 2` | 2-decimal money hardcoded (JPY is 0, KWD/BHD are 3) | **money engine** |
| A21 | `DocumentNumberService.next` | `"%s-%d-%04d"` with `Q/J/INV` prefixes; no tenant pattern, no series, no reset rule | **documents** |
| A22 | `billing/PaymentMethod.java` | `CASH, CARD, BANK_TRANSFER, CHECK, OTHER` — no UPI, SEPA, ACH, Konbini, COD | payments |
| A23 | `ReportService` | `DateTimeFormatter` with `Locale.ENGLISH` labels ("MMM d") | i18n |

**Not cleaning-specific (keep as-is):** tenancy model, auth/token design, role *semantics*, quote→job→
invoice lineage, discount/tax arithmetic *approach*, audit log, document sequence design, module
boundaries, API conventions, UI shell and design system.

## 5. Global market strategy

Design universally; **activate markets in phases**. Everything in §6 is architecture. Only the rows
marked *compliance validation* require a country-specific legal/tax review before that market goes
live.

| Market | Currency precision | Tax model to support | Address/phone shape | Launch wave |
| --- | --- | --- | --- | --- |
| India | 2 (INR) | GST: CGST+SGST (intra-state) or IGST (inter-state), HSN/SAC codes, inclusive pricing common | state + 6-digit PIN, +91 | Wave 1 |
| United States | 2 (USD) | sales tax by state/county/city, tax-exempt customers, tax on services varies by state | state + ZIP(+4), +1 | Wave 1 |
| United Kingdom | 2 (GBP) | VAT 20% (reduced 5%, zero 0%), VAT-inclusive B2C pricing, reverse charge for B2B services | postcode, +44 | Wave 2 |
| Japan | **0 (JPY)** | consumption tax 10%/8%, tax-inclusive display is the norm, qualified invoice (invoice system) fields | prefecture + 7-digit postal, +81 | Wave 2 |
| Other | per ISO 4217 | generic multi-component tax framework | configurable | Wave 3 |

**Universal vs country-specific**

| Universal platform capability (build once) | Requires per-country validation before activation |
| --- | --- |
| Tenant country/locale/currency/timezone; currency precision from ISO 4217 | Which tax components and rates apply, and their legal names |
| Tax *model*: named components, rate, compound flag, inclusive/exclusive, jurisdiction tag | Statutory invoice fields (e.g. Japan qualified invoice number, India GSTIN, UK VAT number) |
| Numbering scheme with pattern/series/reset; gapless option | Legal numbering constraints (e.g. sequential, no gaps, fiscal-year reset) |
| Legal entity + business location model (tax registration per location) | Registration requirements and thresholds per country |
| Address/phone as structured-but-flexible fields with country-specific formatting | Mandatory fields per country (e.g. India state code, JP prefecture) |
| Payment records with method catalogue + external reference | Which gateways/providers are usable and their settlement rules |
| Exchange-rate snapshot on documents when currencies differ | Whether multi-currency invoicing is permitted and how FX gains are treated |
| Data residency by tenant region | Whether a local presence/data-transfer mechanism is required (legal) |

**Explicitly not required to launch everywhere at once:** payment-gateway integrations per country,
localized document PDFs, statutory e-invoicing (India IRN/Japan Peppol equivalents), payroll. Those
are P2/P3 and gated by market demand.

## 6. What "industry-independent" means in practice

A new business type must be onboardable **without a code change**:

1. Choose a **business type** (seeded template: terminology + default units + default modules).
2. Set **country / locale / currency / timezone / fiscal year** → derives precision, number format,
   tax components to configure.
3. Configure **tax profile(s)** and attach to legal entity/location.
4. Configure **numbering schemes** per document type.
5. Toggle **modules** (e.g. equipment rental needs availability + deposits; construction needs
   progress billing; training needs cohorts) — off by default for others.

That path is why the roadmap puts **P0 configuration foundations** before BI and AI: BI metrics and AI
answers must read configured tax/currency/terminology, or they will need rewriting later.

## 7. Scope boundaries (this evolution)

**In scope (design now, build in phases):** industry configuration, globalization foundations, BI
analytics, Operonix AI (ask/assist/advise/automate), subscription plans + AI quotas, background jobs.

**Out of scope (documented, not designed in depth):** payroll, inventory/warehouse management,
full accounting ledger (double-entry GL), e-invoicing statutory integrations, native mobile apps,
customer self-service portal (still a candidate for Wave 2), marketplace.

## 8. Success criteria for the evolution

| Criterion | Measure |
| --- | --- |
| Industry independence | A second business type (e.g. pest control) onboarded with configuration only; zero industry branches in services |
| Globalization | A JPY workspace shows 0-decimal money end-to-end; a GST workspace issues a correctly split tax invoice; both without code changes |
| BI trust | Every BI number is reconcilable to a defined formula over transactional data (documented in BUSINESS_INTELLIGENCE_DESIGN.md §4) |
| AI safety | 0 AI-generated statements about tenant data that bypass the semantic layer; 100% of AI-triggered writes require approval and land in audit |
| Cost control | AI spend per tenant observable and capped by plan; no unmetered model calls |
| Compatibility | Existing v1 tenants keep working through the migration with no data loss and no API break for `frontend/` |

## 9. Decisions this document needs from the owner

See [ARCHITECTURAL_DECISIONS.md](ARCHITECTURAL_DECISIONS.md) — the ones that change the product
shape: business-type model (D1), terminology source (D2), tax component model (D4), currency
precision storage (D5), numbering configurability (D6), BI freshness target (D8), AI provider and
data-handling policy (D10/D11), and the subscription/plan model (D12).