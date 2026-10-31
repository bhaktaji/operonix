import type { ReactNode } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "./cn";
import { Button } from "./primitives";
import type { Tone } from "./cn";

export function Tabs<T extends string>({
  items,
  value,
  onChange,
  className,
}: {
  items: { value: T; label: string; count?: number }[];
  value: T;
  onChange: (value: T) => void;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-wrap items-center gap-1 rounded-xl bg-sand-100 p-1", className)}>
      {items.map((item) => {
        const active = item.value === value;
        return (
          <button
            key={item.value}
            type="button"
            onClick={() => onChange(item.value)}
            aria-pressed={active}
            className={cn(
              "inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[13px] font-medium transition",
              active ? "bg-white text-ink-900 shadow-sm" : "text-ink-500 hover:text-ink-800",
            )}
          >
            {item.label}
            {item.count !== undefined && (
              <span
                className={cn(
                  "rounded-full px-1.5 py-0.5 text-[10px] font-semibold",
                  active ? "bg-ink-900 text-white" : "bg-sand-200 text-ink-500",
                )}
              >
                {item.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

export function Pagination({
  page,
  totalPages,
  totalElements,
  onChange,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onChange: (page: number) => void;
}) {
  if (totalPages <= 1) {
    return (
      <div className="flex items-center justify-between px-4 py-3 text-xs text-ink-400">
        <span>{totalElements} result{totalElements === 1 ? "" : "s"}</span>
      </div>
    );
  }
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-sand-200 px-4 py-3">
      <span className="text-xs text-ink-400">
        Page {page + 1} of {totalPages} · {totalElements} results
      </span>
      <div className="flex items-center gap-1.5">
        <Button size="sm" variant="outline" disabled={page <= 0} onClick={() => onChange(page - 1)}>
          <ChevronLeft className="h-3.5 w-3.5" /> Prev
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={page + 1 >= totalPages}
          onClick={() => onChange(page + 1)}
        >
          Next <ChevronRight className="h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  );
}

const statTones: Record<Tone, string> = {
  neutral: "bg-sand-100 text-ink-600",
  success: "bg-mint-50 text-mint-600",
  warning: "bg-amber-50 text-amber-600",
  danger: "bg-rose-50 text-rose-600",
  info: "bg-brand-50 text-brand-600",
  brand: "bg-ink-950 text-white",
};

export function StatCard({
  label,
  value,
  hint,
  icon,
  tone = "neutral",
  loading,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  icon?: ReactNode;
  tone?: Tone;
  loading?: boolean;
}) {
  return (
    <div className="card p-5">
      <div className="flex items-start justify-between gap-3">
        <p className="text-[12px] font-semibold uppercase tracking-[0.12em] text-ink-400">{label}</p>
        {icon && (
          <span className={cn("flex h-8 w-8 items-center justify-center rounded-lg", statTones[tone])}>
            {icon}
          </span>
        )}
      </div>
      <div className="mt-3 min-h-[32px]">
        {loading ? (
          <div className="h-8 w-28 animate-pulse rounded-md bg-sand-200" />
        ) : (
          <p className="font-display text-[26px] font-semibold leading-none tracking-tight text-ink-950">
            {value}
          </p>
        )}
      </div>
      {hint && <div className="mt-2 text-[12px] text-ink-500">{hint}</div>}
    </div>
  );
}

export function DeltaHint({ delta }: { delta: number | null | undefined }) {
  if (delta === null || delta === undefined) {
    return <span className="text-ink-400">No prior month to compare</span>;
  }
  const positive = delta >= 0;
  return (
    <span className={positive ? "font-medium text-mint-600" : "font-medium text-rose-600"}>
      {positive ? "▲" : "▼"} {Math.abs(delta).toFixed(1)}% vs last month
    </span>
  );
}