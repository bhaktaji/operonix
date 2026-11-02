import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { customersApi, jobsApi, teamApi } from "@/api/endpoints";
import type { Job } from "@/api/types";
import { errorMessage } from "@/api/client";
import { Button, ErrorBanner, Field, Input, Select, Textarea } from "@/components/ui/primitives";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import {
  addMinutesToLocal,
  toZonedLocalValue,
  zonedDefaultWindow,
} from "@/lib/format";
import { useAuth } from "@/auth/AuthProvider";

/**
 * The dialog shows and submits wall-clock times in the location's zone, so a user in another zone
 * books the crew's morning instead of their own.
 */
function defaultStart(zone: string | undefined, dateISO?: string) {
  if (dateISO) {
    return `${dateISO}T09:00`;
  }
  return zonedDefaultWindow(zone).start;
}

function defaultEnd(zone: string | undefined, dateISO?: string) {
  if (dateISO) {
    return addMinutesToLocal(`${dateISO}T09:00`, 120);
  }
  return zonedDefaultWindow(zone).end;
}

export function JobDialog({
  open,
  onClose,
  job,
  defaultDate,
  onSaved,
}: {
  open: boolean;
  onClose: () => void;
  job?: Job | null;
  defaultDate?: string;
  onSaved?: (job: Job) => void;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const { tenant } = useAuth();
  const isEdit = !!job;
  const defaultZone = tenant?.timezone;

  const [customerId, setCustomerId] = useState("");
  const [locationId, setLocationId] = useState("");
  const [title, setTitle] = useState("");
  const [start, setStart] = useState(defaultStart(defaultZone, defaultDate));
  const [end, setEnd] = useState(defaultEnd(defaultZone, defaultDate));
  const [technicianIds, setTechnicianIds] = useState<string[]>([]);
  const [notes, setNotes] = useState("");
  const [recurrence, setRecurrence] = useState("NONE");
  const [occurrences, setOccurrences] = useState("4");
  const [error, setError] = useState<string | null>(null);

  const customers = useQuery({
    queryKey: ["customers", "picker"],
    queryFn: () => customersApi.list({ status: "ACTIVE", size: 100, sort: "name,asc" }),
    enabled: open,
  });
  const team = useQuery({ queryKey: ["team", "assignable"], queryFn: teamApi.assignable, enabled: open });
  const locations = useQuery({
    queryKey: ["locations", customerId],
    queryFn: () => customersApi.locations(customerId),
    enabled: open && !!customerId,
  });

  useEffect(() => {
    if (!open) return;
    setError(null);
    if (job) {
      setCustomerId(job.customer?.id ?? "");
      setLocationId(job.location?.id ?? "");
      setTitle(job.title);
      const jobZone = job.location?.timezone ?? defaultZone;
      setStart(toZonedLocalValue(job.scheduledStart, jobZone));
      setEnd(toZonedLocalValue(job.scheduledEnd, jobZone));
      setTechnicianIds(job.technicians.map((tech) => tech.id));
      setNotes(job.notes ?? "");
      setRecurrence("NONE");
      setOccurrences("4");
    } else {
      setCustomerId("");
      setLocationId("");
      setTitle("");
      setStart(defaultStart(defaultZone, defaultDate));
      setEnd(defaultEnd(defaultZone, defaultDate));
      setTechnicianIds([]);
      setNotes("");
      setRecurrence("NONE");
      setOccurrences("4");
    }
  }, [open, job, defaultDate, defaultZone]);

  useEffect(() => {
    if (!locationId && locations.data && locations.data.length > 0) {
      setLocationId(locations.data[0].id);
    }
  }, [locations.data, locationId]);

  const mutation = useMutation({
    mutationFn: async () => {
      const payload = {
        customerId,
        locationId,
        title: title.trim() || undefined,
        startLocal: start,
        endLocal: end,
        technicianIds,
        notes: notes.trim() || undefined,
      };
      if (isEdit) {
        return jobsApi.update(job!.id, payload);
      }
      if (recurrence !== "NONE") {
        return jobsApi.createSeries({
          ...payload,
          recurrence,
          occurrences: Math.max(2, Math.min(26, Number(occurrences) || 4)),
        });
      }
      return jobsApi.create(payload);
    },
    onSuccess: async (result) => {
      const count = Array.isArray(result) ? result.length : 1;
      const saved = Array.isArray(result) ? result[0] : result;
      toast.push({
        title: isEdit ? "Job rescheduled" : "Job scheduled",
        description: count > 1 ? `${count} visits created.` : saved?.number,
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["jobs"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
      if (saved) {
        queryClient.setQueryData(["job", saved.id], saved);
        onSaved?.(saved);
      }
      onClose();
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    if (!customerId) return setError("Choose a customer.");
    if (!locationId) return setError("Choose a service location for this customer.");
    // Wall-clock strings compare correctly as ISO text within one zone.
    if (end <= start) return setError("The job must end after it starts.");
    mutation.mutate();
  };

  const activeTeam = team.data ?? [];

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isEdit ? `Reschedule ${job?.number}` : "Schedule a job"}
      description={isEdit ? "Only scheduled jobs can be moved. Conflicts are re-checked." : "Book a visit; conflicts for assigned technicians are rejected."}
      size="lg"
      footer={
        <>
          <Button variant="outline" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" form="job-form" variant="secondary" loading={mutation.isPending}>
            {isEdit ? "Save changes" : "Schedule job"}
          </Button>
        </>
      }
    >
      <form id="job-form" onSubmit={submit} className="space-y-4" noValidate>
        {error && <ErrorBanner title="Couldn’t save the job" message={error} />}

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Customer" htmlFor="job-customer">
            <Select
              id="job-customer"
              value={customerId}
              onChange={(event) => {
                setCustomerId(event.target.value);
                setLocationId("");
              }}
            >
              <option value="">Select a customer…</option>
              {customers.data?.content.map((customer) => (
                <option key={customer.id} value={customer.id}>{customer.name}</option>
              ))}
            </Select>
          </Field>

          <Field label="Service location" htmlFor="job-location">
            <Select
              id="job-location"
              value={locationId}
              disabled={!customerId || locations.isLoading}
              onChange={(event) => setLocationId(event.target.value)}
            >
              <option value="">{customerId ? "Choose a location" : "Choose a customer first"}</option>
              {locations.data?.map((location) => (
                <option key={location.id} value={location.id}>{location.label} — {location.city}</option>
              ))}
            </Select>
          </Field>
        </div>

        <Field label="Title" htmlFor="job-title" hint="Leave blank to use your business type and the customer name">
          <Input
            id="job-title"
            value={title}
            maxLength={160}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Weekly service visit"
          />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Starts" htmlFor="job-start">
            <Input
              id="job-start"
              type="datetime-local"
              required
              value={start}
              onChange={(event) => {
                setStart(event.target.value);
                if (event.target.value) {
                  setEnd(addMinutesToLocal(event.target.value, 120));
                }
              }}
            />
          </Field>
          <Field label="Ends" htmlFor="job-end">
            <Input
              id="job-end"
              type="datetime-local"
              required
              value={end}
              onChange={(event) => setEnd(event.target.value)}
            />
          </Field>
        </div>

        <div>
          <p className="label">Assign technicians</p>
          {activeTeam.length === 0 ? (
            <p className="rounded-lg bg-amber-50 px-3 py-2 text-[12px] text-amber-700">
              No active team members yet. You can schedule unassigned work and assign the team later.
            </p>
          ) : (
            <div className="grid max-h-44 gap-1.5 overflow-y-auto rounded-lg border border-sand-200 p-2 sm:grid-cols-2">
              {activeTeam.map((member) => (
                <label
                  key={member.id}
                  className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-ink-700 hover:bg-sand-50"
                >
                  <input
                    type="checkbox"
                    checked={technicianIds.includes(member.id)}
                    onChange={() =>
                      setTechnicianIds((current) =>
                        current.includes(member.id)
                          ? current.filter((id) => id !== member.id)
                          : [...current, member.id])
                    }
                    className="h-4 w-4 rounded border-sand-300 text-brand-600 focus:ring-brand-200"
                  />
                  <span className="truncate">{member.fullName}</span>
                </label>
              ))}
            </div>
          )}
        </div>

        {!isEdit && (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Repeat" htmlFor="job-recurrence">
              <Select id="job-recurrence" value={recurrence} onChange={(event) => setRecurrence(event.target.value)}>
                <option value="NONE">One-time job</option>
                <option value="WEEKLY">Weekly</option>
                <option value="BIWEEKLY">Every two weeks</option>
                <option value="MONTHLY">Monthly</option>
              </Select>
            </Field>
            {recurrence !== "NONE" && (
              <Field label="Occurrences" htmlFor="job-occurrences" hint="2–26 visits">
                <Input
                  id="job-occurrences"
                  type="number"
                  min={2}
                  max={26}
                  value={occurrences}
                  onChange={(event) => setOccurrences(event.target.value)}
                />
              </Field>
            )}
          </div>
        )}

        <Field label="Crew notes" htmlFor="job-notes">
          <Textarea
            id="job-notes"
            rows={2}
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
            placeholder="Access, supplies, pets, priorities…"
          />
        </Field>
      </form>
    </Modal>
  );
}