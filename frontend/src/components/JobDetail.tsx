import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { Ban, Check, CircleDollarSign, Pencil, Play, Plus, UserMinus, X } from "lucide-react";
import { jobsApi, teamApi } from "@/api/endpoints";
import type { Invoice, Job } from "@/api/types";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { Avatar, Badge, Button, ErrorBanner, Field, Select, Textarea } from "@/components/ui/primitives";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import { statusTone } from "@/components/ui/cn";
import { JobCostingPanel } from "@/components/JobCostingPanel";
import { formatCalendarDate, formatTime, labelize } from "@/lib/format";

export function JobDetail({
  open,
  onClose,
  job,
  onEdit,
  onInvoiced,
}: {
  open: boolean;
  onClose: () => void;
  job: Job;
  onEdit: (job: Job) => void;
  onInvoiced?: (invoice: Invoice) => void;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const { hasRole, tenant } = useAuth();
  const canManage = hasRole("OWNER", "ADMIN", "MANAGER");

  const [assignee, setAssignee] = useState("");
  const [cancelMode, setCancelMode] = useState(false);
  const [cancelNote, setCancelNote] = useState("");
  const [error, setError] = useState<string | null>(null);

  const team = useQuery({
    queryKey: ["team", "assignable"],
    queryFn: teamApi.assignable,
    enabled: open && canManage,
  });

  useEffect(() => {
    if (!open) return;
    setAssignee("");
    setCancelMode(false);
    setCancelNote("");
    setError(null);
  }, [open, job.id]);

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["jobs"] }),
      queryClient.invalidateQueries({ queryKey: ["job", job.id] }),
      queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
    ]);
  };

  const transition = useMutation({
    mutationFn: ({ to, note }: { to: string; note?: string }) => jobsApi.transition(job.id, to, note),
    onSuccess: async (updated, variables) => {
      queryClient.setQueryData(["job", updated.id], updated);
      toast.push({ title: `Job ${labelize(variables.to).toLowerCase()}` });
      setCancelMode(false);
      setCancelNote("");
      await refresh();
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const assign = useMutation({
    mutationFn: (userId: string) => jobsApi.assign(job.id, userId),
    onSuccess: async (updated) => {
      queryClient.setQueryData(["job", updated.id], updated);
      toast.push({ title: "Technician assigned" });
      setAssignee("");
      await refresh();
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const unassign = useMutation({
    mutationFn: (userId: string) => jobsApi.unassign(job.id, userId),
    onSuccess: async (updated) => {
      queryClient.setQueryData(["job", updated.id], updated);
      toast.push({ title: "Technician removed" });
      await refresh();
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const invoice = useMutation({
    mutationFn: () => jobsApi.invoice(job.id, {}),
    onSuccess: async (created) => {
      toast.push({ title: "Invoice created", description: created.number });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["invoices"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
      onInvoiced?.(created);
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const available = (team.data ?? []).filter(
    (member) => !job.technicians.some((tech) => tech.id === member.id),
  );
  // The visit happens in the location's zone; the workspace zone is only the fallback.
  const jobZone = job.location?.timezone ?? tenant?.timezone;

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title={
        <span className="flex flex-wrap items-center gap-2">
          {job.number}
          <Badge tone={statusTone(job.status)}>{labelize(job.status)}</Badge>
        </span>
      }
      description={`${job.title} · ${formatCalendarDate(job.scheduledStart, jobZone)}`}
      footer={
        <>
          <Button variant="outline" type="button" onClick={onClose}>Close</Button>
          {/* Invoicing is normal after completion — the backend only refuses a cancelled job or a second
              active invoice — so the action must not disappear when the work is finished. */}
          {canManage && job.status !== "CANCELLED" && (
            <Button
              variant="outline"
              icon={<CircleDollarSign className="h-4 w-4" />}
              loading={invoice.isPending}
              onClick={() => invoice.mutate()}
            >
              Create invoice
            </Button>
          )}
          {canManage && job.status === "SCHEDULED" && (
            <Button variant="ghost" icon={<Pencil className="h-4 w-4" />} onClick={() => onEdit(job)}>
              Reschedule
            </Button>
          )}
          {job.status === "SCHEDULED" && (
            <Button
              variant="secondary"
              icon={<Play className="h-4 w-4" />}
              loading={transition.isPending && transition.variables?.to === "IN_PROGRESS"}
              onClick={() => transition.mutate({ to: "IN_PROGRESS" })}
            >
              Start job
            </Button>
          )}
          {job.status === "IN_PROGRESS" && (
            <Button
              variant="secondary"
              icon={<Check className="h-4 w-4" />}
              loading={transition.isPending && transition.variables?.to === "COMPLETED"}
              onClick={() => transition.mutate({ to: "COMPLETED" })}
            >
              Complete job
            </Button>
          )}
          {(job.status === "SCHEDULED" || job.status === "IN_PROGRESS") && (
            <Button
              variant="ghost"
              className="text-rose-600 hover:bg-rose-50"
              icon={<Ban className="h-4 w-4" />}
              onClick={() => setCancelMode(true)}
            >
              Cancel
            </Button>
          )}
        </>
      }
    >
      <div className="space-y-5">
        {error && <ErrorBanner title="Couldn’t update the job" message={error} />}

        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Customer</p>
            <Link to={`/customers/${job.customer?.id}`} className="mt-1 block text-sm font-medium text-brand-700 hover:text-brand-800">
              {job.customer?.name}
            </Link>
            <p className="text-[12px] text-ink-500">{job.customer?.email ?? "No email on file"}</p>
          </div>
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Location</p>
            {job.location ? (
              <p className="mt-1 text-sm leading-relaxed text-ink-800">
                {job.location.label}
                <br />
                {job.location.line1}, {job.location.city} {job.location.postalCode}
              </p>
            ) : (
              <p className="mt-1 text-sm text-ink-400">—</p>
            )}
          </div>
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Window</p>
            <p className="mt-1 text-sm text-ink-800">
              {formatCalendarDate(job.scheduledStart, jobZone)} · {
                formatTime(job.scheduledStart, jobZone)
              }{" – "}
              {formatTime(job.scheduledEnd, jobZone)}
            </p>
          </div>
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Series</p>
            <p className="mt-1 text-sm text-ink-800">
              {job.recurrence === "NONE" ? "One-time visit" : `${labelize(job.recurrence)} recurring`}
            </p>
          </div>
        </div>

        <div>
          <p className="mb-2 text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Assigned team</p>
          {job.technicians.length === 0 ? (
            <p className="text-sm text-ink-400">Unassigned</p>
          ) : (
            <ul className="flex flex-wrap gap-2">
              {job.technicians.map((tech) => (
                <li key={tech.id} className="inline-flex items-center gap-2 rounded-full border border-sand-200 bg-white py-1 pl-1 pr-2 text-[13px] text-ink-700">
                  <Avatar name={tech.fullName} className="h-6 w-6 text-[10px]" />
                  {tech.fullName}
                  {canManage && job.status !== "COMPLETED" && job.status !== "CANCELLED" && (
                    <button
                      type="button"
                      aria-label={`Unassign ${tech.fullName}`}
                      onClick={() => unassign.mutate(tech.id)}
                      className="rounded-full p-0.5 text-ink-400 hover:bg-sand-100 hover:text-rose-600"
                    >
                      <X className="h-3 w-3" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}

          {canManage && job.status !== "COMPLETED" && job.status !== "CANCELLED" && (
            <div className="mt-3 flex items-end gap-2">
              <Field label="Add a technician" htmlFor="assign-tech" className="flex-1">
                <Select id="assign-tech" value={assignee} onChange={(event) => setAssignee(event.target.value)}>
                  <option value="">Select a team member…</option>
                  {available.map((member) => (
                    <option key={member.id} value={member.id}>{member.fullName}</option>
                  ))}
                </Select>
              </Field>
              <Button
                variant="outline"
                size="md"
                icon={<Plus className="h-4 w-4" />}
                disabled={!assignee}
                loading={assign.isPending}
                onClick={() => assignee && assign.mutate(assignee)}
              >
                Assign
              </Button>
            </div>
          )}
        </div>

        <JobCostingPanel jobId={job.id} canManage={canManage} timezone={jobZone} />

        {job.notes && (
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Notes</p>
            <p className="mt-1 whitespace-pre-line rounded-lg bg-sand-50 px-3 py-2.5 text-sm text-ink-700">{job.notes}</p>
          </div>
        )}

        {cancelMode && (
          <div className="rounded-lg border border-rose-100 bg-rose-50 p-4">
            <p className="text-sm font-semibold text-rose-800">Cancel this job?</p>
            <p className="mt-0.5 text-[12px] text-rose-600">
              A short reason is required and will appear in the audit trail.
            </p>
            <div className="mt-3">
              <Textarea
                aria-label="Cancellation reason"
                rows={2}
                value={cancelNote}
                onChange={(event) => setCancelNote(event.target.value)}
                placeholder="Customer requested a different date…"
              />
            </div>
            <div className="mt-3 flex justify-end gap-2">
              <Button size="sm" variant="outline" onClick={() => setCancelMode(false)}>Keep job</Button>
              <Button
                size="sm"
                variant="danger"
                disabled={cancelNote.trim().length < 3}
                loading={transition.isPending && transition.variables?.to === "CANCELLED"}
                onClick={() => transition.mutate({ to: "CANCELLED", note: cancelNote.trim() })}
              >
                Cancel job
              </Button>
            </div>
          </div>
        )}

        {canManage && job.status !== "CANCELLED" && (
          <p className="flex items-center gap-1.5 text-[12px] text-ink-400">
            <UserMinus className="h-3.5 w-3.5" /> Completing a job locks its schedule; invoicing copies
            the accepted quote&apos;s lines when available.
          </p>
        )}
      </div>
    </Modal>
  );
}