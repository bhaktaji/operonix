import { qs, request } from "./client";

export type MetricUnit = "CURRENCY" | "COUNT" | "MINUTES" | "PERCENT";

export interface MetricInfo {
  key: string;
  label: string;
  unit: MetricUnit;
  formula: string;
  inputs: string[];
  rolledUp: boolean;
}

/** `value` is absent when the metric cannot be computed; `reason` explains why. */
export interface MetricValue {
  key: string;
  label: string;
  unit: MetricUnit;
  value?: number | null;
  previousValue?: number | null;
  changePercent?: number | null;
  available: boolean;
  reason?: string | null;
}

export interface Overview {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  currency: string;
  currencyConverted: boolean;
  metrics: MetricValue[];
  notes: string[];
  refreshedAt?: string | null;
}

export interface TrendPoint {
  bucket: string;
  value: number;
}

export interface Trend {
  metric: string;
  label: string;
  unit: MetricUnit;
  currency: string;
  granularity: string;
  points: TrendPoint[];
  available: boolean;
  reason?: string | null;
}

export interface BreakdownRow {
  label: string;
  id?: string | null;
  dimensions: Record<string, string>;
  revenue?: number | null;
  cost?: number | null;
  profit?: number | null;
  marginPercent?: number | null;
  outstanding?: number | null;
  minutes?: number | null;
  count?: number | null;
  profitAvailable: boolean;
}

export interface Breakdown {
  dimension: "LOCATION" | "SERVICE" | "CUSTOMER" | "TECHNICIAN";
  currency: string;
  rows: BreakdownRow[];
  notes: string[];
}

export interface AgeingBucket {
  bucket: string;
  amount: number;
  invoices: number;
}

export interface AgeingInvoice {
  id: string;
  number: string;
  customerName: string;
  issueDate: string;
  dueDate: string;
  balance: number;
  daysOverdue: number;
}

export interface Ageing {
  currency: string;
  asOf: string;
  buckets: AgeingBucket[];
  outstanding: number;
  overdue: number;
  invoices: AgeingInvoice[];
  notes: string[];
}

export interface JobProfitRow {
  id: string;
  number: string;
  title: string;
  customerName: string;
  deliveredOn: string;
  revenue: number;
  cost: number;
  profit?: number | null;
  marginPercent?: number | null;
  complete: boolean;
  reasons: string[];
}

export interface JobProfitability {
  currency: string;
  rows: JobProfitRow[];
  incompleteCount: number;
  totalRevenue: number;
  totalCost: number;
  totalProfit: number;
  notes: string[];
}

export interface RefreshStatus {
  refreshedAt: string;
  rangeStart: string;
  rangeEnd: string;
  rowCount: number;
}

export interface RangeQuery {
  from?: string;
  to?: string;
  locationId?: string;
}

export const analyticsApi = {
  catalogue: () => request<MetricInfo[]>("/analytics/catalogue"),
  overview: (params: RangeQuery) => request<Overview>(`/analytics/overview${qs(params)}`),
  trend: (params: RangeQuery & { metric: string; granularity?: string }) =>
    request<Trend>(`/analytics/trend${qs(params)}`),
  breakdown: (params: { dimension: string; from?: string; to?: string }) =>
    request<Breakdown>(`/analytics/breakdown${qs(params)}`),
  locations: () => request<BreakdownRow[]>("/analytics/locations"),
  ageing: () => request<Ageing>("/analytics/ageing"),
  jobs: (params: RangeQuery) => request<JobProfitability>(`/analytics/jobs${qs(params)}`),
  status: () => request<RefreshStatus>("/analytics/status"),
  refresh: (params: { from?: string; to?: string } = {}) =>
    request<RefreshStatus>("/analytics/refresh", { method: "POST", body: JSON.stringify(params) }),
};
