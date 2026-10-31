import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { ChevronRight, FileText, Plus, Search } from "lucide-react";
import { quotesApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { Badge, Button, Card, EmptyState, ErrorBanner, Input, PageHeader, Skeleton } from "@/components/ui/primitives";
import { Pagination, Tabs } from "@/components/ui/misc";
import { statusTone } from "@/components/ui/cn";
import { useAuth } from "@/auth/AuthProvider";
import { formatDate, formatMoney, labelize } from "@/lib/format";

type StatusFilter = "ALL" | "DRAFT" | "SENT" | "ACCEPTED" | "CONVERTED" | "DECLINED" | "EXPIRED";

export function Quotes() {
  const { tenant } = useAuth();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);
  const [status, setStatus] = useState<StatusFilter>("ALL");
  const [page, setPage] = useState(0);
  const currency = tenant?.currency ?? "USD";

  const quotes = useQuery({
    queryKey: ["quotes", deferredSearch, status, page],
    queryFn: () => quotesApi.list({
      q: deferredSearch || undefined,
      status: status === "ALL" ? undefined : status,
      page,
      size: 20,
      sort: "issueDate,desc",
    }),
  });

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Sales"
        title="Quotes"
        description="Price the work, send it out, and turn accepted quotes into scheduled jobs."
        actions={
          <Link to="/quotes/new">
            <Button variant="secondary" icon={<Plus className="h-4 w-4" />}>New quote</Button>
          </Link>
        }
      />

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="relative min-w-[220px] flex-1 sm:max-w-xs">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-400" />
          <Input
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            placeholder="Search quote number"
            aria-label="Search quotes"
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
            { value: "ACCEPTED", label: "Accepted" },
            { value: "CONVERTED", label: "Converted" },
            { value: "DECLINED", label: "Declined" },
          ]}
        />
      </div>

      <Card className="overflow-hidden">
        {quotes.isError ? (
          <div className="p-5"><ErrorBanner message={errorMessage(quotes.error)} /></div>
        ) : quotes.isLoading ? (
          <div className="space-y-2 p-4">
            {Array.from({ length: 6 }).map((_, index) => <Skeleton key={index} className="h-14 w-full" />)}
          </div>
        ) : quotes.data && quotes.data.content.length === 0 ? (
          <EmptyState
            icon={<FileText className="h-5 w-5" />}
            title={deferredSearch || status !== "ALL" ? "No quotes match these filters" : "No quotes yet"}
            description="Create a quote from your catalog in under a minute."
            action={
              <Link to="/quotes/new">
                <Button variant="secondary" icon={<Plus className="h-4 w-4" />}>New quote</Button>
              </Link>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] border-collapse">
              <thead className="border-b border-sand-200 bg-sand-50/70">
                <tr>
                  <th className="table-head">Quote</th>
                  <th className="table-head">Customer</th>
                  <th className="table-head">Issued</th>
                  <th className="table-head">Valid until</th>
                  <th className="table-head text-right">Total</th>
                  <th className="table-head">Status</th>
                  <th className="table-head text-right">Open</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-sand-100">
                {quotes.data?.content.map((quote) => (
                  <tr
                    key={quote.id}
                    className="cursor-pointer transition hover:bg-sand-50/70"
                    onClick={() => navigate(`/quotes/${quote.id}`)}
                  >
                    <td className="table-cell font-medium text-ink-900">{quote.number}</td>
                    <td className="table-cell">{quote.customer?.name}</td>
                    <td className="table-cell">{formatDate(quote.issueDate)}</td>
                    <td className="table-cell">{formatDate(quote.validUntil)}</td>
                    <td className="table-cell text-right font-medium text-ink-900">
                      {formatMoney(quote.total, quote.currency || currency)}
                    </td>
                    <td className="table-cell"><Badge tone={statusTone(quote.status)}>{labelize(quote.status)}</Badge></td>
                    <td className="table-cell text-right">
                      <Link
                        to={`/quotes/${quote.id}`}
                        onClick={(event) => event.stopPropagation()}
                        className="inline-flex items-center gap-1 text-[13px] font-medium text-brand-700 hover:text-brand-800"
                      >
                        View <ChevronRight className="h-3.5 w-3.5" />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {quotes.data && quotes.data.content.length > 0 && (
          <Pagination
            page={quotes.data.page}
            totalPages={quotes.data.totalPages}
            totalElements={quotes.data.totalElements}
            onChange={setPage}
          />
        )}
      </Card>
    </div>
  );
}