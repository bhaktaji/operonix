import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { ChevronRight, Plus, Search, Users } from "lucide-react";
import { customersApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { CustomerDialog } from "@/components/CustomerDialog";
import { Avatar, Badge, Button, Card, EmptyState, ErrorBanner, Input, PageHeader, Skeleton } from "@/components/ui/primitives";
import { Pagination, Tabs } from "@/components/ui/misc";
import { statusTone } from "@/components/ui/cn";
import { labelize } from "@/lib/format";

type StatusFilter = "ACTIVE" | "ARCHIVED" | "ALL";

export function Customers() {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);
  const [status, setStatus] = useState<StatusFilter>("ACTIVE");
  const [page, setPage] = useState(0);
  const [dialogOpen, setDialogOpen] = useState(false);

  const customers = useQuery({
    queryKey: ["customers", deferredSearch, status, page],
    queryFn: () => customersApi.list({
      q: deferredSearch || undefined,
      status: status === "ALL" ? "ALL" : status,
      page,
      size: 20,
      sort: "name,asc",
    }),
  });

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Clients"
        title="Customers"
        description="The homes and businesses you clean — with contacts, locations and history."
        actions={
          <Button variant="secondary" icon={<Plus className="h-4 w-4" />} onClick={() => setDialogOpen(true)}>
            New customer
          </Button>
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
            placeholder="Search name, email or contact"
            aria-label="Search customers"
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
            { value: "ACTIVE", label: "Active" },
            { value: "ARCHIVED", label: "Archived" },
            { value: "ALL", label: "All" },
          ]}
        />
      </div>

      <Card className="overflow-hidden">
        {customers.isError ? (
          <div className="p-5">
            <ErrorBanner message={errorMessage(customers.error)} />
          </div>
        ) : customers.isLoading ? (
          <div className="space-y-2 p-4">
            {Array.from({ length: 6 }).map((_, index) => (
              <Skeleton key={index} className="h-14 w-full" />
            ))}
          </div>
        ) : customers.data && customers.data.content.length === 0 ? (
          <EmptyState
            icon={<Users className="h-5 w-5" />}
            title={deferredSearch ? "No customers match that search" : "No customers yet"}
            description={deferredSearch
              ? "Try a different name, email or contact."
              : "Add your first client to start quoting, scheduling and invoicing."}
            action={!deferredSearch ? (
              <Button variant="secondary" icon={<Plus className="h-4 w-4" />} onClick={() => setDialogOpen(true)}>
                New customer
              </Button>
            ) : undefined}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[680px] border-collapse">
              <thead className="border-b border-sand-200 bg-sand-50/70">
                <tr>
                  <th className="table-head">Customer</th>
                  <th className="table-head">Contact</th>
                  <th className="table-head">Phone</th>
                  <th className="table-head">Status</th>
                  <th className="table-head text-right">Open</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-sand-100">
                {customers.data?.content.map((customer) => (
                  <tr
                    key={customer.id}
                    className="group cursor-pointer transition hover:bg-sand-50/70"
                    onClick={() => navigate(`/customers/${customer.id}`)}
                  >
                    <td className="table-cell">
                      <div className="flex items-center gap-3">
                        <Avatar name={customer.name} />
                        <div className="min-w-0">
                          <p className="truncate font-medium text-ink-900">{customer.name}</p>
                          <p className="truncate text-[12px] text-ink-400">{customer.email ?? "No email on file"}</p>
                        </div>
                      </div>
                    </td>
                    <td className="table-cell">{customer.contactName ?? "—"}</td>
                    <td className="table-cell">{customer.phone ?? "—"}</td>
                    <td className="table-cell">
                      <Badge tone={statusTone(customer.status)}>{labelize(customer.status)}</Badge>
                    </td>
                    <td className="table-cell text-right">
                      <Link
                        to={`/customers/${customer.id}`}
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
        {customers.data && customers.data.content.length > 0 && (
          <Pagination
            page={customers.data.page}
            totalPages={customers.data.totalPages}
            totalElements={customers.data.totalElements}
            onChange={setPage}
          />
        )}
      </Card>

      <CustomerDialog open={dialogOpen} onClose={() => setDialogOpen(false)} />
    </div>
  );
}