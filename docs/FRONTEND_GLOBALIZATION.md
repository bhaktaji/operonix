# Frontend Globalization

**Scope:** what P0 actually implemented for global-ready behaviour in the React client, and the rules a
future change must follow. Companion to [GLOBALIZATION_AND_LOCALIZATION.md](product-evolution/GLOBALIZATION_AND_LOCALIZATION.md),
which describes the full target design.

---

## 1. Where configuration comes from

| Concern | Source at runtime | Fallback |
| --- | --- | --- |
| Country | `tenant.countryCode` (`/auth/me`, `/auth/login`) | `US` for pre-2.0 sessions |
| Currency | `tenant.currency` | `USD` |
| Timezone | `tenant.timezone` | `UTC` |
| Language + regional formats | `tenant.locale` (BCP-47) | browser `navigator.language` |
| Currency precision | `currency.minorUnits` from `/reference` | derived from a built-in zero/three-decimal set |
| Industry vocabulary | `tenant.industryTemplate` | `GENERAL_SERVICE` |

The tenant record is the only source. A page must never hardcode `USD`, `America/New_York` or `en-US`.

---

## 2. Formatting rules (single authority)

`frontend/src/lib/format.ts` is the only module allowed to format money, numbers or dates.

- **Money:** `formatMoney(value, currency)` — precision comes from the currency. `JPY` renders `¥1,200`;
  `INR` renders `₹1,200.00`. Never pass a hardcoded fraction-digit count.
- **Percent:** `formatPercent(rate)` formats `0.18` as locale-correct `18%`.
- **Dates:** `formatDate` for date-only values (always UTC so a day never shifts); `formatDateTime`,
  `formatDay`, `formatTime` take the tenant timezone.
- **Locale binding:** `I18nProvider` calls `setFormatLocale(tenant.locale)` once per session, so every
  call site becomes locale-aware without changing its signature.
- **Quantities:** `formatQuantity` for line-item quantities (up to 3 decimals).

**Rule:** a raw `toLocaleString()`, `new Intl.*` or template-literal currency symbol outside `format.ts` is
a defect. `formatMoney` with an explicit options object is allowed only for tests.

---

## 3. Translation architecture

- `frontend/src/i18n/messages.ts` holds the English catalogue (source of truth) and a `catalogues` map.
- `useI18n()` returns `{ locale, t }`; `t()` falls back per key to English, so a partially translated
  locale is safe to ship.
- Currently catalogued: application chrome, navigation, authentication and onboarding. Feature pages still
  contain English literals by design for P0 — that is recorded as the migration path, not as hidden work:
  a page's copy moves into the catalogue when its locale is activated.
- `document.documentElement.lang` is set from the tenant locale on every session change.

**Adding a language:** add the partial catalogue, add the locale to the settings list, verify the
`Intl` outputs for money and dates, and add the language to `messages.ts`. No component changes.

---

## 4. Onboarding and settings

`/register` is a two-step flow: **Business** (name, country, industry, currency, timezone) then **Access**
(name, email, password). Country is the only required regional input:

- selecting a country seeds its default currency and locale, both still editable;
- the timezone defaults to the browser's zone, with the full IANA list when the platform supports it;
- legal name and tax identifier are **not** requested during onboarding; they live in workspace settings
  and are explicitly optional. The label for the tax identifier comes from the country (`GSTIN`, `EIN`,
  `VAT number`, `Invoice registration number`, …), and it is hidden when a country has none.

`/settings` edits the same configuration and states plainly that issued documents keep their original
currency, amounts and numbering.

---

## 5. Backend contract used by the client

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/reference` | Public: countries, currencies (with `minorUnits`), industry templates |
| `POST /api/v1/auth/register` | Accepts optional `countryCode`, `currency`, `timezone`, `locale`, `industryTemplate`, `legalName` |
| `POST /api/v1/auth/login` | Accepts optional `workspace` slug; returns `400 WORKSPACE_REQUIRED` with the workspace list when an email belongs to several |
| `GET /api/v1/auth/me` | Returns the tenant summary including `countryCode`, `locale`, `industryTemplate` |
| `PUT /api/v1/workspace` | Whole-object update of the regional configuration |

`tenant.taxNumber` is intentionally absent from session payloads (`TenantSummary`); it is only readable
through `GET /api/v1/workspace` by an owner or admin.

---

## 6. Verification performed

| Check | Result |
| --- | --- |
| `npm run typecheck` | passes |
| `npm run build` | passes (397.99 kB JS, 34.79 kB CSS) |
| INR rendering in the live UI | ₹5,000.00, ₹10,576.00, ₹15,576.00 with `en-IN` locale |
| Indian onboarding defaults | country `IN` → `INR`, `en-IN`, GSTIN label |
| Country → locale defaults (API test) | `registrationDerivesGlobalDefaultsFromTheCountry` asserts currency and locale |
| Locale persistence | `workspaceSettingsPersistRegionalConfiguration` asserts `en-GB` survives a settings save |
| Mobile layout at 390 px | sidebar hidden, sticky header, no horizontal overflow (`scrollWidth == innerWidth`) |

**Not yet exercised end-to-end in a browser:** Japanese zero-decimal rendering (verified by unit-level
logic and the `minorUnits` contract, not by a screenshot), full translation of a non-English locale, and
right-to-left layouts. Those belong to P1a when a second language is activated.