# Operonix — Globalization and Localization

| Field | Value |
| --- | --- |
| Version | 1.0 (proposal — not approved) |
| Date | 2026-10-31 |
| Markets in scope | India, United States, United Kingdom, Japan (wave 1–2), others later |
| Principle | Design universally, activate per market. Never hardcode a country rule into core logic. |

---

## 1. Current state (verified in code)

| Concern | Current implementation | Evidence |
| --- | --- | --- |
| Currency set | Closed list `USD, EUR, GBP, INR, AUD, CAD` | `WorkspaceService.CURRENCIES:22`; DB `ck_tenant_currency` |
| Currency default | `USD` on provisioning; `UTC` timezone | `TenantService.provision:36-37` |
| Currency precision | Always 2 decimals | `MoneyCalculator.MONEY_SCALE = 2`; `numeric(12,2)` columns |
| Currency display | `Intl.NumberFormat` with `minimumFractionDigits: 2` | `frontend/src/lib/format.ts:formatMoney` |
| Tax | One rate per catalogue item/line, exclusive only | `service_item.tax_rate numeric(5,4)`; `MoneyCalculator.calculate` |
| Document numbering | `Q-2026-0001` from a JSON-free pattern in code | `DocumentNumberService.next`, `DocumentType` prefixes |
| Country | Not stored anywhere; customer address `billing_country varchar(2)` | `customer` table |
| Locale | None; server formats with `Locale.ENGLISH`; client uses browser locale | `ReportService.DAY_LABEL/MONTH_LABEL`; `format.ts` |
| Timezone | Per-tenant IANA string; correct usage in reporting windows | `tenant.timezone`, `ReportService` |
| Phone | `varchar(32)` free text | `customer.phone` |
| Postal code | `varchar(16)` free text | `customer.billing_postal_code`, `service_location.postal_code` |
| Payment methods | 5 fixed enum values | `PaymentMethod` |
| Language | English literals in TSX; no bundles | 16 pages, 11 components |
| Fiscal year | Calendar year implied by `document_sequence.year` | `document_sequence` PK `(tenant, doc_type, year)` |

**Conclusion:** the *timezone* handling and the *tenant-currency snapshot on documents* are already
right. Everything else is v1-single-market: currency is a closed list with fixed 2-dp arithmetic,
tax is one scalar, numbering is a code pattern, and there is no locale/country/business-type concept.

## 2. Currency model

### 2.1 Representation

| Decision | Choice | Rationale |
| --- | --- | --- |
| Storage type | `numeric(19,4)` | Holds 3-dp currencies (KWD/BHD) with headroom; wider than needed for 2-dp today |
| Java type | `BigDecimal` (unchanged) | No floating point anywhere; already the rule |
| Scale source | `currency.minor_units` reference table (JPY 0, USD/EUR/GBP/INR 2, KWD 3) | One source of truth, no `switch` in services |
| Rounding | HALF_UP at the currency scale, applied per line then summed | Matches current behaviour and typical invoicing practice; documented per market |
| Display | `Intl.NumberFormat(locale, {style:'currency', currency, minimumFractionDigits: scale, maximumFractionDigits: scale})` | Locale-aware grouping/symbol placement (¥1,234 vs ₹1,23,456) |

`MoneyContext(currencyCode, scale)` replaces the global `MONEY_SCALE` constant:

```java
Totals calculate(List<Line> lines, BigDecimal discount, MoneyContext ctx);
```

Existing call sites pass the tenant/document currency; quoted/invoiced documents pass the **currency
snapshot stored on the document**, never the tenant's current setting.

### 2.2 Multi-currency documents and FX

| Case | Rule |
| --- | --- |
| Tenant currency changes | Existing quotes/invoices keep their snapshot (already true in v1) |
| Customer billed in another currency | Allowed from P1: document stores `currency`, `currency_scale`, and `fx_rate` + `fx_rate_date` + `fx_rate_source` when it differs from the tenant's base currency |
| Reporting roll-up | Base-currency amounts are computed from **stored snapshots** (never by re-fetching rates later) so history is stable |
| Rate source | Pluggable `ExchangeRateProvider` (manual entry first, an API later). Manual entry is the P1 default — no external dependency, no key |
| Rounding across currencies | Convert, then round at the target currency scale once; store both the original and the base-currency amount |

`exchange_rate` table: `(base_currency, quote_currency, rate_date, rate, source)` with a unique key —
auditable, tenant-agnostic reference data.

## 3. Tax model

### 3.1 Structure

```
tenant ─┬─ legal_entity (country, tax_registration_ids jsonb)
        │      └─ business_location (address, registrations)
        └─ tax_profile (name, jurisdiction, prices_include_tax)
               └─ tax_component (code, rate, compound, sort_order)
service_item.tax_profile_id ─┐
quote_line / invoice_line ────┴─ tax_profile_id  (snapshot copied to document_tax_line)
```

| Field | Purpose |
| --- | --- |
| `tax_profile.prices_include_tax` | UK/JP B2C inclusive pricing; extraction = `gross × rate / (1 + Σrate)` |
| `tax_component.code` | `GST`, `CGST`, `SGST`, `IGST`, `VAT`, `SALES_TAX`, `CESS` |
| `tax_component.compound` | Applied on base + prior components (rare, e.g. some levies) |
| `tax_component.sort_order` | Deterministic computation and display order |
| `document_tax_line` | Immutable snapshot per issued document: code, rate, taxable base, amount, jurisdiction |

### 3.2 Computation rules (deterministic, server-side)

```
gross or net per line  →  allocate discount proportionally  →  per component (in sort order):
     amount = round(base × rate, currency_scale)             [net-based]
     amount = round(gross × rate / (1 + Σrates), scale)       [inclusive]
tax_amount = Σ component amounts        (kept for backward compatibility with v1 fields)
```

### 3.3 Market mapping (illustrative, requires validation before activation)

| Market | Model | Notes requiring validation |
| --- | --- | --- |
| India | `tax_profile` per state: CGST + SGST (intra-state) or IGST (inter-state), plus optional cess | Place of supply; HSN/SAC on lines; GSTR-compatible export |
| US | Profile per jurisdiction with one or more `SALES_TAX` components; exempt customer flag | Service taxability varies by state; nexus rules are the tenant's responsibility |
| UK | `VAT` 20% / 5% / 0%; inclusive or exclusive per profile | VAT number on invoices; reverse charge wording for B2B |
| Japan | `CONSUMPTION_TAX` 10% / 8%, inclusive display typical | Qualified-invoice registration number; rounding convention |

**Hard rule:** the engine computes what the profile says. It does not decide *which* profile applies
automatically until a market-specific rules module is explicitly approved (P3). The tenant selects the
profile (default per location, overridable per line).

## 4. Numbering, legal entities, fiscal year

| Capability | Design |
| --- | --- |
| Pattern | `{PREFIX}-{FY}-{SEQ:5}` template with tokens: `{PREFIX}`, `{FY}`, `{YYYY}`, `{MM}`, `{SEQ:n}`, `{ENTITY}`, `{LOCATION}` |
| Reset | `NEVER`, `CALENDAR_YEAR`, `FISCAL_YEAR` (tenant `fiscal_year_start_month`) |
| Gapless | `numbering_scheme.gapless` — when true, allocation happens in the issuing transaction (already the v1 behaviour) |
| Series | Multiple schemes per document type (e.g. `INV` domestic, `EXP` export) selectable per legal entity/location |
| Backward compatibility | Existing tenants get a scheme that renders exactly `Q-2026-0001` / `J-…` / `INV-…` |

## 5. Addresses, phones, postal codes

Keep the current flat columns as the *core* (they already cover line1/line2/city/region/postal/country)
and add:

| Addition | Reason |
| --- | --- |
| `country_code` validated against the `country` reference table | Replace free 2-char strings; enables per-country behaviour |
| `region_code` (optional) | India state code (GST place of supply), US state, JP prefecture, UK county |
| `address_extras jsonb` | Market-specific fields (JP building/room, IN landmark) without new columns per market |
| Phone as `E.164` + `raw_input` | Normalized for future SMS/WhatsApp; raw preserved for display |
| Postal validation | Pattern hints per country (IN `^\d{6}$`, UK postcode regex, JP `^\d{3}-?\d{4}$`) as **warnings**, not hard failures |

Display formatting stays client-side per locale (`Intl` + country templates) — never stored formatted.
## 6. Dates, numbers, time zones, fiscal calendar

| Concern | Rule | Implementation |
| --- | --- | --- |
| Storage | `timestamptz` for instants; `date` for business dates (already true) | No change |
| Business-day boundaries | Tenant timezone (`tenant.timezone`) | Already correct in `ReportService` |
| Display | User/tenant locale with explicit timezone | `formatDateTime(value, timezone, locale)` |
| Reporting buckets | Day/week/month boundaries computed in the tenant zone, not UTC | Keep; add fiscal-period helpers |
| Week start | Locale-driven (US Sunday, UK/EU/IN Monday) — configurable per tenant | `tenant.week_start` |
| Fiscal year | `tenant.fiscal_year_start_month` (default 1) | Drives numbering, BI periods, exports |
| Number formatting | Locale grouping (`en-IN` lakh/crore grouping, `ja-JP` full-width digits optional) | `Intl.NumberFormat(locale)` |
| Server-side labels | No `Locale.ENGLISH` formatters in reports | Return **data + keys**; let the client localize, or send `Accept-Language` |

**Action:** `ReportService.DAY_LABEL`/`MONTH_LABEL` (`Locale.ENGLISH`) become either ISO labels plus
client formatting, or locale-aware `DateTimeFormatter` with the tenant locale. This is a small change
with a real user-visible payoff in JP/IN.

## 7. Language and terminology

Three separate layers — do not conflate them:

| Layer | Example | Source |
| --- | --- | --- |
| **UI strings** (i18n) | "Customers", "Save", "Overdue" | Message catalogue per locale (ICU MessageFormat), key-based keys `customers.title` |
| **Business terminology** (industry) | "Job/Visit/Work order/Session", "Technician/Engineer/Driver/Instructor", "Customer/Client/Patient/Member" | `term_set` in the `platform` module, per tenant (from `business_type` default) |
| **Document content** | Quote line descriptions, invoice notes | Tenant-authored text; AI may draft (with approval) |

**Terminology contract** (frontend):

```ts
const t = useTerms();          // resolved from tenant's business type + overrides
t("job.plural")                // "Visits" | "Work orders" | "Shipments"
t("worker.singular")           // "Technician" | "Engineer" | "Driver"
```

Every user-visible noun in the SPA that is industry-specific must come from `useTerms()`; a grep test
fails the build if a disallowed literal ("job", "clean", "technician") appears in a JSX string.

**Translation readiness rules**

1. No string concatenation for sentences — ICU messages with placeholders (`{count, plural, …}`).
2. No date/number formatting outside `lib/format.ts`.
3. `dir` attribute support (`ltr`) reserved; RTL is out of scope but the layout must not hardcode `left/right` in new code.
4. Locale resolution: `user.locale` → `tenant.locale` → `country.default_locale` → `en`.
5. Translation files are shipped per locale; AI-assisted translation is a suggestion pipeline, never auto-merged.

### Wave plan for languages

| Wave | Locales | Notes |
| --- | --- | --- |
| 1 | `en-IN`, `en-US`, `en-GB` | Same language, different formats + terminology; proves the i18n plumbing |
| 2 | `ja-JP`, `hi-IN` | First genuinely translated UI; requires native review of money/tax labels |
| 3 | Others on demand | Machine-drafted, human-reviewed |

## 8. Regional configuration model

A new tenant's onboarding resolves defaults from the selected country, and the tenant can override:

```
country (IN) → defaults: currency INR, locale en-IN, timezone Asia/Kolkata,
               tax components [CGST, SGST, IGST], fiscal year start 4, week start MO,
               numbering {FY} pattern, payment methods [CASH, CARD, UPI, BANK_TRANSFER]
```

| Stored on | Reference data | Tenant override |
| --- | --- | --- |
| `tenant.country_code`, `currency_code`, `locale`, `timezone`, `week_start`, `fiscal_year_start_month` | `country`, `currency` tables (seeded) | Yes, all |
| `tax_profile` rows | Seeded templates per country (rates as **examples**, tenant confirms) | Yes — tenant owns final rates |
| `payment_method` rows | Seeded catalogue with `country_codes[]` | Tenant enables a subset |
| Numbering | Seeded schemes | Yes |

This is what makes "activate India in wave 1" a data task rather than a release.

## 9. Universal vs country-specific (compliance boundary)

| Universal (built once, no legal review) | Country-specific (**requires validation before activation**) |
| --- | --- |
| Currency scale/precision, FX snapshot mechanics | Which tax rates apply and how they split |
| Tax component model (N components, compound, inclusive) | Statutory tax names, rounding conventions, place-of-supply/nexus |
| Numbering patterns, series, resets, gapless | Legal numbering constraints (gapless mandates, fiscal series) |
| Legal entity + location model | Registration identifiers required on documents |
| Address/phone structure + formatting hints | Mandatory address fields per country |
| Multi-locale UI, timezone handling, fiscal periods | Legally mandated invoice wording/language |
| Payment method catalogue (data) | Which gateways are licensable/usable, settlement rules |
| Data residency *mechanism* (tenant region) | Whether residency/transfer is legally required |

**Explicit disclaimer:** this document makes *architectural* recommendations only. It is not legal,
tax or accounting advice, and no market may be activated on the basis of this document alone.

## 10. Data residency (summary — details in SECURITY_AND_COMPLIANCE_IMPACT.md)

| Level | Mechanism | Cost |
| --- | --- | --- |
| R0 (default) | Single region (`asia-south1`), all tenants | Cheapest; fine for most SMB customers |
| R1 | `tenant.region` column; documents/data carry region; API rejects cross-region writes | Medium — routing logic, one deployment per region |
| R2 | Per-region Cloud SQL + Cloud Run, per-region secret sets, region-aware DNS | Highest — full duplication of infra |

Recommendation: implement the **column and routing seam** in P1 (cheap, no infra), activate a second
region only when a customer contract requires it.

## 11. Migration strategy for existing tenants

| Step | Migration | Safety |
| --- | --- | --- |
| 1 | Seed `currency`, `country`, `payment_method`, `business_type`, `term_set` | Reference data only |
| 2 | Add nullable columns to `tenant` (`country_code`, `locale`, `currency_code`, `week_start`, `fiscal_year_start_month`, `business_type_id`) | Additive |
| 3 | Backfill: `currency_code = currency`; `country_code` from the first customer's billing country or `'US'`; `locale = 'en'`; `business_type = 'general_service'` | Deterministic, reversible |
| 4 | Create one `legal_entity` + `business_location` per tenant from the workspace name | Deterministic |
| 5 | Create a `tax_profile` per tenant containing one component per distinct `tax_rate` in use, and link `service_item.tax_profile_id` | Preserves exact arithmetic |
| 6 | Widen money columns to `numeric(19,4)` | Lossless |
| 7 | Drop `ck_tenant_currency` only after `currency_code` FK validation is in place | One-way, gated by tests |

Each step is a separate forward-only migration; the app reads both old and new columns during the
transition window (expand → migrate → contract), so no downtime is required.

## 12. Testing requirements

| Test | Type |
| --- | --- |
| JPY totals have 0 decimals; KWD 3; USD 2 | Unit (parametrized) |
| Inclusive tax extraction (`gross 1100 @10% → tax 100`) | Unit |
| CGST+SGST split sums to IGST equivalent | Unit |
| Compound component ordering | Unit |
| Numbering patterns (`{PREFIX}-{FY}-{SEQ:5}`) and fiscal reset | Service |
| Number formatting via `Intl` for `en-IN`, `ja-JP` (client) | Component |
| Locale resolution fallback chain | Unit |
| Terminology: no hardcoded industry literal in TSX | Build-time grep test |
| Migration: existing tenant's quotes keep numbers and totals after evolution | Integration |

## 13. Phasing

| Phase | Globalization scope |
| --- | --- |
| **P0** | Currency precision + reference tables + locale/country columns + tax component model + terminology plumbing; existing tenants unchanged in behaviour |
| **P1** | Per-market tax profile activation (IN, US, UK first), legal entity/location, numbering schemes, `en-IN`/`en-GB` formatting, message-catalogue extraction |
| **P2** | Japanese localization (JPY end-to-end, `ja-JP` strings, qualified-invoice fields), multi-currency documents + FX snapshots, region routing seam |
| **P3** | Additional locales, `address_extras` per market, statutory integrations if commercially justified |
