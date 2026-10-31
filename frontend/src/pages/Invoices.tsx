import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { ChevronRight, Plus, Receipt, Search } from "lucide-react";
import { invoicesApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { InvoiceCreateDialog } from "@/components/InvoiceCreateDialog";
import { Badge, Button, Card, EmptyState, ErrorBanner, Input, PageHeader, Skeleton } from "@/components/ui/primitives";
import { Pagination, Tabs } from "@/components/ui/misc";
import { statusTone } from "@/components/ui/cn";
import { formatDate, formatMoney, labelize } from "@/lib/format";

type StatusFilter = "ALL" | "DRAFT" | "SENT" | "PARTIALLY_PAID" | "PAID" | "VOID";

export function Invoices() {
  const { tenant } = useAuth();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);
  const [status, setStatus] = useState<StatusFilter>("ALL");
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const currency = tenant?.currency ?? "USD";

  const invoices = useQuery({
    queryKey: ["invoices", deferredSearch, status, overdueOnly, page],
    queryFn: () => invoicesApi.list({
      q: deferredSearch || undefined,
      status: status === "ALL" ? undefined : status,
      overdue: overdueOnly ? true : undefined,
      page,
      size: 20,
      sort: "issueDate,desc",
    }),
  });

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Billing"
        title="Invoices"
        description="Track what’s owed, what’s late, and every payment collected."
        actions={
          <Button variant="secondary" icon={<Plus className="h-4 w-4" />} onClick={() => setCreateOpen(true)}>
            New invoice
          </Button>
        }
      />

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="relative min-w-[200px] flex-1 sm:max-w-xs">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-400" />
          <Input
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            placeholder="Search invoice number"
            aria-label="Search invoices"
            className="pl-9"
          />
        </div>
        <Tabs
          value={status}
          onChange={(value) => {
            setStatus(value);
            setPage(0);
          }}
          items={[
            { value: "ALL", label: "All" },
            { value: "DRAFT", label: "Draft" },
            { value: "SENT", label: "Sent" },
            { value: "PARTIALLY_PAID", label: "Part paid" },
            { value: "PAID", label: "Paid" },
            { value: "VOID", label: "Void" },
          ]}
        />
        <label className="flex cursor-pointer items-center gap-2 rounded-lg border border-sand-200 bg-white px-3 py-2 text-[13px] font-medium text-ink-600">
          <input
            type="checkbox"
            checked={overdueOnly}
            onChange={(event) => {
              setOverdueOnly(event.target.checked);
              setPage(0);
            }}
            className="h-4 w-4 rounded border-sand-300 text-brand-600 focus:ring-brand-200"
          />
          Overdue only
        </label>
      </div>

      <Card className="overflow-hidden">
        {invoices.isError ? (
          <div className="p-5"><ErrorBanner message={errorMessage(invoices.error)} /></div>
        ) : invoices.isLoading ? (
          <div className="space-y-2 p-4">
            {Array.from({ length: 6 }).map((_, index) => <Skeleton key={index} className="h-14 w-full" />)}
          </div>
        ) : invoices.data && invoices.data.content.length === 0 ? (
          <EmptyState
            icon={<Receipt className="h-5 w-5" />}
            title="No invoices here"
            description="Invoice a completed job from the schedule, or create an invoice directly."
            action={
              <Button variant="secondary" icon={<Plus className="h-4 w-4" />} onClick={() => setCreateOpen(true)}>
                New invoice
              </Button>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[840px] border-collapse">
              <thead className="border-b border-sand-200 bg-sand-50/70">
                <tr>
                  <th className="table-head">Invoice</th>
                  <th className="table-head">Customer</th>
                  <th className="table-head">Issued</th>
                  <th className="table-head">Due</th>
                  <th className="table-head text-right">Total</th>
                  <th className="table-head text-right">Balance</th>
                  <th className="table-head">Status</th>
                  <th className="table-head text-right">Open</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-sand-100">
                {invoices.data?.content.map((invoice) => (
                  <tr
                    key={invoice.id}
                    className="cursor-pointer transition hover:bg-sand-50/70"
                    onClick={() => navigate(`/invoices/${invoice.id}`)}
                  >
                    <td className="table-cell font-medium text-ink-900">{invoice.number}</td>
                    <td className="table-cell">{invoice.customer?.name}</td>
                    <td className="table-cell">{formatDate(invoice.issueDate)}</td>
                    <td className="table-cell">
                      <span className={invoice.overdue ? "font-medium text-rose-600" : ""}>
                        {formatDate(invoice.dueDate)}
                      </span>
                    </td>
                    <td className="table-cell text-right">{formatMoney(invoice.total, invoice.currency || currency)}</td>
                    <td className="table-cell text-right font-medium text-ink-900">
                      {formatMoney(invoice.balance, invoice.currency || currency)}
                    </td>
                    <td className="table-cell">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <Badge tone={statusTone(invoice.status)}>{labelize(invoice.status)}</Badge>
                        {invoice.overdue && <Badge tone="danger">Overdue</Badge>}
                      </div>
                    </td>
                    <td className="table-cell text-right">
                      <span className="inline-flex items-center gap-1 text-[13px] font-medium text-brand-700">
                        View <ChevronRight className="h-3.5 w-3.5" />
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {invoices.data && invoices.data.content.length > 0 && (
          <Pagination
            page={invoices.data.page}
            totalPages={invoices.data.totalPages}
            totalElements={invoices.data.totalElements}
            onChange={setPage}
          />
        )}
      </Card>

      <InvoiceCreateDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(invoice) => navigate(`/invoices/${invoice.id}`)}
      />
    </div>
  );
}