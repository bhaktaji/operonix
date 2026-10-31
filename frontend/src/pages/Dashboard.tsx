import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  ArrowUpRight,
  CalendarDays,
  CircleDollarSign,
  Clock,
  Plus,
  Receipt,
  Sparkles,
  TrendingUp,
  Users,
} from "lucide-react";
import { reportsApi } from "@/api/endpoints";
import type { Bucket, Job } from "@/api/types";
import { useAuth } from "@/auth/AuthProvider";
import { errorMessage } from "@/api/client";
import { Badge, Button, Card, EmptyState, ErrorBanner, PageHeader, SectionCard, Skeleton } from "@/components/ui/primitives";
import { DeltaHint, StatCard } from "@/components/ui/misc";
import { statusTone } from "@/components/ui/cn";
import { formatDay, formatMoney, formatTime, labelize } from "@/lib/format";

function greeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return "Good morning";
  if (hour < 18) return "Good afternoon";
  return "Good evening";
}

function RevenueChart({ buckets, currency }: { buckets: Bucket[]; currency: string }) {
  const width = 640;
  const height = 170;
  const padding = 10;
  const max = Math.max(...buckets.map((bucket) => bucket.revenue), 1);
  const step = buckets.length > 1 ? (width - padding * 2) / (buckets.length - 1) : 0;

  const points = buckets.map((bucket, index) => {
    const x = padding + index * step;
    const y = height - padding - (bucket.revenue / max) * (height - padding * 2 - 20);
    return { x, y, bucket };
  });

  const line = points.map((point) => `${point.x},${point.y}`).join(" ");
  const area = `M${padding},${height - padding} L${line.split(" ").join(" L")} L${points[points.length - 1]?.x ?? padding},${height - padding} Z`;
  const total = buckets.reduce((sum, bucket) => sum + bucket.revenue, 0);

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className="font-display text-2xl font-semibold tracking-tight text-ink-950">
            {formatMoney(total, currency)}
          </p>
          <p className="text-[12px] text-ink-400">Collected in the last 14 days</p>
        </div>
        <Badge tone="success">
          <TrendingUp className="h-3 w-3" /> Cash basis
        </Badge>
      </div>
      {total === 0 ? (
        <div className="flex h-[170px] items-center justify-center rounded-lg border border-dashed border-sand-300 text-sm text-ink-400">
          Payments will chart here once invoices are paid.
        </div>
      ) : (
        <svg viewBox={`0 0 ${width} ${height}`} className="h-[170px] w-full" role="img"
             aria-label="Revenue collected over the last 14 days">
          <defs>
            <linearGradient id="revenueFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#5b63ec" stopOpacity="0.24" />
              <stop offset="100%" stopColor="#5b63ec" stopOpacity="0.02" />
            </linearGradient>
          </defs>
          {[0.25, 0.5, 0.75].map((fraction) => (
            <line
              key={fraction}
              x1={padding}
              x2={width - padding}
              y1={height - padding - fraction * (height - padding * 2 - 20)}
              y2={height - padding - fraction * (height - padding * 2 - 20)}
              stroke="#e8ecf3"
              strokeDasharray="3 4"
            />
          ))}
          <path d={area} fill="url(#revenueFill)" />
          <polyline points={line} fill="none" stroke="#5b63ec" strokeWidth="2.2" strokeLinejoin="round" strokeLinecap="round" />
          {points.map((point) => (
            <circle key={point.bucket.key} cx={point.x} cy={point.y} r="2.4" fill="#3a3bab">
              <title>{`${point.bucket.label}: ${formatMoney(point.bucket.revenue, currency)}`}</title>
            </circle>
          ))}
        </svg>
      )}
      <div className="mt-1 flex justify-between text-[10px] text-ink-400">
        <span>{buckets[0]?.label}</span>
        <span>{buckets[Math.floor(buckets.length / 2)]?.label}</span>
        <span>{buckets[buckets.length - 1]?.label}</span>
      </div>
    </div>
  );
}

function UpcomingJob({ job, timezone }: { job: Job; timezone: string }) {
  return (
    <li className="flex items-center gap-3 py-3">
      <div className="flex w-16 shrink-0 flex-col text-center">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">
          {formatDay(job.scheduledStart, timezone)}
        </span>
        <span className="text-[13px] font-semibold text-ink-900">
          {formatTime(job.scheduledStart, timezone)}
        </span>
      </div>
      <div className="min-w-0 flex-1 border-l border-sand-200 pl-3">
        <p className="truncate text-sm font-medium text-ink-900">{job.title}</p>
        <p className="truncate text-[12px] text-ink-500">
          {job.customer?.name}
          {job.technicians.length > 0 && ` · ${job.technicians.map((tech) => tech.fullName).join(", ")}`}
        </p>
      </div>
      <Badge tone={statusTone(job.status)}>{labelize(job.status)}</Badge>
    </li>
  );
}

export function Dashboard() {
  const { tenant, user } = useAuth();
  const currency = tenant?.currency ?? "USD";
  const timezone = tenant?.timezone ?? "UTC";
  const isField = user?.role === "TECHNICIAN";

  const dashboard = useQuery({ queryKey: ["dashboard"], queryFn: reportsApi.dashboard });

  if (dashboard.isError) {
    return (
      <div>
        <PageHeader kicker="Overview" title="Dashboard" />
        <ErrorBanner message={errorMessage(dashboard.error)} />
        <div className="mt-4">
          <Button variant="outline" onClick={() => dashboard.refetch()}>Try again</Button>
        </div>
      </div>
    );
  }

  const data = dashboard.data;
  const loading = dashboard.isLoading;

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Overview"
        title={`${greeting()}, ${user?.fullName?.split(" ")[0] ?? "there"}`}
        description={isField
          ? "Your assigned work and schedule for today."
          : "A live view of revenue, collections and the work ahead."}
        actions={
          !isField ? (
            <>
              <Link to="/quotes/new">
                <Button variant="outline" icon={<Plus className="h-4 w-4" />}>New quotation</Button>
              </Link>
              <Link to="/schedule">
                <Button variant="secondary" icon={<CalendarDays className="h-4 w-4" />}>Schedule a job</Button>
              </Link>
            </>
          ) : (
            <Link to="/schedule">
              <Button variant="secondary" icon={<CalendarDays className="h-4 w-4" />}>Open my schedule</Button>
            </Link>
          )
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {!isField && (
          <>
            <StatCard
              label="Revenue this month"
              value={loading ? "—" : formatMoney(data?.revenueThisMonth ?? 0, currency)}
              hint={<DeltaHint delta={data?.revenueDeltaPct} />}
              icon={<CircleDollarSign className="h-4 w-4" />}
              tone="success"
              loading={loading}
            />
            <StatCard
              label="Outstanding"
              value={loading ? "—" : formatMoney(data?.outstandingTotal ?? 0, currency)}
              hint={<span>{formatMoney(data?.overdueTotal ?? 0, currency)} overdue</span>}
              icon={<Receipt className="h-4 w-4" />}
              tone="warning"
              loading={loading}
            />
          </>
        )}
        <StatCard
          label="Jobs today"
          value={loading ? "—" : String(data?.jobsToday ?? 0)}
          hint={`${data?.jobsThisWeek ?? 0} scheduled this week`}
          icon={<CalendarDays className="h-4 w-4" />}
          tone="info"
          loading={loading}
        />
        <StatCard
          label={isField ? "Jobs this week" : "Active customers"}
          value={loading ? "—" : String(isField ? data?.jobsThisWeek ?? 0 : data?.activeCustomers ?? 0)}
          hint={isField ? "Across all assigned work" : "Active and recently active accounts"}
          icon={isField ? <Clock className="h-4 w-4" /> : <Users className="h-4 w-4" />}
          tone="neutral"
          loading={loading}
        />
      </div>

      <div className="mt-4 grid gap-4 xl:grid-cols-3">
        {!isField && (
          <SectionCard
            className="xl:col-span-2"
            title="Cash collected"
            description="Payments recorded against invoices, by day"
            actions={<Badge tone="neutral">Last 14 days</Badge>}
          >
            {loading ? (
              <Skeleton className="h-[220px] w-full" />
            ) : (
              <RevenueChart buckets={data?.revenueByDay ?? []} currency={currency} />
            )}
          </SectionCard>
        )}

        <div className={isField ? "xl:col-span-3 grid gap-4 lg:grid-cols-2" : "flex flex-col gap-4"}>
          {!isField && (
            <Card className="p-5">
              <h2 className="font-display text-[15px] font-semibold text-ink-900">Quotation pipeline</h2>
              <div className="mt-4 space-y-3">
                <div className="flex items-baseline justify-between">
                  <span className="text-[13px] text-ink-500">Awaiting a decision</span>
                  <span className="font-display text-xl font-semibold text-ink-950">
                    {data?.quotesPending ?? 0}
                  </span>
                </div>
                <div className="flex items-baseline justify-between">
                  <span className="text-[13px] text-ink-500">Acceptance rate</span>
                  <span className="font-display text-xl font-semibold text-ink-950">
                    {data?.quoteAcceptanceRate == null ? "—" : `${data.quoteAcceptanceRate.toFixed(0)}%`}
                  </span>
                </div>
                <div className="h-1.5 overflow-hidden rounded-full bg-sand-200">
                  <div
                    className="h-full rounded-full bg-brand-500 transition-all"
                    style={{ width: `${Math.min(100, data?.quoteAcceptanceRate ?? 0)}%` }}
                  />
                </div>
                <Link
                  to="/quotes"
                  className="inline-flex items-center gap-1 pt-1 text-[13px] font-medium text-brand-700 hover:text-brand-800"
                >
                  Go to quotations <ArrowUpRight className="h-3.5 w-3.5" />
                </Link>
              </div>
            </Card>
          )}

          {!isField && (
            <SectionCard title="Top services" description="Invoiced in the last 30 days">
              {loading ? (
                <Skeleton className="h-28 w-full" />
              ) : (data?.topServices.length ?? 0) === 0 ? (
                <p className="py-4 text-sm text-ink-400">No invoiced services yet this month.</p>
              ) : (
                <ul className="space-y-3">
                  {data?.topServices.map((service) => {
                    const max = Math.max(...data.topServices.map((item) => item.revenue), 1);
                    return (
                      <li key={service.label}>
                        <div className="flex items-baseline justify-between gap-3 text-[13px]">
                          <span className="truncate text-ink-700">{service.label}</span>
                          <span className="shrink-0 font-medium text-ink-900">
                            {formatMoney(service.revenue, currency)}
                          </span>
                        </div>
                        <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-sand-100">
                          <div
                            className="h-full rounded-full bg-brand-300"
                            style={{ width: `${Math.max(6, (service.revenue / max) * 100)}%` }}
                          />
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </SectionCard>
          )}
        </div>
      </div>

      <SectionCard
        className="mt-4"
        title="Upcoming work"
        description="The next scheduled jobs across the team"
        actions={
          <Link to="/schedule" className="inline-flex items-center gap-1 text-[13px] font-medium text-brand-700 hover:text-brand-800">
            Full schedule <ArrowUpRight className="h-3.5 w-3.5" />
          </Link>
        }
        bodyClassName="px-5 py-2"
      >
        {loading ? (
          <div className="space-y-3 py-3">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : (data?.upcomingJobs.length ?? 0) === 0 ? (
          <EmptyState
            icon={<Sparkles className="h-5 w-5" />}
            title="Nothing scheduled yet"
            description="Create a job directly, or convert an accepted quotation into scheduled work."
            action={
              <Link to="/schedule">
                <Button variant="secondary" size="sm" icon={<CalendarDays className="h-3.5 w-3.5" />}>
                  Open schedule
                </Button>
              </Link>
            }
          />
        ) : (
          <ul className="divide-y divide-sand-100">
            {data?.upcomingJobs.map((job) => (
              <UpcomingJob key={job.id} job={job} timezone={timezone} />
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  );
}