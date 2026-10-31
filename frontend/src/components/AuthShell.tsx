import type { ReactNode } from "react";
import { BrandMark } from "@/components/Brand";

const pillars = [
  {
    title: "Quote to cash, in one thread",
    body: "A quotation becomes a scheduled job, the job becomes an invoice, the invoice becomes a payment — with the trail intact.",
  },
  {
    title: "Built for any service business",
    body: "Cleaning, pest control, rentals, workshops, contractors, training. One configurable platform, not a separate app per trade.",
  },
  {
    title: "Ready for more than one country",
    body: "Country-aware currencies, date and number formats, timezones and document numbering from the first invoice.",
  },
];

export function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="grid min-h-screen lg:grid-cols-[1.08fr_1fr]">
      <div className="relative hidden overflow-hidden bg-ink-950 lg:flex lg:flex-col lg:justify-between lg:p-12">
        <div className="pointer-events-none absolute inset-0 grid-lines opacity-60" />
        <div
          className="pointer-events-none absolute -left-24 top-1/3 h-[520px] w-[520px] rounded-full opacity-70 blur-[110px]"
          style={{ background: "radial-gradient(circle, rgba(91,99,236,0.42), transparent 65%)" }}
        />
        <div
          className="pointer-events-none absolute -right-20 bottom-0 h-[380px] w-[380px] rounded-full opacity-50 blur-[120px]"
          style={{ background: "radial-gradient(circle, rgba(16,185,129,0.28), transparent 65%)" }}
        />

        <div className="relative">
          <BrandMark onDark size="md" tagline />
        </div>

        <div className="relative max-w-xl">
          <h1 className="font-display text-[42px] font-semibold leading-[1.06] tracking-tightest text-white text-balance">
            One platform for the whole business.
          </h1>
          <p className="mt-5 max-w-md text-[15px] leading-relaxed text-ink-300">
            Operonix gives growing service businesses a single operating system for customers,
            quotations, schedules, invoices and payments — wherever they trade.
          </p>

          <div className="mt-10 space-y-5">
            {pillars.map((pillar) => (
              <div key={pillar.title} className="flex items-start gap-3.5">
                <span className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-300" />
                <div>
                  <p className="text-sm font-medium text-white">{pillar.title}</p>
                  <p className="mt-1 text-[13px] leading-relaxed text-ink-400">{pillar.body}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="relative flex flex-wrap items-center gap-x-5 gap-y-2 text-[11px] uppercase tracking-[0.16em] text-ink-500">
          <span>Tenant isolation</span>
          <span className="h-1 w-1 rounded-full bg-ink-600" />
          <span>Audit trail</span>
          <span className="h-1 w-1 rounded-full bg-ink-600" />
          <span>Global by design</span>
        </div>
      </div>

      <div className="flex min-h-screen flex-col bg-white">
        <div className="flex items-center justify-between px-6 py-5 lg:hidden">
          <BrandMark size="sm" />
        </div>
        <div className="flex flex-1 items-center justify-center px-5 pb-14 pt-4 sm:px-10">
          <div className="w-full max-w-[432px] animate-fade-up">{children}</div>
        </div>
        <div className="hidden px-10 pb-6 lg:block">
          <p className="text-[11px] text-ink-400">
            © {new Date().getFullYear()} Operonix · The Operating Platform for Growing Businesses
          </p>
        </div>
      </div>
    </div>
  );
}