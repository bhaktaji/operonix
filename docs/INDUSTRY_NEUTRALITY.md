# Industry Neutrality

**Scope:** how Operonix stays one configurable platform for many kinds of small business instead of a
cleaning application with a new logo. This records what P0 changed, and the rule every later feature must
follow.

Reference design: [GLOBAL_PRODUCT_VISION.md](product-evolution/GLOBAL_PRODUCT_VISION.md) and ADR-12 in
[ARCHITECTURAL_DECISIONS.md](product-evolution/ARCHITECTURAL_DECISIONS.md).

---

## 1. The governing rule

> An industry is data, not a code path.

Nothing in the domain model, the API or a business rule branches on what the customer's business does.
An industry template may change **words** (a label a user sees) and **onboarding defaults** (the title a
new job receives). It may never change pricing logic, tax arithmetic, numbering, permissions, statuses or
workflow order. If a future requirement only applies to one industry, it is expressed as a workspace
configuration flag or a template definition — not an `if (industry == CLEANING)`.

---

## 2. What was removed in P0

| Location | Was | Now |
| --- | --- | --- |
| `index.html` title / description | "Cleaning operations, in one place" | "The Operating Platform for Growing Businesses." |
| Auth shell copy and artwork | cleaning-specific phrasing and sample content | trade-agnostic product statement |
| Brand mark | star/sparkle glyph implying shine | neutral ring-with-ledger-line mark (`Brand.tsx`) |
| Registration | cleaning business implied | country + industry selected from reference data |
| Seeded tenant | cleaning company demo data | general service business |
| Job default title | `"Cleaning service"` | `IndustryTemplate.defaultJobTitle()` — `"General service"` by default |
| Dashboard metrics | cleaning-only framing | jobs, quotations, collections and receivables — vocabulary that fits any trade |
| Navigation | "Services" as a cleaning concept | "Catalog" holding services and items |
| Seed data | cleaning crews, cleaning rates | generic services (`Site visit`, `Labour hour`, `Call-out fee`, …) |

Verification for this was a repository-wide search for the strings `clean`, `cleaner`, `cleaning` and
`maid` across `frontend/src`, `backend/src/main` and the seed migration; the only remaining matches are
the `CLEANING` industry template itself and the ADRs that discuss the migration — both intentional.

---

## 3. The template catalogue

`IndustryTemplate` (backend, `com.operonix.global`) is the single definition; `/api/v1/reference` exposes
it so onboarding and settings never duplicate the list in TypeScript.

| Code | Label | Default job title |
| --- | --- | --- |
| `GENERAL_SERVICE` | General service business | General service |
| `CLEANING` | Cleaning & facilities | Cleaning service |
| `PEST_CONTROL` | Pest control | Pest treatment |
| `EQUIPMENT_RENTAL` | Equipment rental | Equipment rental |
| `AUTO_REPAIR` | Auto repair & maintenance | Repair job |
| `PLUMBING_ELECTRICAL` | Plumbing & electrical | Site visit |
| `PROPERTY_MAINTENANCE` | Property maintenance | Maintenance visit |
| `CONSTRUCTION` | Construction & contracting | Project works |
| `LOGISTICS` | Local logistics | Delivery run |
| `TRAINING` | Training & education | Training session |

`GENERAL_SERVICE` is the default and the fallback for an unknown or absent value, so an unrecognised
template can never leave a workspace without a job title. A database check constraint restricts the
column to the catalogue, which keeps typos out at the storage layer as well.

**Adding an industry** requires: one enum constant with label, description and default job title; nothing
else. No migration (the constraint is extended in the same change), no controller, no frontend list.

---

## 4. Terminology policy

Generic terms are the canonical product vocabulary:

- **Customers** (a client, an account, a resident)
- **Locations** (a site, a property, a pickup address)
- **Catalog** — services and items (a treatment, a part, a rental unit, a course)
- **Quotations / estimates**
- **Jobs** (a work order, a visit, a run, a session)
- **Team** (staff, technicians, contractors)
- **Invoices** and **payments**
- **Business operations** — the umbrella term for the product's subject matter

Where a specific trade would say something different, that belongs in a future template-driven terminology
override — a label lookup on the template, resolved once in the frontend before rendering. It must not
become a second frontend or a per-industry page. P0 does not implement overrides; it establishes the
single lookup point where they will attach, and records the decision in
[FRONTEND_GLOBALIZATION.md](FRONTEND_GLOBALIZATION.md).

---

## 5. Guardrails for reviewers

A pull request violates industry neutrality if it contains any of the following outside the template
catalogue:

1. An industry word in a shared component, page, API DTO or migration seed.
2. A conditional on `industryTemplate` inside a service, calculator or state machine.
3. A dashboard metric that only makes sense for one trade.
4. An illustration, icon or photograph that depicts one trade as the default product identity.
5. A copy string that assumes a tax identifier exists (many countries have none, and onboarding never
   requires one).

The reviewer test: *would a pest-control operator in Nairobi and a training centre in Osaka both read
this screen and feel it was written for them?* If not, the change belongs behind configuration.