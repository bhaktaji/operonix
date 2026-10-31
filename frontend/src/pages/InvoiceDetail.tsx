import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, Ban, CreditCard, Plus, Send, Trash2 } from "lucide-react";
import { invoicesApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { RecordPaymentDialog } from "@/components/RecordPaymentDialog";
import { Badge, Button, Card, ErrorBanner, PageLoader, SectionCard } from "@/components/ui/primitives";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import { statusTone } from "@/components/ui/cn";
import { formatDate, formatDateTime, formatMoney, formatPercent, labelize } from "@/lib/format";

export function InvoiceDetail() {
  const { invoiceId = "" } = useParams();
  const queryClient = useQueryClient();
  const toast = useToast();
  const { user, hasRole, tenant } = useAuth();
  const [paymentOpen, setPaymentOpen] = useState(false);
  const [voidOpen, setVoidOpen] = useState(false);
  const [voidReason, setVoidReason] = useState("");
  const [reverseId, setReverseId] = useState<string | null>(null);

  const invoice = useQuery({
    queryKey: ["invoice", invoiceId],
    queryFn: () => invoicesApi.get(invoiceId),
    enabled: !!invoiceId,
  });

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["invoice", invoiceId] }),
      queryClient.invalidateQueries({ queryKey: ["invoices"] }),
      queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
    ]);
  };

  const send = useMutation({
    mutationFn: () => invoicesApi.send(invoiceId),
    onSuccess: async (updated) => {
      queryClient.setQueryData(["invoice", invoiceId], updated);
      toast.push({ title: "Invoice sent", description: "It now counts as a receivable." });
      await refresh();
    },
    onError: (caught) => toast.push({ title: "Couldn’t send the invoice", description: errorMessage(caught), tone: "error" }),
  });

  const voidInvoice = useMutation({
    mutationFn: () => invoicesApi.void(invoiceId, voidReason.trim()),
    onSuccess: async (updated) => {
      queryClient.setQueryData(["invoice", invoiceId], updated);
      setVoidOpen(false);
      setVoidReason("");
      toast.push({ title: "Invoice voided" });
      await refresh();
    },
    onError: (caught) => toast.push({ title: "Couldn’t void the invoice", description: errorMessage(caught), tone: "error" }),
  });

  const reverse = useMutation({
    mutationFn: (paymentId: string) => invoicesApi.reversePayment(invoiceId, paymentId),
    onSuccess: async (updated) => {
      queryClient.setQueryData(["invoice", invoiceId], updated);
      setReverseId(null);
      toast.push({ title: "Payment reversed" });
      await refresh();
    },
    onError: (caught) => toast.push({ title: "Couldn’t reverse the payment", description: errorMessage(caught), tone: "error" }),
  });

  if (invoice.isLoading) return <PageLoader label="Loading invoice" />;
  if (invoice.isError || !invoice.data) {
    return (
      <div>
        <Link to="/invoices" className="mb-5 inline-flex items-center gap-1.5 text-sm text-ink-500 hover:text-ink-900">
          <ArrowLeft className="h-4 w-4" /> Back to invoices
        </Link>
        <ErrorBanner title="Invoice unavailable" message={errorMessage(invoice.error)} />
      </div>
    );
  }

  const data = invoice.data;
  const isVoid = data.status === "VOID";
  const canRecordPayment = !isVoid && data.balance > 0;
  const canVoid = !isVoid && data.amountPaid === 0;

  return (
    <div className="animate-fade-up">
      <Link to="/invoices" className="mb-5 inline-flex items-center gap-1.5 text-sm text-ink-500 hover:text-ink-900">
        <ArrowLeft className="h-4 w-4" /> Back to invoices
      </Link>

      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-2.5">
            <h1 className="font-display text-[26px] font-semibold tracking-tight text-ink-950 sm:text-[30px]">
              {data.number}
            </h1>
            <Badge tone={statusTone(data.status)}>{labelize(data.status)}</Badge>
            {data.overdue && <Badge tone="danger">Overdue</Badge>}
          </div>
          <p className="mt-1.5 text-sm text-ink-500">
            For <span className="font-medium text-ink-800">{data.customer?.name}</span> · issued {formatDate(data.issueDate)} · due {formatDate(data.dueDate)}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {data.status === "DRAFT" && (
            <Button
              variant="secondary"
              icon={<Send className="h-4 w-4" />}
              loading={send.isPending}
              onClick={() => send.mutate()}
            >
              Send invoice
            </Button>
          )}
          {canRecordPayment && (
            <Button variant="secondary" icon={<Plus className="h-4 w-4" />} onClick={() => setPaymentOpen(true)}>
              Record payment
            </Button>
          )}
          {canVoid && (
            <Button variant="outline" icon={<Ban className="h-4 w-4" />} onClick={() => setVoidOpen(true)}>
              Void
            </Button>
          )}
        </div>
      </div>

      {isVoid && (
        <div className="mb-4 rounded-xl2 border border-rose-100 bg-rose-50 px-4 py-3 text-sm text-rose-700">
          <span className="font-semibold">Voided.</span>{" "}
          {data.voidReason ?? "This invoice is no longer collectible and is excluded from reporting."}
        </div>
      )}

      <div className="grid gap-4 xl:grid-cols-[1.6fr_1fr]">
        <div className="space-y-4">
          <Card className="p-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Customer</p>
                <Link to={`/customers/${data.customer?.id}`} className="mt-1 block text-sm font-medium text-brand-700 hover:text-brand-800">
                  {data.customer?.name}
                </Link>
                <p className="text-[12px] text-ink-500">{data.customer?.email ?? "No email on file"}</p>
              </div>
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Source job</p>
                {data.jobId ? (
                  <Link to={`/schedule?job=${data.jobId}`} className="mt-1 block text-sm font-medium text-brand-700 hover:text-brand-800">
                    Open the scheduled job
                  </Link>
                ) : (
                  <p className="mt-1 text-sm text-ink-400">Created directly</p>
                )}
              </div>
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Issued / due</p>
                <p className="mt-1 text-sm text-ink-800">{formatDate(data.issueDate)} → {formatDate(data.dueDate)}</p>
              </div>
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Created</p>
                <p className="mt-1 text-sm text-ink-800">{formatDateTime(data.createdAt, tenant?.timezone)}</p>
              </div>
              {data.notes && (
                <div className="sm:col-span-2">
                  <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Notes</p>
                  <p className="mt-1 whitespace-pre-line rounded-lg bg-sand-50 px-3 py-2.5 text-sm text-ink-700">{data.notes}</p>
                </div>
              )}
            </div>
          </Card>

          <SectionCard title="Line items" description={`${data.lines.length} line${data.lines.length === 1 ? "" : "s"}`} bodyClassName="p-0">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[560px] border-collapse">
                <thead className="border-b border-sand-200 bg-sand-50/70">
                  <tr>
                    <th className="table-head">Description</th>
                    <th className="table-head text-right">Qty</th>
                    <th className="table-head text-right">Unit price</th>
                    <th className="table-head text-right">Tax</th>
                    <th className="table-head text-right">Total</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-sand-100">
                  {data.lines.map((line) => (
                    <tr key={line.id}>
                      <td className="table-cell font-medium text-ink-900">{line.description}</td>
                      <td className="table-cell text-right">{Number(line.quantity)}</td>
                      <td className="table-cell text-right">{formatMoney(line.unitPrice, data.currency)}</td>
                      <td className="table-cell text-right">{formatPercent(line.taxRate)}</td>
                      <td className="table-cell text-right font-medium text-ink-900">{formatMoney(line.lineTotal, data.currency)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          <SectionCard
            title="Payments"
            description={data.payments.length === 0 ? "No payments recorded yet" : `${data.payments.length} payment${data.payments.length === 1 ? "" : "s"} recorded`}
            bodyClassName="p-0"
            actions={canRecordPayment ? (
              <Button size="sm" variant="outline" icon={<CreditCard className="h-3.5 w-3.5" />} onClick={() => setPaymentOpen(true)}>
                Add payment
              </Button>
            ) : undefined}
          >
            {data.payments.length === 0 ? (
              <p className="px-5 py-6 text-sm text-ink-400">
                Payments recorded here update the balance and the invoice status automatically.
              </p>
            ) : (
              <ul className="divide-y divide-sand-100">
                {data.payments.map((payment) => (
                  <li key={payment.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-3">
                    <div>
                      <p className="text-sm font-medium text-ink-900">
                        {formatMoney(payment.amount, data.currency)}
                        <span className="ml-2 text-[12px] font-normal text-ink-400">{labelize(payment.method)}</span>
                      </p>
                      <p className="text-[12px] text-ink-500">
                        {formatDateTime(payment.paidAt, tenant?.timezone)}
                        {payment.reference ? ` · ${payment.reference}` : ""}
                      </p>
                    </div>
                    {hasRole("OWNER", "ADMIN") && !isVoid && (
                      <Button
                        size="sm"
                        variant="ghost"
                        className="text-rose-600 hover:bg-rose-50"
                        icon={<Trash2 className="h-3.5 w-3.5" />}
                        onClick={() => setReverseId(payment.id)}
                      >
                        Reverse
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </div>

        <div className="space-y-4 xl:sticky xl:top-6 xl:self-start">
          <Card className="p-5">
            <h2 className="font-display text-[15px] font-semibold text-ink-900">Amounts</h2>
            <dl className="mt-4 space-y-2.5 text-sm">
              <div className="flex justify-between"><dt className="text-ink-500">Subtotal</dt><dd className="font-medium text-ink-900">{formatMoney(data.subtotal, data.currency)}</dd></div>
              {data.discountAmount > 0 && (
                <div className="flex justify-between"><dt className="text-ink-500">Discount</dt><dd className="font-medium text-brand-700">−{formatMoney(data.discountAmount, data.currency)}</dd></div>
              )}
              {(data.taxComponents ?? []).length > 0 ? (
                (data.taxComponents ?? []).map((component) => (
                  <div key={component.code} className="flex justify-between">
                    <dt className="text-ink-500">
                      {component.name}
                      <span className="ml-1 text-[11px] text-ink-400">{(component.rate * 100).toFixed(2)}%</span>
                    </dt>
                    <dd className="font-medium text-ink-900">{formatMoney(component.amount, data.currency)}</dd>
                  </div>
                ))
              ) : (
                <div className="flex justify-between"><dt className="text-ink-500">Tax</dt><dd className="font-medium text-ink-900">{formatMoney(data.taxAmount, data.currency)}</dd></div>
              )}
              {data.taxMode === "INCLUSIVE" && (
                <p className="text-[11px] text-ink-400">Prices include tax.</p>
              )}
              <div className="flex justify-between border-t border-sand-200 pt-2.5">
                <dt className="font-medium text-ink-900">Total</dt>
                <dd className="font-display text-lg font-semibold text-ink-950">{formatMoney(data.total, data.currency)}</dd>
              </div>
              <div className="flex justify-between"><dt className="text-ink-500">Paid</dt><dd className="font-medium text-brand-700">{formatMoney(data.amountPaid, data.currency)}</dd></div>
              <div className="flex items-baseline justify-between border-t border-sand-200 pt-3">
                <dt className="font-medium text-ink-900">Balance</dt>
                <dd className="font-display text-2xl font-semibold text-ink-950">{formatMoney(data.balance, data.currency)}</dd>
              </div>
            </dl>
            {data.baseCurrency && data.baseCurrency !== data.currency && data.baseTotal !== undefined && (
              <p className="mt-3 border-t border-sand-200 pt-2.5 text-[12px] text-ink-500">
                {formatMoney(data.baseTotal, data.baseCurrency)} in {data.baseCurrency}
                <span className="text-ink-400"> · rate {data.fxRate}</span>
              </p>
            )}
            {data.paidAt && (
              <p className="mt-3 text-[12px] text-ink-400">Paid in full {formatDateTime(data.paidAt, tenant?.timezone)}</p>
            )}
          </Card>

          <div className="rounded-xl2 border border-sand-200 bg-white/60 p-4 text-[12px] leading-relaxed text-ink-500">
            <p className="font-medium text-ink-700">Signed in as {user?.fullName}</p>
            <p className="mt-1">
              Voiding requires zero payments recorded. Reverse payments first if a refund was issued.
            </p>
          </div>
        </div>
      </div>

      <RecordPaymentDialog open={paymentOpen} onClose={() => setPaymentOpen(false)} invoice={data} />

      <Modal
        open={voidOpen}
        onClose={() => setVoidOpen(false)}
        title={`Void ${data.number}?`}
        description="Voided invoices are terminal, excluded from receivables, and remain in the audit trail."
        size="sm"
        footer={
          <>
            <Button variant="outline" onClick={() => setVoidOpen(false)}>Keep invoice</Button>
            <Button
              variant="danger"
              disabled={voidReason.trim().length < 3}
              loading={voidInvoice.isPending}
              onClick={() => voidInvoice.mutate()}
            >
              Void invoice
            </Button>
          </>
        }
      >
        <label className="label" htmlFor="void-reason">Reason</label>
        <textarea
          id="void-reason"
          rows={3}
          value={voidReason}
          onChange={(event) => setVoidReason(event.target.value)}
          className="input resize-y"
          placeholder="Duplicate of INV-2026-0012…"
        />
      </Modal>

      <ConfirmDialog
        open={!!reverseId}
        onClose={() => setReverseId(null)}
        onConfirm={() => reverseId && reverse.mutate(reverseId)}
        loading={reverse.isPending}
        title="Reverse this payment?"
        description="The amount is removed from the invoice, the balance is recalculated, and the reversal is audited."
        confirmLabel="Reverse payment"
      />
    </div>
  );
}