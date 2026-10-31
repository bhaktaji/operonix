export function cn(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(" ");
}

export type Tone = "neutral" | "success" | "warning" | "danger" | "info" | "brand";

const toneClasses: Record<Tone, string> = {
  neutral: "bg-sand-100 text-ink-600 border-sand-200",
  success: "bg-brand-50 text-brand-700 border-brand-100",
  warning: "bg-amber-50 text-amber-700 border-amber-200",
  danger: "bg-rose-50 text-rose-700 border-rose-100",
  info: "bg-sky-50 text-sky-700 border-sky-100",
  brand: "bg-ink-900 text-white border-ink-900",
};

export function badgeTone(tone: Tone): string {
  return toneClasses[tone];
}

export function statusTone(status: string): Tone {
  switch (status) {
    case "ACTIVE":
    case "PAID":
    case "COMPLETED":
    case "ACCEPTED":
    case "CONVERTED":
      return "success";
    case "SENT":
    case "PARTIALLY_PAID":
    case "IN_PROGRESS":
      return "info";
    case "DRAFT":
    case "SCHEDULED":
      return "neutral";
    case "EXPIRED":
    case "ARCHIVED":
    case "CANCELLED":
    case "VOID":
      return "danger";
    case "DECLINED":
      return "warning";
    default:
      return "neutral";
  }
}