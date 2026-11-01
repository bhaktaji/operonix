import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  AlertTriangle,
  Info,
  Lightbulb,
  Send,
  Sparkles,
  TrendingUp,
} from "lucide-react";
import { aiApi, type AskResponse, type Insight } from "@/api/ai";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import {
  Badge,
  Button,
  Card,
  ErrorBanner,
  Field,
  Input,
  PageHeader,
  SectionCard,
  Skeleton,
  Textarea,
} from "@/components/ui/primitives";
import { useToast } from "@/components/ui/toast";
import { formatMoney, formatPercent } from "@/lib/format";

const EXAMPLES = [
  "What was our revenue this month?",
  "Which jobs lost money last quarter?",
  "How much is overdue right now?",
  "Show revenue by location last 90 days",
];

function reading(value: number | null | undefined, unit: string, currency: string): string {
  if (value === null || value === undefined) return "Not available";
  if (unit === "CURRENCY") return formatMoney(value, currency);
  if (unit === "PERCENT") return formatPercent(value / 100);
  if (unit === "MINUTES") return `${Math.round(value)} min`;
  return Math.round(value).toLocaleString();
}

function InsightRow({ insight }: { insight: Insight }) {
  const tone: "warning" | "success" | "info" =
    insight.severity === "WARNING" ? "warning" : insight.severity === "POSITIVE" ? "success" : "info";
  const icon = insight.severity === "WARNING"
    ? <AlertTriangle className="h-3.5 w-3.5" />
    : insight.severity === "POSITIVE"
      ? <TrendingUp className="h-3.5 w-3.5" />
      : <Info className="h-3.5 w-3.5" />;
  return (
    <li className="flex flex-col gap-1 border-b border-sand-100 py-3 last:border-0 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0">
        <p className="flex items-center gap-1.5 text-[13px] font-medium text-ink-900">
          <Badge tone={tone}>
            <span className="inline-flex items-center gap-1">{icon}{insight.severity.toLowerCase()}</span>
          </Badge>
          {insight.title}
        </p>
        <p className="mt-0.5 text-[12px] text-ink-500">{insight.detail}</p>
      </div>
      {insight.link && (
        <Link to={insight.link} className="shrink-0 text-[12px] font-medium text-brand-700 hover:text-brand-800">
          Investigate
        </Link>
      )}
    </li>
  );
}

/**
 * Operonix AI.
 *
 * <p>The page is explicit about where numbers come from: every figure shown is calculated by
 * Operonix, and the assistant only explains them. When no provider key is configured the same
 * figures are still presented, with the built-in explanation instead of model prose.
 */
export function Assistant() {
  const { tenant, hasRole } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const canUse = hasRole("OWNER", "ADMIN", "MANAGER");
  const canConfigure = hasRole("OWNER", "ADMIN");
  const currency = tenant?.currency ?? "USD";

  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState<AskResponse | null>(null);
  const [limitDraft, setLimitDraft] = useState("");

  const status = useQuery({ queryKey: ["ai", "status"], queryFn: aiApi.status, enabled: canUse });
  const insights = useQuery({ queryKey: ["ai", "insights"], queryFn: aiApi.insights, enabled: canUse });
  const usage = useQuery({ queryKey: ["ai", "usage"], queryFn: aiApi.usage, enabled: canUse });

  const ask = useMutation({
    mutationFn: (text: string) => aiApi.ask(text),
    onSuccess: (result) => {
      setAnswer(result);
      void queryClient.invalidateQueries({ queryKey: ["ai", "usage"] });
      void queryClient.invalidateQueries({ queryKey: ["ai", "status"] });
    },
    onError: (caught) => toast.push({ tone: "error", title: "Couldn’t answer", description: errorMessage(caught) }),
  });

  const assist = useMutation({
    mutationFn: () => aiApi.quoteAssist({}),
    onSuccess: () => toast.push({ title: "Suggestions ready", description: "Review before sending anything." }),
    onError: (caught) => toast.push({ tone: "error", title: "Couldn’t draft suggestions", description: errorMessage(caught) }),
  });

  const saveSettings = useMutation({
    mutationFn: (input: { enabled?: boolean; dailyQueryLimit?: number }) => aiApi.settings(input),
    onSuccess: async () => {
      toast.push({ title: "AI settings saved" });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["ai", "status"] }),
        queryClient.invalidateQueries({ queryKey: ["ai", "usage"] }),
      ]);
    },
    onError: (caught) => toast.push({ tone: "error", title: "Couldn’t save settings", description: errorMessage(caught) }),
  });

  if (!canUse) {
    return (
      <div className="space-y-6">
        <PageHeader title="Operonix AI" description="Business assistant for revenue, cost and collections." />
        <Card className="p-6">
          <p className="text-sm text-ink-600">
            Operonix AI is available to managers and above. Your workspace figures remain available on the
            pages you can access.
          </p>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Operonix AI"
        description="Ask about revenue, costs, collections and productivity. Every figure is calculated by Operonix — the assistant only explains it."
        actions={
          <Badge tone={status.data?.providerConfigured ? "success" : "neutral"}>
            {status.data?.providerConfigured
              ? `Assisted by ${status.data.provider}`
              : "Built-in explanations"}
          </Badge>
        }
      />

      {status.isError && <ErrorBanner title="Couldn’t load AI status" message={errorMessage(status.error)} />}

      {status.data && (
        <Card className="flex flex-wrap items-center justify-between gap-3 p-4">
          <p className="text-[12px] text-ink-500">{status.data.note}</p>
          <p className="text-[12px] text-ink-500">
            {status.data.usedToday} of {status.data.dailyQueryLimit} queries used today
            {status.data.blockedToday > 0 ? ` · ${status.data.blockedToday} blocked` : ""}
          </p>
        </Card>
      )}

      <SectionCard
        title="Ask a question"
        description="Questions map onto the same metric catalogue the dashboard uses — the assistant cannot invent a metric."
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (question.trim().length < 3) return;
            ask.mutate(question.trim());
          }}
          className="space-y-3"
        >
          <Textarea
            rows={3}
            maxLength={500}
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            placeholder="How much revenue did we collect last quarter, and what is still outstanding?"
            aria-label="Your question"
          />
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap gap-1.5">
              {EXAMPLES.map((example) => (
                <button
                  key={example}
                  type="button"
                  onClick={() => setQuestion(example)}
                  className="rounded-full border border-sand-200 bg-white px-2.5 py-1 text-[11px] text-ink-600 hover:border-brand-200 hover:text-brand-700"
                >
                  {example}
                </button>
              ))}
            </div>
            <Button
              type="submit"
              icon={<Send className="h-4 w-4" />}
              loading={ask.isPending}
              disabled={question.trim().length < 3}
            >
              Ask
            </Button>
          </div>
        </form>

        {ask.isError && <div className="mt-3"><ErrorBanner message={errorMessage(ask.error)} /></div>}

        {answer && (
          <div className="mt-4 space-y-3 rounded-xl border border-sand-200 bg-sand-50/60 p-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={answer.status === "OK" ? "success" : answer.status === "DEGRADED" ? "warning" : "neutral"}>
                {answer.status === "OK" ? "Answered" : answer.status === "DEGRADED" ? "Built-in explanation" : answer.status.toLowerCase()}
              </Badge>
              <span className="text-[11px] text-ink-400">
                {answer.from} → {answer.to} · {answer.metrics.length} metric(s)
              </span>
            </div>

            <p className="text-sm leading-relaxed text-ink-800">{answer.narrative}</p>

            {answer.metrics.length > 0 && (
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                {answer.metrics.map((metric) => (
                  <div key={metric.key} className="rounded-lg border border-sand-200 bg-white p-3">
                    <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-400">
                      {metric.label}
                    </p>
                    <p className={metric.available ? "mt-1 text-[15px] font-semibold text-ink-900" : "mt-1 text-[13px] text-ink-400"}>
                      {reading(metric.value, metric.unit, answer.currency)}
                    </p>
                    {!metric.available && metric.reason && (
                      <p className="mt-0.5 text-[11px] text-ink-400">{metric.reason}</p>
                    )}
                  </div>
                ))}
              </div>
            )}

            {answer.breakdown && answer.breakdown.rows.length > 0 && (
              <div className="overflow-x-auto rounded-lg border border-sand-200 bg-white">
                <table className="w-full text-[13px]">
                  <thead>
                    <tr className="border-b border-sand-200 text-left text-[11px] uppercase tracking-[0.1em] text-ink-400">
                      <th className="px-3 py-2">{answer.breakdown.dimension.toLowerCase()}</th>
                      <th className="px-3 py-2 text-right">Revenue</th>
                      <th className="px-3 py-2 text-right">Profit</th>
                    </tr>
                  </thead>
                  <tbody>
                    {answer.breakdown.rows.map((row, index) => (
                      <tr key={index} className="border-b border-sand-100 last:border-0">
                        <td className="px-3 py-2 text-ink-800">{String(row.label ?? "—")}</td>
                        <td className="px-3 py-2 text-right text-ink-900">
                          {typeof row.revenue === "number" ? formatMoney(row.revenue, answer.currency) : "—"}
                        </td>
                        <td className="px-3 py-2 text-right text-ink-600">
                          {typeof row.profit === "number" ? formatMoney(row.profit, answer.currency) : "—"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {answer.notes.length > 0 && (
              <ul className="list-disc space-y-0.5 pl-5 text-[11px] text-ink-500">
                {answer.notes.map((note) => <li key={note}>{note}</li>)}
              </ul>
            )}
            <p className="text-[11px] text-ink-400">
              Provider: {answer.provider} · model {answer.model} ·{" "}
              {answer.narrativeFromModel ? "wording written by the model" : "wording written by Operonix"}
            </p>
          </div>
        )}
      </SectionCard>

      <div className="grid gap-4 lg:grid-cols-2">
        <SectionCard
          title="Operational insights"
          description="Rules over verified figures — not generated text."
        bodyClassName="pt-0"
        >
          {insights.isLoading && <Skeleton className="h-32 w-full" />}
          {insights.isError && <ErrorBanner message={errorMessage(insights.error)} />}
          {insights.data && insights.data.insights.length === 0 && (
            <p className="text-[13px] text-ink-500">
              Nothing needs attention in this period. Insights appear once there is billing history.
            </p>
          )}
          {insights.data && insights.data.insights.length > 0 && (
            <ul className="divide-y divide-sand-100">
              {insights.data.insights.map((insight, index) => (
                <InsightRow key={`${insight.title}-${index}`} insight={insight} />
              ))}
            </ul>
          )}
          {insights.data && (
            <p className="mt-2 text-[11px] text-ink-400">{(insights.data.notes ?? []).join(" ")}</p>
          )}
        </SectionCard>

        <SectionCard
          title="Quotation assistance"
          description="Suggestions built from your catalogue, planned costs and accepted prices."
          actions={
            <Button
              variant="outline"
              size="sm"
              icon={<Sparkles className="h-4 w-4" />}
              loading={assist.isPending}
              onClick={() => assist.mutate()}
            >
              Suggest lines
            </Button>
          }
        >
          {!assist.data ? (
            <p className="text-[13px] text-ink-500">
              Generate suggestions to see pricing guidance with the assumptions behind it. Nothing is
              written to a quote.
            </p>
          ) : (
            <div className="space-y-3">
              <p className="text-sm leading-relaxed text-ink-800">{assist.data.narrative}</p>
              <ul className="divide-y divide-sand-100 rounded-lg border border-sand-200 bg-white">
                {assist.data.suggestions.map((suggestion) => (
                  <li key={suggestion.serviceItemId} className="px-3 py-2">
                    <div className="flex items-center justify-between gap-2 text-[13px]">
                      <span className="font-medium text-ink-900">{suggestion.name}</span>
                      <span className="text-ink-900">{formatMoney(suggestion.suggestedPrice, currency)}</span>
                    </div>
                    <p className="text-[11px] text-ink-500">
                      cost {formatMoney(suggestion.cost, currency)}
                      {suggestion.historicalPrice != null
                        ? ` · quoted before at ${formatMoney(suggestion.historicalPrice, currency)} (${suggestion.timesQuoted}×)`
                        : " · never quoted"}
                      {suggestion.marginPercent != null
                        ? ` · margin ${formatPercent(suggestion.marginPercent / 100)}`
                        : ""}
                    </p>
                    <p className="text-[11px] text-ink-400">{suggestion.rationale}</p>
                  </li>
                ))}
              </ul>
              <ul className="list-disc space-y-0.5 pl-5 text-[11px] text-ink-500">
                {assist.data.assumptions.map((assumption) => <li key={assumption}>{assumption}</li>)}
              </ul>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-[11px] text-ink-400">{assist.data.disclaimer}</p>
                <Link to="/quotes/new" className="text-[12px] font-medium text-brand-700 hover:text-brand-800">
                  Start a quote
                </Link>
              </div>
            </div>
          )}
        </SectionCard>
      </div>

      {canConfigure && (
        <SectionCard title="AI availability and limits" description="Control whether the assistant may be used, and how much.">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex items-center justify-between rounded-lg border border-sand-200 p-3">
              <div>
                <p className="text-[13px] font-medium text-ink-900">Assistant enabled</p>
                <p className="text-[11px] text-ink-500">Turning this off never affects analytics.</p>
              </div>
              <Button
                variant={status.data?.enabled ? "outline" : "secondary"}
                size="sm"
                loading={saveSettings.isPending}
                onClick={() => saveSettings.mutate({ enabled: !status.data?.enabled })}
              >
                {status.data?.enabled ? "Turn off" : "Turn on"}
              </Button>
            </div>
            <form
              className="flex items-end gap-2 rounded-lg border border-sand-200 p-3"
              onSubmit={(event) => {
                event.preventDefault();
                const limit = Number(limitDraft);
                if (!Number.isFinite(limit) || limit < 0) return;
                saveSettings.mutate({ dailyQueryLimit: Math.floor(limit) });
                setLimitDraft("");
              }}
            >
              <Field label="Daily query limit" htmlFor="ai-limit" hint="Per workspace, per day." className="flex-1">
                <Input
                  id="ai-limit"
                  type="number"
                  min={0}
                  max={10000}
                  value={limitDraft}
                  onChange={(event) => setLimitDraft(event.target.value)}
                  placeholder={String(status.data?.dailyQueryLimit ?? 100)}
                />
              </Field>
              <Button type="submit" variant="outline" size="md" disabled={!limitDraft}>
                Save
              </Button>
            </form>
          </div>

          {usage.data && (
            <div className="mt-4">
              <p className="mb-2 text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">
                Recent activity
              </p>
              {usage.data.recent.length === 0 ? (
                <p className="text-[13px] text-ink-500">No AI activity yet.</p>
              ) : (
                <ul className="divide-y divide-sand-100 text-[12px]">
                  {usage.data.recent.map((entry, index) => (
                    <li key={index} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
                      <span className="min-w-0 flex-1 truncate text-ink-700">
                        {entry.question ?? entry.kind}
                      </span>
                      <span className="text-ink-400">
                        <Badge tone={entry.status === "OK" ? "success" : entry.status === "DEGRADED" ? "warning" : "neutral"}>
                          {entry.status}
                        </Badge>{" "}
                        {new Date(entry.createdAt).toLocaleString()}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          {!status.data?.providerConfigured && (
            <p className="mt-3 flex items-start gap-1.5 text-[11px] text-ink-500">
              <Lightbulb className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              To have answers written by a language model, an administrator adds a provider key on the
              server (Secret Manager in production). Operonix never sends customer records — only the
              figures it has calculated.
            </p>
          )}
        </SectionCard>
      )}
    </div>
  );
}
