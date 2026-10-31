import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  Clock,
  MapPin,
  Plus,
  RefreshCw,
} from "lucide-react";
import { jobsApi } from "@/api/endpoints";
import type { Job, JobStatus } from "@/api/types";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { JobDialog } from "@/components/JobDialog";
import { JobDetail } from "@/components/JobDetail";
import { Avatar, Badge, Button, Card, EmptyState, ErrorBanner, PageHeader, Skeleton } from "@/components/ui/primitives";
import { Tabs } from "@/components/ui/misc";
import { statusTone } from "@/components/ui/cn";
import {
  addDaysISO,
  dayKey,
  formatDate,
  formatDay,
  formatTime,
  labelize,
  startOfWeekISO,
  todayISO,
} from "@/lib/format";

type StatusFilter = "ALL" | JobStatus;

export function Schedule() {
  const { tenant, hasRole } = useAuth();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const timezone = tenant?.timezone;
  const canManage = hasRole("OWNER", "ADMIN", "MANAGER");

  const [weekOffset, setWeekOffset] = useState(0);
  const [status, setStatus] = useState<StatusFilter>("ALL");
  const [createOpen, setCreateOpen] = useState(false);
  const [editingJob, setEditingJob] = useState<Job | null>(null);
  const [selectedJob, setSelectedJob] = useState<Job | null>(null);
  const [focusedId, setFocusedId] = useState<string | null>(searchParams.get("job"));

  const from = addDaysISO(startOfWeekISO(todayISO()), weekOffset * 7);
  const to = addDaysISO(from, 6);

  const jobs = useQuery({
    queryKey: ["jobs", from, to, status],
    queryFn: () => jobsApi.list({
      from,
      to,
      status: status === "ALL" ? undefined : status,
      page: 0,
      size: 100,
      sort: "scheduledStart,asc",
    }),
  });

  const focused = useQuery({
    queryKey: ["job", focusedId],
    queryFn: () => jobsApi.get(focusedId!),
    enabled: !!focusedId,
  });

  useEffect(() => {
    if (focused.data) {
      setSelectedJob(focused.data);
    }
  }, [focused.data]);

  useEffect(() => {
    const id = searchParams.get("job");
    if (id && id !== focusedId) {
      setFocusedId(id);
    }
  }, [searchParams, focusedId]);

  const closeDetail = () => {
    setSelectedJob(null);
    if (focusedId) {
      setFocusedId(null);
      setSearchParams({}, { replace: true });
    }
  };

  const grouped = useMemo(() => {
    const map = new Map<string, Job[]>();
    for (const job of jobs.data?.content ?? []) {
      const key = dayKey(job.scheduledStart, timezone);
      map.set(key, [...(map.get(key) ?? []), job]);
    }
    return [...map.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [jobs.data, timezone]);

  const weekLabel = `${formatDate(from)} – ${formatDate(addDaysISO(from, 6))}`;

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Operations"
        title="Scheduling"
        description="The week at a glance — assign crews, start visits, and keep every address covered."
        actions={
          canManage ? (
            <Button variant="secondary" icon={<Plus className="h-4 w-4" />} onClick={() => setCreateOpen(true)}>
              New job
            </Button>
          ) : undefined
        }
      />

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Button size="sm" variant="outline" aria-label="Previous week" onClick={() => setWeekOffset((value) => value - 1)}>
            <ChevronLeft className="h-3.5 w-3.5" />
          </Button>
          <div className="min-w-[190px] text-center">
            <p className="text-sm font-semibold text-ink-900">{weekLabel}</p>
            <p className="text-[11px] text-ink-400">{weekOffset === 0 ? "This week" : weekOffset > 0 ? `In ${weekOffset} week${weekOffset === 1 ? "" : "s"}` : `${Math.abs(weekOffset)} week${weekOffset === -1 ? "" : "s"} ago`}</p>
          </div>
          <Button size="sm" variant="outline" aria-label="Next week" onClick={() => setWeekOffset((value) => value + 1)}>
            <ChevronRight className="h-3.5 w-3.5" />
          </Button>
          {weekOffset !== 0 && (
            <Button size="sm" variant="ghost" icon={<RefreshCw className="h-3.5 w-3.5" />} onClick={() => setWeekOffset(0)}>
              Today
            </Button>
          )}
        </div>

        <Tabs
          value={status}
          onChange={setStatus}
          items={[
            { value: "ALL", label: "All" },
            { value: "SCHEDULED", label: "Scheduled" },
            { value: "IN_PROGRESS", label: "In progress" },
            { value: "COMPLETED", label: "Completed" },
            { value: "CANCELLED", label: "Cancelled" },
          ]}
        />
      </div>

      {jobs.isError ? (
        <ErrorBanner message={errorMessage(jobs.error)} />
      ) : jobs.isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, index) => <Skeleton key={index} className="h-32 w-full" />)}
        </div>
      ) : grouped.length === 0 ? (
        <Card>
          <EmptyState
            icon={<CalendarDays className="h-5 w-5" />}
            title="Nothing scheduled this week"
            description={canManage
              ? "Book a job or convert an accepted quote to start filling the calendar."
              : "No visits are assigned to you for this week."}
            action={canManage ? (
              <Button variant="secondary" icon={<Plus className="h-4 w-4" />} onClick={() => setCreateOpen(true)}>
                Schedule a job
              </Button>
            ) : undefined}
          />
        </Card>
      ) : (
        <div className="space-y-5">
          {grouped.map(([key, dayJobs]) => (
            <section key={key}>
              <div className="mb-2 flex items-baseline justify-between px-0.5">
                <div className="flex items-baseline gap-2">
                  <h2 className="font-display text-[15px] font-semibold text-ink-900">
                    {formatDay(dayJobs[0].scheduledStart, timezone)}
                  </h2>
                  <span className="text-[12px] text-ink-400">{dayJobs.length} visit{dayJobs.length === 1 ? "" : "s"}</span>
                </div>
                <span className="text-[11px] text-ink-400">{dayJobs.reduce((sum, job) => sum + (new Date(job.scheduledEnd).getTime() - new Date(job.scheduledStart).getTime()), 0) / 3_600_000}h planned</span>
              </div>

              <ul className="space-y-2">
                {dayJobs.map((job) => (
                  <li key={job.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedJob(job)}
                      className="card w-full px-4 py-3 text-left transition hover:border-brand-200 hover:shadow-float focus-visible:ring-4 focus-visible:ring-brand-100"
                    >
                      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
                        <div className="flex w-24 shrink-0 items-center gap-2 sm:flex-col sm:items-start sm:gap-0.5">
                          <span className="text-[13px] font-semibold text-ink-900">
                            {formatTime(job.scheduledStart, timezone)}
                          </span>
                          <span className="text-[11px] text-ink-400">
                            {formatTime(job.scheduledEnd, timezone)}
                          </span>
                          <span className="hidden text-[10px] font-medium uppercase tracking-wide text-ink-400 sm:block">
                            {job.number}
                          </span>
                        </div>

                        <div className="min-w-0 flex-1 sm:border-l sm:border-sand-200 sm:pl-4">
                          <div className="flex flex-wrap items-center gap-2">
                            <p className="font-medium text-ink-900">{job.title}</p>
                            {job.recurrence !== "NONE" && (
                              <Badge tone="neutral">{labelize(job.recurrence)}</Badge>
                            )}
                          </div>
                          <p className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-ink-500">
                            <span>{job.customer?.name}</span>
                            {job.location && (
                              <span className="inline-flex items-center gap-1">
                                <MapPin className="h-3 w-3" /> {job.location.label}
                              </span>
                            )}
                            <span className="inline-flex items-center gap-1">
                              <Clock className="h-3 w-3" />
                              {Math.round((new Date(job.scheduledEnd).getTime() - new Date(job.scheduledStart).getTime()) / 60_000)} min
                            </span>
                          </p>
                        </div>

                        <div className="flex items-center justify-between gap-3 sm:justify-end">
                          <div className="flex -space-x-1.5">
                            {job.technicians.slice(0, 3).map((tech) => (
                              <Avatar key={tech.id} name={tech.fullName} className="h-7 w-7 border-2 border-white text-[10px]" />
                            ))}
                            {job.technicians.length === 0 && (
                              <span className="text-[11px] font-medium text-amber-600">Unassigned</span>
                            )}
                          </div>
                          <Badge tone={statusTone(job.status)}>{labelize(job.status)}</Badge>
                        </div>
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}

      <JobDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        defaultDate={from}
        onSaved={(job) => {
          setCreateOpen(false);
          setSelectedJob(job);
        }}
      />

      <JobDialog
        open={!!editingJob}
        onClose={() => setEditingJob(null)}
        job={editingJob}
        onSaved={(job) => {
          setEditingJob(null);
          setSelectedJob(job);
        }}
      />

      {selectedJob && (
        <JobDetail
          open={!!selectedJob}
          job={selectedJob}
          onClose={closeDetail}
          onEdit={(job) => {
            setSelectedJob(null);
            setEditingJob(job);
          }}
          onInvoiced={(invoice) => navigate(`/invoices/${invoice.id}`)}
        />
      )}
    </div>
  );
}