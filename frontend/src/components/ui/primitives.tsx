import { forwardRef } from "react";
import type {
  ButtonHTMLAttributes,
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from "react";
import { AlertCircle, Loader2 } from "lucide-react";
import { badgeTone, cn } from "./cn";
import type { Tone } from "./cn";

// ------------------------------------------------------------------ buttons

type Variant = "primary" | "secondary" | "ghost" | "danger" | "outline";
type Size = "sm" | "md";

const variants: Record<Variant, string> = {
  primary:
    "bg-ink-900 text-white hover:bg-ink-800 focus-visible:ring-ink-900/20 disabled:bg-ink-400",
  secondary:
    "bg-brand-500 text-white hover:bg-brand-600 focus-visible:ring-brand-500/30 disabled:bg-brand-300",
  ghost:
    "bg-transparent text-ink-600 hover:bg-sand-100 hover:text-ink-900 focus-visible:ring-ink-900/10",
  danger:
    "bg-rose-600 text-white hover:bg-rose-700 focus-visible:ring-rose-500/30 disabled:bg-rose-300",
  outline:
    "border border-sand-300 bg-white text-ink-800 hover:border-sand-400 hover:bg-sand-50 focus-visible:ring-ink-900/10",
};

const sizes: Record<Size, string> = {
  sm: "h-8 px-3 text-[13px] gap-1.5",
  md: "h-10 px-4 text-sm gap-2",
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  icon?: ReactNode;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "primary", size = "md", loading, icon, className, children, disabled, ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      className={cn(
        "inline-flex items-center justify-center rounded-lg font-medium transition focus-visible:outline-none focus-visible:ring-4 disabled:cursor-not-allowed",
        variants[variant],
        sizes[size],
        className,
      )}
      disabled={disabled || loading}
      {...props}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : icon}
      {children}
    </button>
  );
});

// ------------------------------------------------------------------- inputs

interface FieldProps {
  label?: string;
  htmlFor?: string;
  error?: string;
  hint?: string;
  className?: string;
  children: ReactNode;
}

export function Field({ label, htmlFor, error, hint, className, children }: FieldProps) {
  return (
    <div className={cn("min-w-0", className)}>
      {label && (
        <label className="label" htmlFor={htmlFor}>
          {label}
        </label>
      )}
      {children}
      {error ? (
        <p className="mt-1.5 text-xs font-medium text-rose-600">{error}</p>
      ) : hint ? (
        <p className="mt-1.5 text-xs text-ink-400">{hint}</p>
      ) : null}
    </div>
  );
}

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...props }, ref) {
    return <input ref={ref} className={cn("input", className)} {...props} />;
  },
);

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  function Textarea({ className, rows = 3, ...props }, ref) {
    return <textarea ref={ref} rows={rows} className={cn("input resize-y", className)} {...props} />;
  },
);

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  function Select({ className, children, ...props }, ref) {
    return (
      <select ref={ref} className={cn("input appearance-none pr-9", className)} {...props}>
        {children}
      </select>
    );
  },
);

// ------------------------------------------------------------------- badges

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold tracking-wide",
        badgeTone(tone),
      )}
    >
      {children}
    </span>
  );
}

// ------------------------------------------------------------------- layout

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn("card", className)}>{children}</div>;
}

export function SectionCard({
  title,
  description,
  actions,
  className,
  bodyClassName,
  children,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
  bodyClassName?: string;
  children: ReactNode;
}) {
  return (
    <section className={cn("card overflow-hidden", className)}>
      <header className="flex flex-wrap items-start justify-between gap-3 border-b border-sand-200 px-5 py-4">
        <div className="min-w-0">
          <h2 className="font-display text-[15px] font-semibold text-ink-900">{title}</h2>
          {description && <p className="mt-0.5 text-[13px] text-ink-500">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </header>
      <div className={cn("p-5", bodyClassName)}>{children}</div>
    </section>
  );
}

export function PageHeader({
  kicker,
  title,
  description,
  actions,
}: {
  kicker?: string;
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        {kicker && <p className="kicker mb-1.5">{kicker}</p>}
        <h1 className="font-display text-[26px] font-semibold leading-tight tracking-tight text-ink-950 sm:text-[30px]">
          {title}
        </h1>
        {description && <p className="mt-1.5 max-w-2xl text-sm text-ink-500">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

// -------------------------------------------------------------------- state

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn("h-5 w-5 animate-spin text-ink-400", className)} />;
}

export function PageLoader({ label = "Loading" }: { label?: string }) {
  return (
    <div className="flex min-h-[40vh] flex-col items-center justify-center gap-3 text-sm text-ink-500">
      <Spinner className="h-6 w-6" />
      {label}…
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("animate-pulse rounded-lg bg-sand-200/80", className)} />;
}

/**
 * Error surface for loads and failed actions. A rejected form is not a system failure: when the
 * message is a validation rejection the default title points at the fields instead, and the detail
 * is shown once here while each field carries its own message.
 */
export function ErrorBanner({ title, message }: { title?: string; message: string }) {
  const validation = /invalid|must not be blank|must be|is required|remaining balance|already/i.test(message);
  const heading = title ?? (validation ? "Check the highlighted fields" : "Something went wrong");
  return (
    <div
      role="alert"
      className="flex items-start gap-3 rounded-lg border border-rose-100 bg-rose-50 px-4 py-3 text-sm text-rose-700"
    >
      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
      <div>
        <p className="font-semibold">{heading}</p>
        <p className="mt-0.5 text-rose-600">{message}</p>
      </div>
    </div>
  );
}

export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-14 text-center">
      {icon && (
        <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-xl2 bg-sand-100 text-ink-400">
          {icon}
        </div>
      )}
      <h3 className="font-display text-[15px] font-semibold text-ink-900">{title}</h3>
      {description && <p className="mt-1 max-w-sm text-sm text-ink-500">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

// -------------------------------------------------------------------- misc

export function Avatar({ name, className }: { name: string; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-ink-900 text-[12px] font-semibold text-white",
        className,
      )}
    >
      {(name || "?").trim().charAt(0).toUpperCase()}
    </span>
  );
}

export function KeyValue({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">{label}</dt>
      <dd className="mt-1 text-sm text-ink-800">{children}</dd>
    </div>
  );
}