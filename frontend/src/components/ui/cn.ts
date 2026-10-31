export function cn(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(" ");
}

export type Tone = "neutral" | "success" | "warning" | "danger" | "info" | "brand";

const toneClasses: Record<Tone, string> = {
  neutral: "bg-sand-100 text-ink-600 border-sand-200",
  success: "bg-mint-50 text-mint-700 border-mint-100",
  warning: "bg-amber-50 text-amber-600 border-amber-200",
  danger: "bg-rose-50 text-rose-700 border-rose-100",
  info: "bg-brand-50 text-brand-700 border-brand-100",
  brand: "bg-ink-950 text-white border-ink-950",
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