import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { AlertTriangle, ArrowUpRight, BarChart3, RefreshCw, TrendingDown, TrendingUp } from "lucide-react";
import { analyticsApi, type BreakdownRow, type MetricValue } from "@/api/analytics";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  PageHeader,
  SectionCard,
  Select,
  Skeleton,
} from "@/components/ui/primitives";
import { StatCard, Tabs } from "@/components/ui/misc";
import { useToast } from "@/components/ui/toast";
import { formatMoney, formatPercent } from "@/lib/format";

type Dimension = "LOCATION" | "SERVICE" | "CUSTOMER" | "TECHNICIAN";
type Preset = "30" | "90" | "quarter" | "ytd";

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

function presetRange(preset: Preset): { from: string; to: string } {
  const today = new Date();
  const to = isoDate(today);
  if (preset === "ytd") {
    return { from: isoDate(new Date(Date.UTC(today.getUTCFullYear(), 0, 1))), to };
  }
  if (preset === "quarter") {
    return { from: isoDate(new Date(today.getTime() - 89 * 86_400_000)), to };
  }
  if (preset === "90") {
    return { from: isoDate(new Date(today.getTime() - 59 * 86_400_000)), to };
  }
  return { from: isoDate(new Date(today.getTime() - 29 * 86_400_000)), to };
}

const DIMENSION_LABELS: Record<Dimension, string> = {
  LOCATION: "Locations",
  SERVICE: "Services",
  CUSTOMER: "Customers",
  TECHNICIAN: "Team",
};

/** A metric is either a number or an explicit "why not". */
function MetricTile({ metric, currency }: { metric: MetricValue; currency: string }) {
  const value = metric.available && metric.value !== null && metric.value !== undefined
    ? metric.unit === "CURRENCY"
      ? formatMoney(metric.value, currency)
      : metric.unit === "PERCENT"
        ? `${metric.value.toFixed(1)}%`
        : metric.unit === "MINUTES"
          ? `${Math.round(metric.value)} min`
          : Math.round(metric.value).toLocaleString()
    : null;

  const delta = metric.changePercent;
  return (
    <StatCard
      label={metric.label}
      value={value ?? <span className="text-[17px] text-ink-400">Not available</span>}
      hint={
        value === null ? (
          <span className="text-ink-400">{metric.reason ?? "Waiting for source data"}</span>
        ) : delta === null || delta === undefined ? (
          <span className="text-ink-400">No comparable prior period</span>
        ) : (
          <span className={delta >= 0 ? "font-medium text-mint-600" : "font-medium text-rose-600"}>
            {delta >= 0 ? "▲" : "▼"} {Math.abs(delta).toFixed(1)}% vs previous period
          </span>
        )
      }
    />
  );
}

export function Analytics() {
  const { tenant, hasRole } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const canRefresh = hasRole("OWNER", "ADMIN", "MANAGER");
  const currency = tenant?.currency ?? "USD";

  const [preset, setPreset] = useState<Preset>("30");
  const [locationId, setLocationId] = useState("");
  const [dimension, setDimension] = useState<Dimension>("LOCATION");
  const range = useMemo(() => presetRange(preset), [preset]);

  const overview = useQuery({
    queryKey: ["analytics", "overview", range, locationId],
    queryFn: () => analyticsApi.overview({ ...range, locationId: locationId || undefined }),
  });

  const trend = useQuery({
    queryKey: ["analytics", "trend", range, locationId],
    queryFn: () => analyticsApi.trend({
      metric: "revenue",
      granularity: preset === "30" ? "day" : "week",
      ...range,
      locationId: locationId || undefined,
    }),
  });

  const breakdown = useQuery({
    queryKey: ["analytics", "breakdown", dimension, range],
    queryFn: () => analyticsApi.breakdown({ dimension, ...range }),
  });

  const jobs = useQuery({
    queryKey: ["analytics", "jobs", range, locationId],
    queryFn: () => analyticsApi.jobs({ ...range, locationId: locationId || undefined }),
  });

  const ageing = useQuery({ queryKey: ["analytics", "ageing"], queryFn: analyticsApi.ageing });
  const locations = useQuery({ queryKey: ["analytics", "locations"], queryFn: analyticsApi.locations });

  const refresh = useMutation({
    mutationFn: () => analyticsApi.refresh(range),
    onSuccess: async () => {
      toast.push({ title: "Analytics refreshed", description: "Metrics rebuilt from source records." });
      await queryClient.invalidateQueries({ queryKey: ["analytics"] });
    },
    onError: (caught) =>
      toast.push({ tone: "error", title: "Refresh failed", description: errorMessage(caught) }),
  });

  const peak = Math.max(1, ...(trend.data?.points ?? []).map((point) => point.value));

  return (
    <div className="space-y-6">
      <PageHeader
        title="Business analytics"
        description="Revenue, cost, collections and profitability — every figure traces back to the records behind it."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Select
              aria-label="Business location"
              className="w-full sm:w-[190px]"
              value={locationId}
              onChange={(event) => setLocationId(event.target.value)}
            >
              <option value="">All locations</option>
              {(locations.data ?? []).map((row) => (
                <option key={row.dimensions.locationId} value={row.dimensions.locationId}>
                  {row.label}
                </option>
              ))}
            </Select>
            <Select
              aria-label="Date range"
              className="w-full sm:w-[170px]"
              value={preset}
              onChange={(event) => setPreset(event.target.value as Preset)}
            >
              <option value="30">Last 30 days</option>
              <option value="90">Last 60 days</option>
              <option value="quarter">Last 90 days</option>
              <option value="ytd">Year to date</option>
            </Select>
            {canRefresh && (
              <Button
                variant="outline"
                icon={<RefreshCw className="h-4 w-4" />}
                loading={refresh.isPending}
                onClick={() => refresh.mutate()}
              >
                Refresh
              </Button>
            )}
          </div>
        }
      />

      {overview.isError && (
        <ErrorBanner title="Couldn’t load analytics" message={errorMessage(overview.error)} />
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {overview.isLoading
          ? Array.from({ length: 8 }).map((_, index) => <Skeleton key={index} className="h-32 w-full" />)
          : (overview.data?.metrics ?? []).map((metric) => (
              <MetricTile key={metric.key} metric={metric} currency={overview.data?.currency ?? currency} />
            ))}
      </div>

      {overview.data && (
        <p className="text-[11px] leading-relaxed text-ink-400">
          {overview.data.notes.join(" ")}
          {overview.data.refreshedAt
            ? ` Rollup refreshed ${new Date(overview.data.refreshedAt).toLocaleString()}.`
            : ""}
        </p>
      )}

      <div className="grid gap-4 lg:grid-cols-5">
        <SectionCard className="lg:col-span-3" title="Revenue trend" description="Issued invoice value per bucket.">
          {trend.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : !trend.data?.available ? (
            <p className="text-sm text-ink-500">{trend.data?.reason ?? "No revenue in this period."}</p>
          ) : trend.data.points.length === 0 ? (
            <EmptyState
              icon={<BarChart3 className="h-5 w-5" />}
              title="No revenue in this period"
              description="Issue an invoice and the trend will populate on the next refresh."
            />
          ) : (
            <div className="flex h-40 items-end gap-1">
              {trend.data.points.map((point) => (
                <div key={point.bucket} className="group flex flex-1 flex-col items-center justify-end">
                  <span className="mb-1 hidden text-[10px] text-ink-500 group-hover:block">
                    {formatMoney(point.value, trend.data!.currency)}
                  </span>
                  <div
                    className="w-full rounded-t bg-brand-400 transition group-hover:bg-brand-500"
                    style={{ height: `${Math.max(2, (point.value / peak) * 100)}%` }}
                    title={`${point.bucket}: ${formatMoney(point.value, trend.data!.currency)}`}
                  />
                  <span className="mt-1 hidden w-full truncate text-center text-[9px] text-ink-400 sm:block">
                    {point.bucket.slice(5)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </SectionCard>

        <SectionCard
          className="lg:col-span-2"
          title="Receivables ageing"
          description="Open balances by how long they are past due."
        >
          {ageing.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : (
            <div className="space-y-3">
              <div className="flex items-baseline justify-between">
                <p className="text-sm text-ink-500">Outstanding</p>
                <p className="font-display text-lg font-semibold text-ink-950">
                  {formatMoney(ageing.data?.outstanding, ageing.data?.currency ?? currency)}
                </p>
              </div>
              <div className="flex items-baseline justify-between">
                <p className="text-sm text-ink-500">Overdue</p>
                <p className="font-display text-lg font-semibold text-rose-600">
                  {formatMoney(ageing.data?.overdue, ageing.data?.currency ?? currency)}
                </p>
              </div>
              <ul className="divide-y divide-sand-100">
                {(ageing.data?.buckets ?? []).map((bucket) => (
                  <li key={bucket.bucket} className="flex items-center justify-between py-1.5 text-[13px]">
                    <span className="text-ink-600">{bucket.bucket === "current" ? "Not due" : `${bucket.bucket} days`}</span>
                    <span className="text-ink-900">
                      {formatMoney(bucket.amount, ageing.data?.currency ?? currency)}
                      <span className="ml-2 text-[11px] text-ink-400">{bucket.invoices} inv.</span>
                    </span>
                  </li>
                ))}
              </ul>
              <Link
                to="/invoices"
                className="inline-flex items-center gap-1 text-[12px] font-medium text-brand-700 hover:text-brand-800"
              >
                Review invoices <ArrowUpRight className="h-3 w-3" />
              </Link>
            </div>
          )}
        </SectionCard>
      </div>

      <Card className="p-0">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-sand-200 p-4">
          <div>
            <p className="font-display text-[15px] font-semibold text-ink-900">Breakdown</p>
            <p className="text-[12px] text-ink-500">Revenue and cost by dimension, for the selected period.</p>
          </div>
          <Tabs
            value={dimension}
            onChange={(value) => setDimension(value)}
            items={(Object.keys(DIMENSION_LABELS) as Dimension[]).map((value) => ({
              value,
              label: DIMENSION_LABELS[value],
            }))}
          />
        </div>

        {breakdown.isLoading ? (
          <div className="p-4">
            <Skeleton className="h-32 w-full" />
          </div>
        ) : breakdown.isError ? (
          <div className="p-4">
            <ErrorBanner title="Couldn’t load the breakdown" message={errorMessage(breakdown.error)} />
          </div>
        ) : (breakdown.data?.rows.length ?? 0) === 0 ? (
          <div className="p-6 text-center text-sm text-ink-500">No activity in this period.</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-sand-200 text-left text-[11px] uppercase tracking-[0.1em] text-ink-400">
                  <th className="px-4 py-2 font-semibold">{DIMENSION_LABELS[dimension]}</th>
                  <th className="px-4 py-2 text-right font-semibold">Revenue</th>
                  <th className="px-4 py-2 text-right font-semibold">Cost</th>
                  <th className="px-4 py-2 text-right font-semibold">Profit</th>
                  <th className="px-4 py-2 text-right font-semibold">
                    {dimension === "TECHNICIAN" ? "Minutes" : "Jobs"}
                  </th>
                </tr>
              </thead>
              <tbody>
                {(breakdown.data?.rows ?? []).map((row: BreakdownRow, index: number) => (
                  <tr
                    key={`${row.label}-${row.id ?? "unassigned"}-${index}`}
                    className="border-b border-sand-100 last:border-0"
                  >
                    <td className="px-4 py-2 text-ink-800">{row.label}</td>
                    <td className="px-4 py-2 text-right text-ink-900">
                      {formatMoney(row.revenue, breakdown.data!.currency)}
                    </td>
                    <td className="px-4 py-2 text-right text-ink-600">
                      {row.cost === null || row.cost === undefined
                        ? "—"
                        : formatMoney(row.cost, breakdown.data!.currency)}
                    </td>
                    <td className="px-4 py-2 text-right">
                      {row.profitAvailable && row.profit !== null && row.profit !== undefined ? (
                        <span className={row.profit >= 0 ? "text-mint-700" : "text-rose-600"}>
                          {formatMoney(row.profit, breakdown.data!.currency)}
                        </span>
                      ) : (
                        <span className="text-ink-400" title="Needs complete cost inputs">—</span>
                      )}
                    </td>
                    <td className="px-4 py-2 text-right text-ink-600">
                      {dimension === "TECHNICIAN" ? row.minutes ?? "—" : row.count ?? "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {(breakdown.data?.notes ?? []).length > 0 && (
          <p className="border-t border-sand-100 px-4 py-2 text-[11px] text-ink-400">
            {breakdown.data?.notes.join(" ")}
          </p>
        )}
      </Card>

      <Card className="p-0">
        <div className="border-b border-sand-200 p-4">
          <p className="font-display text-[15px] font-semibold text-ink-900">Delivered job profitability</p>
          <p className="text-[12px] text-ink-500">
            Revenue minus direct cost per job. Jobs with missing inputs are excluded from the totals.
          </p>
        </div>
        {jobs.isLoading ? (
          <div className="p-4">
            <Skeleton className="h-32 w-full" />
          </div>
        ) : (jobs.data?.rows.length ?? 0) === 0 ? (
          <div className="p-6 text-center text-sm text-ink-500">No completed jobs in this period.</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-sand-200 text-left text-[11px] uppercase tracking-[0.1em] text-ink-400">
                  <th className="px-4 py-2 font-semibold">Job</th>
                  <th className="px-4 py-2 font-semibold">Delivered</th>
                  <th className="px-4 py-2 text-right font-semibold">Revenue</th>
                  <th className="px-4 py-2 text-right font-semibold">Cost</th>
                  <th className="px-4 py-2 text-right font-semibold">Profit</th>
                  <th className="px-4 py-2 text-right font-semibold">Margin</th>
                </tr>
              </thead>
              <tbody>
                {(jobs.data?.rows ?? []).map((row) => (
                  <tr key={row.id} className="border-b border-sand-100 last:border-0">
                    <td className="px-4 py-2">
                      <Link
                        to={`/schedule?job=${row.id}`}
                        className="font-medium text-brand-700 hover:text-brand-800"
                      >
                        {row.number}
                      </Link>
                      <p className="text-[11px] text-ink-500">{row.customerName}</p>
                    </td>
                    <td className="px-4 py-2 text-ink-600">{row.deliveredOn}</td>
                    <td className="px-4 py-2 text-right text-ink-900">
                      {formatMoney(row.revenue, jobs.data!.currency)}
                    </td>
                    <td className="px-4 py-2 text-right text-ink-600">
                      {formatMoney(row.cost, jobs.data!.currency)}
                    </td>
                    <td className="px-4 py-2 text-right">
                      {row.complete ? (
                        <span className={(row.profit ?? 0) >= 0 ? "text-mint-700" : "text-rose-600"}>
                          {formatMoney(row.profit, jobs.data!.currency)}
                        </span>
                      ) : (
                        <Badge tone="warning">
                          <span className="inline-flex items-center gap-1">
                            <AlertTriangle className="h-3 w-3" /> Incomplete
                          </span>
                        </Badge>
                      )}
                    </td>
                    <td className="px-4 py-2 text-right text-ink-600">
                      {row.marginPercent === null || row.marginPercent === undefined
                        ? "—"
                        : formatPercent(row.marginPercent / 100)}
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="bg-sand-50 text-[13px] font-medium">
                  <td className="px-4 py-2" colSpan={2}>
                    Total ({jobs.data?.rows.length ?? 0} jobs, {jobs.data?.incompleteCount ?? 0} excluded)
                  </td>
                  <td className="px-4 py-2 text-right">
                    {formatMoney(jobs.data?.totalRevenue, jobs.data?.currency ?? currency)}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {formatMoney(jobs.data?.totalCost, jobs.data?.currency ?? currency)}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {formatMoney(jobs.data?.totalProfit, jobs.data?.currency ?? currency)}
                  </td>
                  <td />
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </Card>

      {(jobs.data?.notes ?? []).length > 0 && (
        <p className="text-[11px] text-ink-400">{jobs.data?.notes.join(" ")}</p>
      )}

      <p className="flex items-center gap-1.5 text-[11px] text-ink-400">
        {overview.data?.metrics.find((metric) => metric.key === "revenue")?.changePercent != null
          && (overview.data.metrics.find((metric) => metric.key === "revenue")!.changePercent! >= 0
            ? <TrendingUp className="h-3.5 w-3.5" />
            : <TrendingDown className="h-3.5 w-3.5" />)}
        Totals include every location unless a location filter is applied.
      </p>
    </div>
  );
}
