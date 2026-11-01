/**
 * Single formatting authority for the client (docs/FRONTEND_GLOBALIZATION.md).
 *
 * Two rules make this global-ready:
 *  1. Money precision comes from the currency, not from a constant. JPY has zero minor units, so it
 *     must never render ".00"; most currencies have two; a few have three.
 *  2. Every formatter takes the workspace locale, which the auth layer injects once via
 *     {@link setFormatLocale}. Call sites stay unchanged and become locale-aware for free.
 */

/** Currencies with no minor unit. Kept explicit rather than guessed from the browser. */
const ZERO_DECIMAL_CURRENCIES = new Set([
  "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "UYI", "VND", "VUV", "XAF", "XOF", "XPF",
]);

/** Currencies with three minor units (kept for completeness; none are active markets yet). */
const THREE_DECIMAL_CURRENCIES = new Set(["BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND"]);

let activeLocale: string | undefined;

/** Called by the auth layer when a session (or the user's saved preference) resolves. */
export function setFormatLocale(locale: string | null | undefined): void {
  activeLocale = locale || undefined;
}

export function formatLocale(): string | undefined {
  return activeLocale;
}

export function currencyMinorUnits(currency: string | null | undefined): number {
  const code = (currency ?? "").toUpperCase();
  if (ZERO_DECIMAL_CURRENCIES.has(code)) return 0;
  if (THREE_DECIMAL_CURRENCIES.has(code)) return 3;
  return 2;
}

export interface MoneyFormatOptions {
  locale?: string;
  /** Override precision; defaults to the currency's real minor units. */
  maximumFractionDigits?: number;
}

export function formatMoney(
  value: number | null | undefined,
  currency: string,
  options: number | MoneyFormatOptions = {},
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  const resolved: MoneyFormatOptions = typeof options === "number"
    ? { maximumFractionDigits: options }
    : options;
  const digits = resolved.maximumFractionDigits ?? currencyMinorUnits(currency);
  return new Intl.NumberFormat(resolved.locale ?? activeLocale, {
    style: "currency",
    currency,
    maximumFractionDigits: digits,
    minimumFractionDigits: digits,
  }).format(value);
}

/** 0.0825 → "8.25%", 0.08 → "8%" — trailing zeros are dropped, never the significant ones. */
export function formatPercent(rate: number | null | undefined, locale?: string): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) {
    return "—";
  }
  const rounded = Math.round(rate * 10_000) / 100;
  return new Intl.NumberFormat(locale ?? activeLocale, {
    style: "percent",
    maximumFractionDigits: 2,
  }).format(rounded / 100);
}

export function formatQuantity(value: number | null | undefined, locale?: string): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  return new Intl.NumberFormat(locale ?? activeLocale, { maximumFractionDigits: 3 }).format(value);
}

export function formatCompactMoney(
  value: number | null | undefined,
  currency: string,
  locale?: string,
): string {
  if (value === null || value === undefined) {
    return "—";
  }
  return new Intl.NumberFormat(locale ?? activeLocale, {
    style: "currency",
    currency,
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(value);
}

/** Date-only values (yyyy-mm-dd) are formatted in UTC so they never shift a day. */
export function formatDate(value: string | null | undefined, locale?: string): string {
  if (!value) {
    return "—";
  }
  const [year, month, day] = value.slice(0, 10).split("-").map(Number);
  if (!year || !month || !day) {
    return value;
  }
  return new Intl.DateTimeFormat(locale ?? activeLocale, {
    timeZone: "UTC",
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(new Date(Date.UTC(year, month - 1, day)));
}

/**
 * Date of an instant, rendered in the workspace's time zone. Unlike `formatDate` (which formats a
 * date-only value such as an issue date, always as written) this converts the instant first, so the
 * date always belongs to the same day as the time shown next to it.
 */
export function formatCalendarDate(
  value: string | null | undefined,
  timezone?: string,
  locale?: string,
): string {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat(locale ?? activeLocale, {
    timeZone: timezone,
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(new Date(value));
}

export function formatDateTime(value: string | null | undefined, timezone?: string, locale?: string): string {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat(locale ?? activeLocale, {
    timeZone: timezone,
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(value));
}

export function formatTime(value: string | null | undefined, timezone?: string, locale?: string): string {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat(locale ?? activeLocale, {
    timeZone: timezone,
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(value));
}

export function formatDay(value: string | null | undefined, timezone?: string, locale?: string): string {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat(locale ?? activeLocale, {
    timeZone: timezone,
    weekday: "short",
    month: "short",
    day: "numeric",
  }).format(new Date(value));
}

export function dayKey(value: string, timezone?: string): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date(value));
}

export function todayISO(): string {
  const now = new Date();
  const offset = now.getTimezoneOffset();
  return new Date(now.getTime() - offset * 60_000).toISOString().slice(0, 10);
}

export function addDaysISO(dateISO: string, days: number): string {
  const [year, month, day] = dateISO.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

export function startOfWeekISO(dateISO: string = todayISO()): string {
  const [year, month, day] = dateISO.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  const weekday = date.getUTCDay();
  const diff = weekday === 0 ? -6 : 1 - weekday;
  date.setUTCDate(date.getUTCDate() + diff);
  return date.toISOString().slice(0, 10);
}

/** yyyy-mm-ddTHH:mm in the browser's zone, suitable for datetime-local inputs. */
export function toDateTimeLocal(value: string | Date): string {
  const date = typeof value === "string" ? new Date(value) : value;
  const offset = date.getTimezoneOffset();
  return new Date(date.getTime() - offset * 60_000).toISOString().slice(0, 16);
}

export function fromDateTimeLocal(value: string): string {
  return new Date(value).toISOString();
}

export function addMinutes(value: string, minutes: number): string {
  return new Date(new Date(value).getTime() + minutes * 60_000).toISOString();
}

export function initialOf(name: string | null | undefined): string {
  return (name ?? "?").trim().charAt(0).toUpperCase();
}

export function labelize(value: string | null | undefined): string {
  if (!value) {
    return "—";
  }
  return value
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function pluralize(count: number, singular: string, plural?: string): string {
  return `${count} ${count === 1 ? singular : plural ?? `${singular}s`}`;
}

/** Best-effort browser timezone, used only as an onboarding suggestion. */
export function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}