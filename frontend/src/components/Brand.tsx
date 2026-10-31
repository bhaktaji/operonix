import { cn } from "@/components/ui/cn";

/**
 * The Operonix mark: a ring with a ledger line through it — an operating loop, not an industry symbol.
 * It is deliberately trade-agnostic so the same mark works for a plumber, a rental desk and a clinic.
 */
export function BrandGlyph({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" fill="none" className={className} aria-hidden="true">
      <circle cx="16" cy="16" r="10.2" stroke="currentColor" strokeWidth="2" opacity="0.9" />
      <path d="M9.4 16h13.2" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      <circle cx="16" cy="16" r="2.1" fill="currentColor" />
    </svg>
  );
}

export function BrandMark({
  size = "md",
  onDark = false,
  withWordmark = true,
  tagline = false,
  className,
}: {
  size?: "sm" | "md" | "lg";
  onDark?: boolean;
  withWordmark?: boolean;
  tagline?: boolean;
  className?: string;
}) {
  const box = size === "sm" ? "h-8 w-8 rounded-[9px]" : size === "lg" ? "h-12 w-12 rounded-[14px]" : "h-9 w-9 rounded-[10px]";
  const glyph = size === "sm" ? "h-4 w-4" : size === "lg" ? "h-6 w-6" : "h-[18px] w-[18px]";
  const word = size === "lg" ? "text-[20px]" : size === "sm" ? "text-[13px]" : "text-[15px]";

  return (
    <div className={cn("flex items-center gap-2.5", className)}>
      <span
        className={cn(
          "flex shrink-0 items-center justify-center ring-1 ring-inset",
          box,
          onDark
            ? "bg-white/[0.06] text-brand-200 ring-white/15"
            : "bg-ink-950 text-brand-300 ring-ink-900/10",
        )}
      >
        <BrandGlyph className={glyph} />
      </span>
      {withWordmark && (
        <span className="min-w-0">
          <span
            className={cn(
              "block font-display font-semibold uppercase leading-none tracking-[0.22em]",
              word,
              onDark ? "text-white" : "text-ink-950",
            )}
          >
            Operonix
          </span>
          {tagline && (
            <span
              className={cn(
                "mt-1 block text-[11px] leading-none",
                onDark ? "text-ink-400" : "text-ink-500",
              )}
            >
              The Operating Platform for Growing Businesses
            </span>
          )}
        </span>
      )}
    </div>
  );
}