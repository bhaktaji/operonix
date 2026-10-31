import type { ReactNode } from "react";
import { CalendarCheck, CreditCard, Sparkles, Timer } from "lucide-react";

const highlights = [
  {
    icon: Timer,
    title: "Quote in minutes",
    body: "Catalog-priced lines, taxes and discounts calculated for you.",
  },
  {
    icon: CalendarCheck,
    title: "No double-bookings",
    body: "Technician schedules are checked before a job is ever saved.",
  },
  {
    icon: CreditCard,
    title: "Get paid faster",
    body: "Invoice straight from the job and track every payment and balance.",
  },
];

export function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="grid min-h-screen lg:grid-cols-[1.05fr_1fr]">
      <div className="relative hidden overflow-hidden bg-ink-950 lg:flex lg:flex-col lg:justify-between lg:p-12">
        <div
          className="pointer-events-none absolute inset-0 opacity-[0.35]"
          style={{
            backgroundImage:
              "radial-gradient(circle at 20% 15%, rgba(53,194,157,0.28), transparent 45%), radial-gradient(circle at 85% 80%, rgba(231,155,50,0.18), transparent 40%)",
          }}
        />
        <div
          className="pointer-events-none absolute inset-0 opacity-[0.06]"
          style={{
            backgroundImage:
              "linear-gradient(to right, white 1px, transparent 1px), linear-gradient(to bottom, white 1px, transparent 1px)",
            backgroundSize: "56px 56px",
          }}
        />

        <div className="relative flex items-center gap-2.5">
          <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-500/15 text-brand-300 ring-1 ring-inset ring-brand-400/30">
            <Sparkles className="h-5 w-5" />
          </span>
          <span className="font-display text-lg font-semibold tracking-tight text-white">Operonix</span>
        </div>

        <div className="relative max-w-lg">
          <p className="mb-4 text-[11px] font-semibold uppercase tracking-[0.22em] text-brand-300">
            Built for cleaning businesses
          </p>
          <h1 className="font-display text-[40px] font-semibold leading-[1.08] tracking-tight text-white text-balance">
            Run the whole clean, not just the calendar.
          </h1>
          <p className="mt-4 max-w-md text-[15px] leading-relaxed text-ink-400">
            Quotes, recurring schedules, crews, invoices and payments — one calm workspace for the
            business behind every spotless room.
          </p>

          <div className="mt-9 space-y-4">
            {highlights.map((item) => (
              <div key={item.title} className="flex items-start gap-3.5">
                <span className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white/[0.06] text-brand-300 ring-1 ring-inset ring-white/10">
                  <item.icon className="h-[17px] w-[17px]" />
                </span>
                <div>
                  <p className="text-sm font-medium text-white">{item.title}</p>
                  <p className="mt-0.5 text-[13px] leading-relaxed text-ink-400">{item.body}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="relative flex items-center gap-3 text-[12px] text-ink-500">
          <span className="h-px flex-1 bg-white/10" />
          Tenant-isolated by design · audit trail included
        </div>
      </div>

      <div className="flex min-h-screen flex-col bg-sand-50">
        <div className="flex items-center gap-2 px-6 py-5 lg:hidden">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-ink-950 text-brand-300">
            <Sparkles className="h-[18px] w-[18px]" />
          </span>
          <span className="font-display text-[17px] font-semibold text-ink-950">Operonix</span>
        </div>
        <div className="flex flex-1 items-center justify-center px-5 pb-14 pt-4 sm:px-8">
          <div className="w-full max-w-[400px] animate-fade-up">{children}</div>
        </div>
      </div>
    </div>
  );
}