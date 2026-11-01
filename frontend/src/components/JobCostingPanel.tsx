import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Plus, Play, Square, Trash2 } from "lucide-react";
import { costingApi } from "@/api/endpoints";
import type { ExpenseCategory } from "@/api/types";
import { errorMessage } from "@/api/client";
import { Badge, Button, ErrorBanner, Field, Input, KeyValue, Select, Skeleton, Textarea } from "@/components/ui/primitives";
import { useToast } from "@/components/ui/toast";
import { formatMoney, formatPercent } from "@/lib/format";

const CATEGORIES: ExpenseCategory[] = ["LABOR", "MATERIAL", "EQUIPMENT", "SUBCONTRACTOR", "OTHER"];

/** "3h 25m" from a minute count. */
function duration(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return "—";
  const hours = Math.floor(minutes / 60);
  const rest = Math.round(minutes % 60);
  return hours > 0 ? `${hours}h ${rest}m` : `${rest}m`;
}

/**
 * Time, expenses and profitability for one job.
 *
 * <p>The design rule the backend enforces is mirrored here: when an input is missing the panel says
 * what is missing and shows "Not available" — it never renders a zero that would read as "no profit".
 */
export function JobCostingPanel({ jobId, canManage }: { jobId: string; canManage: boolean }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [error, setError] = useState<string | null>(null);
  const [summary, setSummary] = useState("");
  const [amount, setAmount] = useState("");
  const [category, setCategory] = useState<ExpenseCategory>("MATERIAL");
  const [vendorId, setVendorId] = useState("");
  const [expenseDate, setExpenseDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [receipt, setReceipt] = useState("");
  const [notes, setNotes] = useState("");

  const query = useQuery({
    queryKey: ["job-costing", jobId],
    queryFn: () => costingApi.job(jobId),
  });

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["job-costing", jobId] }),
      queryClient.invalidateQueries({ queryKey: ["job", jobId] }),
      queryClient.invalidateQueries({ queryKey: ["jobs"] }),
    ]);
  };

  const start = useMutation({
    mutationFn: () => costingApi.addWorkLog(jobId, { startAt: new Date().toISOString() }),
    onSuccess: async () => {
      setError(null);
      toast.push({ tone: "success", title: "Timer started", description: "Stop it when the work is done." });
      await invalidate();
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const stop = useMutation({
    mutationFn: (workLogId: string) => costingApi.stopWorkLog(workLogId),
    onSuccess: invalidate,
    onError: (caught) => setError(errorMessage(caught)),
  });

  const removeLog = useMutation({
    mutationFn: (workLogId: string) => costingApi.deleteWorkLog(workLogId),
    onSuccess: invalidate,
    onError: (caught) => setError(errorMessage(caught)),
  });

  const addExpense = useMutation({
    mutationFn: () =>
      costingApi.addExpense(jobId, {
        description: summary.trim(),
        amount: Number(amount),
        expenseDate,
        category,
        vendorId: vendorId || null,
        receiptReference: receipt.trim() || null,
        notes: notes.trim() || null,
      }),
    onSuccess: async () => {
      setError(null);
      setSummary("");
      setAmount("");
      setReceipt("");
      setNotes("");
      toast.push({ tone: "success", title: "Cost recorded" });
      await invalidate();
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const removeExpense = useMutation({
    mutationFn: (expenseId: string) => costingApi.deleteExpense(expenseId),
    onSuccess: invalidate,
    onError: (caught) => setError(errorMessage(caught)),
  });

  if (query.isLoading) {
    return <Skeleton className="h-40 w-full" />;
  }
  if (query.isError || !query.data) {
    return <ErrorBanner title="Couldn’t load costing" message={errorMessage(query.error)} />;
  }

  const { cost, profitability, workLogs, expenses, vendors } = query.data;
  const currency = profitability.currency;
  const openLog = workLogs.find((log) => !log.endAt);

  return (
    <div className="space-y-4 border-t border-sand-200 pt-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Costing &amp; profit</p>
        <div className="flex items-center gap-2">
          <Badge tone={profitability.status === "COMPLETE" ? "success" : "warning"}>
            {profitability.status === "COMPLETE" ? "Complete" : "Incomplete inputs"}
          </Badge>
          {openLog ? (
            <Button
              size="sm"
              variant="outline"
              icon={<Square className="h-3.5 w-3.5" />}
              loading={stop.isPending}
              onClick={() => stop.mutate(openLog.id)}
            >
              Stop timer
            </Button>
          ) : (
            <Button
              size="sm"
              variant="outline"
              icon={<Play className="h-3.5 w-3.5" />}
              loading={start.isPending}
              onClick={() => start.mutate()}
            >
              Start timer
            </Button>
          )}
        </div>
      </div>

      {error && <ErrorBanner title="Costing update failed" message={error} />}

      {profitability.status === "INCOMPLETE" && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 p-3">
          <p className="flex items-center gap-1.5 text-[12px] font-semibold text-amber-800">
            <AlertTriangle className="h-3.5 w-3.5" /> Profit is not available yet
          </p>
          <ul className="mt-1 list-disc space-y-0.5 pl-5 text-[12px] text-amber-700">
            {profitability.reasons.map((reason) => (
              <li key={reason}>{reason}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-lg border border-sand-200 bg-white p-3">
          <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Actual cost</p>
          <div className="mt-2 space-y-1">
            <KeyValue label="Labour">{formatMoney(cost.laborCost, currency)}</KeyValue>
            <KeyValue label="Materials">{formatMoney(cost.materialCost, currency)}</KeyValue>
            <KeyValue label="Equipment">{formatMoney(cost.equipmentCost, currency)}</KeyValue>
            <KeyValue label="Subcontractors">{formatMoney(cost.subcontractorCost, currency)}</KeyValue>
            <KeyValue label="Other">{formatMoney(cost.otherCost, currency)}</KeyValue>
            <div className="border-t border-sand-200 pt-1">
              <KeyValue label="Total">{formatMoney(cost.totalCost, currency)}</KeyValue>
            </div>
          </div>
        </div>

        <div className="rounded-lg border border-sand-200 bg-white p-3">
          <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Direct job profit</p>
          <div className="mt-2 space-y-1">
            <KeyValue label="Revenue">
              {profitability.revenue === null || profitability.revenue === undefined
                ? "Not available"
                : formatMoney(profitability.revenue, currency)}
            </KeyValue>
            <KeyValue label="Profit">
              {profitability.directProfit === null || profitability.directProfit === undefined
                ? "Not available"
                : formatMoney(profitability.directProfit, currency)}
            </KeyValue>
            <KeyValue label="Margin">
              {profitability.marginPercent === null || profitability.marginPercent === undefined
                ? "Not available"
                : formatPercent(profitability.marginPercent / 100)}
            </KeyValue>
            <KeyValue label="Planned profit">
              {profitability.plannedMargin === null || profitability.plannedMargin === undefined
                ? "—"
                : formatMoney(profitability.plannedMargin, currency)}
            </KeyValue>
            <KeyValue label="Time (actual / planned)">
              {duration(profitability.actualMinutes)} / {duration(profitability.scheduledMinutes)}
            </KeyValue>
          </div>
        </div>
      </div>

      <p className="text-[11px] leading-relaxed text-ink-400">{profitability.scopeNote}</p>

      <div>
        <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Time</p>
        {workLogs.length === 0 ? (
          <p className="text-[13px] text-ink-400">No time recorded yet.</p>
        ) : (
          <ul className="divide-y divide-sand-100 rounded-lg border border-sand-200 bg-white">
            {workLogs.map((log) => (
              <li key={log.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-[13px]">
                <span className="text-ink-800">{log.userName}</span>
                <span className="text-ink-500">
                  {new Date(log.startAt).toLocaleString()} ·{" "}
                  {log.endAt ? (
                    <>
                      {duration(log.minutes)} · {log.cost === null || log.cost === undefined
                        ? "rate not set"
                        : formatMoney(log.cost, currency)}
                    </>
                  ) : (
                    <Badge tone="info">Running</Badge>
                  )}
                </span>
                <button
                  type="button"
                  aria-label="Delete time entry"
                  className="rounded p-1 text-ink-400 hover:bg-rose-50 hover:text-rose-600"
                  onClick={() => removeLog.mutate(log.id)}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div>
        <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Expenses</p>
        {expenses.length === 0 ? (
          <p className="text-[13px] text-ink-400">No expenses recorded.</p>
        ) : (
          <ul className="divide-y divide-sand-100 rounded-lg border border-sand-200 bg-white">
            {expenses.map((expense) => (
              <li key={expense.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-[13px]">
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium text-ink-800">{expense.description}</span>
                  <span className="text-[11px] text-ink-500">
                    {expense.category.toLowerCase()} · {expense.expenseDate}
                    {expense.vendorName ? ` · ${expense.vendorName}` : ""}
                    {expense.receiptReference ? ` · ${expense.receiptReference}` : ""}
                  </span>
                </span>
                <span className="text-right">
                  <span className="block font-medium text-ink-900">
                    {formatMoney(expense.amount, expense.currency)}
                  </span>
                  {expense.currency !== currency && (
                    <span className="text-[11px] text-ink-400">
                      {formatMoney(expense.workspaceAmount, currency)} @ {expense.fxRate}
                    </span>
                  )}
                </span>
                {canManage && (
                  <button
                    type="button"
                    aria-label="Delete expense"
                    className="rounded p-1 text-ink-400 hover:bg-rose-50 hover:text-rose-600"
                    onClick={() => removeExpense.mutate(expense.id)}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}

        {canManage && (
          <form
            className="mt-2 grid gap-2 rounded-lg border border-dashed border-sand-300 p-3 sm:grid-cols-2"
            onSubmit={(event) => {
              event.preventDefault();
              addExpense.mutate();
            }}
          >
            <Field label="What was it for?" htmlFor="expense-summary" className="sm:col-span-2">
              <Input
                id="expense-summary"
                required
                maxLength={360}
                value={summary}
                onChange={(event) => setSummary(event.target.value)}
                placeholder="Replacement filters"
              />
            </Field>
            <Field label="Amount" htmlFor="expense-amount">
              <Input
                id="expense-amount"
                required
                type="number"
                min="0.01"
                step="0.01"
                value={amount}
                onChange={(event) => setAmount(event.target.value)}
                placeholder="0.00"
              />
            </Field>
            <Field label="Date" htmlFor="expense-date">
              <Input
                id="expense-date"
                required
                type="date"
                value={expenseDate}
                onChange={(event) => setExpenseDate(event.target.value)}
              />
            </Field>
            <Field label="Category" htmlFor="expense-category">
              <Select
                id="expense-category"
                value={category}
                onChange={(event) => setCategory(event.target.value as ExpenseCategory)}
              >
                {CATEGORIES.map((option) => (
                  <option key={option} value={option}>
                    {option.charAt(0) + option.slice(1).toLowerCase()}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Vendor" htmlFor="expense-vendor" hint="Optional">
              <Select id="expense-vendor" value={vendorId} onChange={(event) => setVendorId(event.target.value)}>
                <option value="">No vendor</option>
                {vendors.filter((vendor) => vendor.active).map((vendor) => (
                  <option key={vendor.id} value={vendor.id}>{vendor.name}</option>
                ))}
              </Select>
            </Field>
            <Field label="Receipt reference" htmlFor="expense-receipt" hint="Optional">
              <Input
                id="expense-receipt"
                maxLength={120}
                value={receipt}
                onChange={(event) => setReceipt(event.target.value)}
                placeholder="INV-2291"
              />
            </Field>
            <Field label="Notes" htmlFor="expense-notes" hint="Optional">
              <Textarea
                id="expense-notes"
                rows={2}
                maxLength={2000}
                value={notes}
                onChange={(event) => setNotes(event.target.value)}
              />
            </Field>
            <div className="sm:col-span-2 flex justify-end">
              <Button
                type="submit"
                size="sm"
                variant="secondary"
                icon={<Plus className="h-3.5 w-3.5" />}
                loading={addExpense.isPending}
                disabled={!summary.trim() || !amount || Number(amount) <= 0}
              >
                Record expense
              </Button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
