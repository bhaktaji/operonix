# Operonix Design System

**Version 2.0 (P0)** · Applies to every screen: authentication, onboarding, operations, administration.
Source of truth for tokens: [`frontend/tailwind.config.js`](../frontend/tailwind.config.js) and
[`frontend/src/index.css`](../frontend/src/index.css). Source of truth for components:
[`frontend/src/components/ui`](../frontend/src/components/ui).

---

## 1. Principles

1. **Calm by default.** A business owner opens Operonix to answer a question, not to admire a screen.
   Surfaces are quiet, colour is reserved for meaning, and only one element per view carries the accent.
2. **One hierarchy per screen.** Kicker → title → description → actions → content. Every page uses
   `PageHeader`, so the eye lands in the same place everywhere.
3. **Numbers are content.** Money, counts and dates use tabular formatting through `src/lib/format.ts`,
   never ad-hoc string building. A number that changes width as it updates is a bug.
4. **Never assume the trade.** No icon, illustration or label may imply an industry. The mark is an
   operating loop, not a broom, a wrench or a truck.
5. **Density with air.** Tables are tight vertically (44 px rows) but generous horizontally, and cards
   use a single 20 px padding rhythm.

---

## 2. Design tokens

### Colour

| Token | Value | Use |
| --- | --- | --- |
| `ink-950` | `#080b14` | Sidebar, brand surfaces, primary buttons |
| `ink-900` | `#0e1322` | Headings |
| `ink-700 / 600 / 500` | `#2b3651 / #42506e / #5d6b88` | Body, secondary, muted text |
| `ink-400 / 300` | `#7d89a3 / #a3adc2` | Placeholders, sidebar idle links |
| `sand-100` | `#f4f6fa` | Application background |
| `sand-200 / 300` | `#e8ecf3 / #d7dde8` | Borders, dividers, chart grid |
| `brand-500` | `#5b63ec` | Primary accent: primary action, active chart, focus ring |
| `brand-50 / 100` | `#eef1ff / #e0e5ff` | Accent tint backgrounds |
| `mint-500/600` | `#10b981 / #059669` | Success, positive deltas, paid states |
| `amber-500/600` | `#f59e0b / #d97706` | Attention, outstanding balance |
| `rose-600` | `#e11d48` | Destructive actions, errors |

**Rules.** Never use `brand-500` for text on white below 14 px bold (contrast); use `brand-700`. Success
is `mint`, information is `brand`, warning is `amber`, failure is `rose` — never repurpose them.

### Typography

| Role | Family | Size / weight / tracking |
| --- | --- | --- |
| Page title | Sora | 26–30 px / 600 / `-0.035em` |
| Section title | Sora | 15 px / 600 |
| Body | Inter | 14 px / 400, 1.6 line-height |
| Label | Inter | 13 px / 500 |
| Kicker | Inter | 11 px / 600 / `0.18em` uppercase |
| Numeric display | Sora | 26 px / 600, tabular |
| Table head | Inter | 11 px / 600 / `0.14em` uppercase |

Fonts load from Google Fonts in `index.html`; the production CSP already allows `fonts.googleapis.com`
and `fonts.gstatic.com`. No other font may be introduced without updating the CSP.

### Spacing, radius, elevation

- Spacing scale: 4 px base; card padding 20 px; page gutter 16 px mobile, 24 px tablet, 32 px desktop.
- Radius: inputs/buttons `8px` (`rounded-lg`), cards `18px` (`rounded-xl2`), dialogs `18px`, pills full.
- Elevation: exactly two shadows — `shadow-card` (resting surfaces) and `shadow-float` (dialogs,
  dropdowns, drawers). Nothing else casts a shadow.

---

## 3. Layout

- **Application shell:** fixed 264 px sidebar (`ink-950`) on `lg` and above; slide-over drawer below.
  Sticky 56 px top bar on the content side with workspace identity on the left and the account menu on
  the right. Content max width 1400 px.
- **Navigation groups:** Operations → Business → Administration, filtered by role. Labels come from the
  i18n catalogue, so a terminology change is one string.
- **Authentication:** split screen ≥ 1024 px (brand panel + form), single column below. The brand panel
  never shows product screenshots or trade imagery.
- **Mobile:** single column, 16 px gutters, tables scroll horizontally inside their card rather than
  compressing columns.

---

## 4. Components

All live in `components/ui`. Pages must compose these rather than re-styling raw elements.

| Component | Contract |
| --- | --- |
| `Button` | Variants `primary` (ink), `secondary` (brand), `outline`, `ghost`, `danger`; sizes `sm`/`md`; `loading` disables and swaps the icon for a spinner |
| `Field` | Label + control + error/hint; the only way to render a form row |
| `Input`, `Textarea`, `Select` | 40 px high, 8 px radius, 4 px brand focus ring, disabled state greyed |
| `Badge` | Tone from `statusTone()`; never hand-pick colours for a status |
| `Card`, `SectionCard` | Surface with optional header (title, description, actions) |
| `PageHeader` | Kicker, title, description, actions |
| `StatCard` | Label, value, hint, icon, tone, loading skeleton |
| `EmptyState` | Icon, title, description, one primary next action |
| `Modal`, `ConfirmDialog` | Escape/backdrop close, focus trap, destructive confirm states that the action is audited |
| `Tabs`, `Pagination` | Consistent list navigation |
| `ErrorBanner`, `Skeleton`, `PageLoader`, `Spinner` | Loading and failure states |
| `Toast` | Success/failure feedback after a mutation |

### Interaction conventions

- Mutations always give feedback: an inline banner for validation, a toast for success.
- Destructive actions are always confirmed by a dialog naming the record, and the dialog states that the
  action is audited.
- Buttons that submit show `loading` and stay disabled until the request settles.
- Empty states always answer "what can I do next?" — never a bare "no data".
- Tables: the first column identifies the record, money is right-aligned, status uses a badge, and the
  final column holds the only navigation affordance.

### Accessibility

- Every control is reachable by keyboard; visible focus comes from `focus-visible` rings (4 px).
- Forms use `label htmlFor`, `aria-invalid` through `Field` errors, and `autocomplete` on credentials.
- Dialog titles are referenced by `aria-labelledby`; drawers close on `Escape`.
- Colour is never the only signal: badges carry text, deltas carry ▲/▼.
- Minimum contrast: body text ≥ 4.5:1 (`ink-700` on white is 9.4:1).

---

## 5. Content and terminology

Vocabulary is platform-level, not industry-level:

| Use | Instead of |
| --- | --- |
| Customer / client | Homeowner, account |
| Service, catalog item | Clean, treatment, product |
| Job, work order | Clean, visit-only wording |
| Quotation, estimate | Quote (in navigation and headings) |
| Scheduling | Calendar, dispatch board |
| Team, workforce | Crew, cleaners |
| Business locations | Properties |
| Workspace settings | Admin, company |

An industry template may change the *fallback job title* (for example "Pest treatment — <customer>") and
onboarding copy, but never the navigation, table headers or document vocabulary.

---

## 6. How to change the system

1. Change the token in `tailwind.config.js` — not a class string inside a page.
2. Change a component once in `components/ui`; never fork it per page.
3. Any new pattern used twice becomes a component, documented here.
4. Any new colour needs a documented meaning; colour without meaning is decoration and is rejected in
   review.

**Documented deviations:** none. The P0 UI rebuild replaced the previous warm sand/green palette
(brand `#12a385`) with the ink/porcelain/indigo system above; every page inherits it through tokens,
which is why no page needed a bespoke palette.