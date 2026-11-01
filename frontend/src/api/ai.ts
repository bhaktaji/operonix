import { request } from "./client";

export type AiStatusValue = "OK" | "DEGRADED" | "DISABLED" | "QUOTA_EXCEEDED";

export interface AiStatus {
  enabled: boolean;
  providerConfigured: boolean;
  provider: string;
  model: string;
  dailyQueryLimit: number;
  usedToday: number;
  remainingToday: number;
  blockedToday: number;
  tokensInToday: number;
  tokensOutToday: number;
  note: string;
}

export interface MetricReading {
  key: string;
  label: string;
  unit: "CURRENCY" | "COUNT" | "MINUTES" | "PERCENT";
  value?: number | null;
  available: boolean;
  reason?: string | null;
}

export interface BreakdownTable {
  dimension: string;
  rows: Record<string, string | number | boolean | null>[];
}

export interface AskResponse {
  status: AiStatusValue;
  answered: boolean;
  question: string;
  from: string;
  to: string;
  currency: string;
  metrics: MetricReading[];
  breakdown?: BreakdownTable | null;
  narrative: string;
  narrativeFromModel: boolean;
  provider: string;
  model: string;
  notes: string[];
  intent: string;
}

export interface QuoteSuggestion {
  serviceItemId: string;
  name: string;
  unit: string;
  cost: number;
  historicalPrice?: number | null;
  timesQuoted: number;
  quantity: number;
  suggestedPrice: number;
  suggestedTotal: number;
  marginPercent?: number | null;
  rationale: string;
}

export interface QuoteAssistResponse {
  suggestions: QuoteSuggestion[];
  narrative: string;
  narrativeFromModel: boolean;
  assumptions: string[];
  disclaimer: string;
}

export interface Insight {
  severity: "INFO" | "POSITIVE" | "WARNING";
  title: string;
  detail: string;
  metric?: string | null;
  link?: string | null;
}

export interface InsightsResponse {
  currency: string;
  from: string;
  to: string;
  insights: Insight[];
  notes: string[];
}

export interface AiUsage {
  provider: string;
  model: string;
  providerConfigured: boolean;
  enabled: boolean;
  dailyQueryLimit: number;
  usedToday: number;
  remainingToday: number;
  tokensInToday: number;
  tokensOutToday: number;
  recent: {
    kind: string;
    question?: string | null;
    status: string;
    provider: string;
    model: string;
    tokensIn: number;
    tokensOut: number;
    createdAt: string;
  }[];
}

export const aiApi = {
  status: () => request<AiStatus>("/ai/status"),
  usage: () => request<AiUsage>("/ai/usage"),
  settings: (input: { enabled?: boolean; dailyQueryLimit?: number; model?: string }) =>
    request<AiStatus>("/ai/settings", { method: "PUT", body: JSON.stringify(input) }),
  ask: (question: string, from?: string, to?: string) =>
    request<AskResponse>("/ai/ask", {
      method: "POST",
      body: JSON.stringify({ question, from, to }),
    }),
  quoteAssist: (input: { notes?: string; targetMarginPercent?: number } = {}) =>
    request<QuoteAssistResponse>("/ai/quote-assist", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  insights: () => request<InsightsResponse>("/ai/insights"),
};
