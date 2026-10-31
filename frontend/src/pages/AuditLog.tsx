import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ScrollText, Search } from "lucide-react";
import { auditApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { Badge, Card, EmptyState, ErrorBanner, Field, Input, PageHeader, Select, Skeleton } from "@/components/ui/primitives";
import { Pagination } from "@/components/ui/misc";
import { formatDateTime, labelize } from "@/lib/format";

const actions = [
  "TENANT_CREATED",
  "USER_REGISTERED",
  "USER_LOGIN",
  "USER_LOGIN_FAILED",
  "USER_CREATED",
  "USER_UPDATED",
  "USER_DEACTIVATED",
  "WORKSPACE_UPDATED",
  "CUSTOMER_CREATED",
  "CUSTOMER_UPDATED",
  "CUSTOMER_ARCHIVED",
  "CUSTOMER_RESTORED",
  "LOCATION_CREATED",
  "LOCATION_UPDATED",
  "LOCATION_DELETED",
  "SERVICE_CREATED",
  "SERVICE_UPDATED",
  "QUOTE_CREATED",
  "QUOTE_UPDATED",
  "QUOTE_SENT",
  "QUOTE_ACCEPTED",
  "QUOTE_DECLINED",
  "QUOTE_CONVERTED",
  "QUOTE_DELETED",
  "JOB_CREATED",
  "JOB_UPDATED",
  "JOB_STATUS_CHANGED",
  "JOB_ASSIGNED",
  "JOB_UNASSIGNED",
  "INVOICE_CREATED",
  "INVOICE_SENT",
  "INVOICE_VOIDED",
  "PAYMENT_RECORDED",
  "PAYMENT_REVERSED",
];

function toneFor(action: string) {
  if (action.includes("FAILED") || action.includes("VOIDED") || action.includes("REVERSED") || action.includes("ARCHIVED")) {
    return "danger" as const;
  }
  if (action.includes("LOGIN") || action.includes("ACCEPTED") || action.includes("PAYMENT")) {
    return "success" as const;
  }
  if (action.includes("CREATED") || action.includes("CONVERTED")) {
    return "info" as const;
  }
  return "neutral" as const;
}

export function AuditLog() {
  const { tenant } = useAuth();
  const [action, setAction] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);

  const events = useQuery({
    queryKey: ["audit", action, from, to, page],
    queryFn: () => auditApi.list({
      action: action || undefined,
      from: from ? new Date(`${from}T00:00:00Z`).toISOString() : undefined,
      to: to ? new Date(`${to}T23:59:59Z`).toISOString() : undefined,
      page,
      size: 25,
    }),
  });

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Administration"
        title="Audit log"
        description="An append-only record of sign-ins, record changes, money movements and failed attempts."
      />

      <div className="mb-4 grid gap-3 sm:grid-cols-3 lg:max-w-3xl">
        <Field label="Action" htmlFor="audit-action">
          <Select
            id="audit-action"
            value={action}
            onChange={(event) => {
              setAction(event.target.value);
              setPage(0);
            }}
          >
            <option value="">All actions</option>
            {actions.map((item) => (
              <option key={item} value={item}>{labelize(item)}</option>
            ))}
          </Select>
        </Field>
        <Field label="From" htmlFor="audit-from">
          <Input
            id="audit-from"
            type="date"
            value={from}
            onChange={(event) => {
              setFrom(event.target.value);
              setPage(0);
            }}
          />
        </Field>
        <Field label="To" htmlFor="audit-to">
          <Input
            id="audit-to"
            type="date"
            value={to}
            onChange={(event) => {
              setTo(event.target.value);
              setPage(0);
            }}
          />
        </Field>
      </div>

      <Card className="overflow-hidden">
        {events.isError ? (
          <div className="p-5"><ErrorBanner message={errorMessage(events.error)} /></div>
        ) : events.isLoading ? (
          <div className="space-y-2 p-4">
            {Array.from({ length: 6 }).map((_, index) => <Skeleton key={index} className="h-14 w-full" />)}
          </div>
        ) : (events.data?.content.length ?? 0) === 0 ? (
          <EmptyState
            icon={<ScrollText className="h-5 w-5" />}
            title="Nothing recorded for this filter"
            description="Try widening the date range or clearing the action filter."
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[820px] border-collapse">
                <thead className="border-b border-sand-200 bg-sand-50/70">
                  <tr>
                    <th className="table-head">When</th>
                    <th className="table-head">Action</th>
                    <th className="table-head">Entity</th>
                    <th className="table-head">Detail</th>
                    <th className="table-head">Source</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-sand-100">
                  {events.data?.content.map((event) => (
                    <tr key={event.id} className="align-top transition hover:bg-sand-50/60">
                      <td className="table-cell whitespace-nowrap text-[12px] text-ink-500">
                        {formatDateTime(event.createdAt, tenant?.timezone)}
                      </td>
                      <td className="table-cell">
                        <Badge tone={toneFor(event.action)}>{labelize(event.action)}</Badge>
                      </td>
                      <td className="table-cell">
                        <p className="text-[13px] font-medium text-ink-800">{labelize(event.entityType)}</p>
                        <p className="font-mono text-[11px] text-ink-400">
                          {event.entityId ? event.entityId.slice(0, 8) : "—"}
                        </p>
                      </td>
                      <td className="table-cell max-w-[280px]">
                        <p className="truncate text-[12px] text-ink-500" title={event.detail ?? undefined}>
                          {event.detail ?? "—"}
                        </p>
                      </td>
                      <td className="table-cell text-[12px] text-ink-400">{event.ipAddress ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={events.data?.page ?? 0}
              totalPages={events.data?.totalPages ?? 1}
              totalElements={events.data?.totalElements ?? 0}
              onChange={setPage}
            />
          </>
        )}
      </Card>

      <p className="mt-3 flex items-center gap-1.5 px-1 text-[12px] text-ink-400">
        <Search className="h-3.5 w-3.5" />
        Audit events cannot be edited or deleted through the application.
      </p>
    </div>
  );
}