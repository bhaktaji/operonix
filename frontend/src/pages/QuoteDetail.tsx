import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, ArrowRight, Ban, CalendarPlus, Check, Pencil, Send, Trash2 } from "lucide-react";
import { quotesApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { ConvertQuoteDialog } from "@/components/ConvertQuoteDialog";
import { Badge, Button, Card, ErrorBanner, PageLoader, SectionCard } from "@/components/ui/primitives";
import { ConfirmDialog } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import { statusTone } from "@/components/ui/cn";
import { formatDate, formatDateTime, formatMoney, formatPercent, labelize } from "@/lib/format";

export function QuoteDetail() {
  const { quoteId = "" } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();
  const { tenant, user, hasRole } = useAuth();
  const [convertOpen, setConvertOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const quote = useQuery({ queryKey: ["quote", quoteId], queryFn: () => quotesApi.get(quoteId), enabled: !!quoteId });

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["quote", quoteId] }),
      queryClient.invalidateQueries({ queryKey: ["quotes"] }),
      queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
    ]);
  };

  const transition = useMutation({
    mutationFn: (kind: "send" | "accept" | "decline") => {
      if (kind === "send") return quotesApi.send(quoteId);
      if (kind === "accept") return quotesApi.accept(quoteId);
      return quotesApi.decline(quoteId);
    },
    onSuccess: async (updated, kind) => {
      queryClient.setQueryData(["quote", quoteId], updated);
      toast.push({ title: kind === "send" ? "Quote sent" : kind === "accept" ? "Quote accepted" : "Quote declined" });
      await refresh();
    },
    onError: (caught) => toast.push({ title: "Couldn’t update the quote", description: errorMessage(caught), tone: "error" }),
  });

  const remove = useMutation({
    mutationFn: () => quotesApi.remove(quoteId),
    onSuccess: async () => {
      toast.push({ title: "Draft deleted" });
      await queryClient.invalidateQueries({ queryKey: ["quotes"] });
      navigate("/quotes", { replace: true });
    },
    onError: (caught) => toast.push({ title: "Couldn’t delete the quote", description: errorMessage(caught), tone: "error" }),
  });

  if (quote.isLoading) return <PageLoader label="Loading quote" />;
  if (quote.isError || !quote.data) {
    return (
      <div>
        <Link to="/quotes" className="mb-5 inline-flex items-center gap-1.5 text-sm text-ink-500 hover:text-ink-900">
          <ArrowLeft className="h-4 w-4" /> Back to quotes
        </Link>
        <ErrorBanner title="Quote unavailable" message={errorMessage(quote.error)} />
      </div>
    );
  }

  const data = quote.data;
  const isDraft = data.status === "DRAFT";
  const isSent = data.status === "SENT";

  return (
    <div className="animate-fade-up">
      <Link to="/quotes" className="mb-5 inline-flex items-center gap-1.5 text-sm text-ink-500 hover:text-ink-900">
        <ArrowLeft className="h-4 w-4" /> Back to quotes
      </Link>

      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-2.5">
            <h1 className="font-display text-[26px] font-semibold tracking-tight text-ink-950 sm:text-[30px]">
              {data.number}
            </h1>
            <Badge tone={statusTone(data.status)}>{labelize(data.status)}</Badge>
          </div>
          <p className="mt-1.5 text-sm text-ink-500">
            For <span className="font-medium text-ink-800">{data.customer?.name}</span> · issued {formatDate(data.issueDate)}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {isDraft && (
            <>
              <Link to={`/quotes/${data.id}/edit`}>
                <Button variant="outline" icon={<Pencil className="h-4 w-4" />}>Edit</Button>
              </Link>
              <Button
                variant="secondary"
                icon={<Send className="h-4 w-4" />}
                loading={transition.isPending && transition.variables === "send"}
                onClick={() => transition.mutate("send")}
              >
                Send quote
              </Button>
            </>
          )}
          {isSent && (
            <>
              <Button
                variant="outline"
                icon={<Ban className="h-4 w-4" />}
                loading={transition.isPending && transition.variables === "decline"}
                onClick={() => transition.mutate("decline")}
              >
                Mark declined
              </Button>
              <Button
                variant="secondary"
                icon={<Check className="h-4 w-4" />}
                loading={transition.isPending && transition.variables === "accept"}
                onClick={() => transition.mutate("accept")}
              >
                Mark accepted
              </Button>
            </>
          )}
          {data.status === "ACCEPTED" && (
            <Button variant="secondary" icon={<CalendarPlus className="h-4 w-4" />} onClick={() => setConvertOpen(true)}>
              Convert to job
            </Button>
          )}
          {data.status === "CONVERTED" && data.convertedJobId && (
            <Link to={`/schedule?job=${data.convertedJobId}`}>
              <Button variant="outline" icon={<ArrowRight className="h-4 w-4" />}>View job</Button>
            </Link>
          )}
          {isDraft && hasRole("OWNER", "ADMIN") && (
            <Button variant="ghost" className="text-rose-600 hover:bg-rose-50" icon={<Trash2 className="h-4 w-4" />} onClick={() => setDeleteOpen(true)}>
              Delete
            </Button>
          )}
        </div>
      </div>

      <div className="grid gap-4 xl:grid-cols-[1.6fr_1fr]">
        <div className="min-w-0 space-y-4">
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
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Service location</p>
                {data.location ? (
                  <p className="mt-1 text-sm leading-relaxed text-ink-800">
                    {data.location.label}
                    <br />
                    {data.location.line1}, {data.location.city} {data.location.postalCode}
                  </p>
                ) : (
                  <p className="mt-1 text-sm text-ink-400">Customer default</p>
                )}
              </div>
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Issued</p>
                <p className="mt-1 text-sm text-ink-800">{formatDate(data.issueDate)}</p>
              </div>
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Valid until</p>
                <p className="mt-1 text-sm text-ink-800">{formatDate(data.validUntil)}</p>
              </div>
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
                      <td className="table-cell text-right font-medium text-ink-900">
                        {formatMoney(line.lineTotal, data.currency)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {data.notes && (
            <Card className="p-5">
              <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Notes</p>
              <p className="mt-2 whitespace-pre-line text-sm leading-relaxed text-ink-700">{data.notes}</p>
            </Card>
          )}
        </div>

        <div className="space-y-4 xl:sticky xl:top-6 xl:self-start">
          <Card className="p-5">
            <h2 className="font-display text-[15px] font-semibold text-ink-900">Quote total</h2>
            <dl className="mt-4 space-y-2.5 text-sm">
              <div className="flex justify-between">
                <dt className="text-ink-500">Subtotal</dt>
                <dd className="font-medium text-ink-900">{formatMoney(data.subtotal, data.currency)}</dd>
              </div>
              {data.discountAmount > 0 && (
                <div className="flex justify-between">
                  <dt className="text-ink-500">Discount</dt>
                  <dd className="font-medium text-brand-700">−{formatMoney(data.discountAmount, data.currency)}</dd>
                </div>
              )}
              <div className="flex justify-between">
                <dt className="text-ink-500">Tax</dt>
                <dd className="font-medium text-ink-900">{formatMoney(data.taxAmount, data.currency)}</dd>
              </div>
              <div className="flex justify-between border-t border-sand-200 pt-2.5">
                <dt className="font-medium text-ink-900">Total</dt>
                <dd className="font-display text-xl font-semibold text-ink-950">
                  {formatMoney(data.total, data.currency)}
                </dd>
              </div>
            </dl>
            <div className="mt-4 border-t border-sand-100 pt-3 text-[12px] text-ink-400">
              <p>Created {formatDateTime(data.createdAt, tenant?.timezone)}</p>
              <p>Updated {formatDateTime(data.updatedAt, tenant?.timezone)}</p>
              <p className="mt-1">Prepared by {user?.fullName}</p>
            </div>
          </Card>

          {data.status === "EXPIRED" && (
            <div className="rounded-xl2 border border-rose-100 bg-rose-50 p-4 text-[13px] text-rose-700">
              This quote passed its validity date and can no longer be accepted. Create a fresh quote
              for the customer.
            </div>
          )}
        </div>
      </div>

      <ConvertQuoteDialog
        open={convertOpen}
        onClose={() => setConvertOpen(false)}
        quote={data}
        onConverted={(job) => {
          setConvertOpen(false);
          navigate(`/schedule?job=${job.id}`);
        }}
      />

      <ConfirmDialog
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
        title={`Delete draft ${data.number}?`}
        description="Only drafts can be deleted. Once sent, a quote is part of your sales record."
        confirmLabel="Delete draft"
      />
    </div>
  );
}