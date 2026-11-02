import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { teamApi, quotesApi } from "@/api/endpoints";
import type { Job, Quote } from "@/api/types";
import { errorMessage } from "@/api/client";
import { Button, ErrorBanner, Field, Input } from "@/components/ui/primitives";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import { addMinutesToLocal, zonedDefaultWindow } from "@/lib/format";
import { useAuth } from "@/auth/AuthProvider";

export function ConvertQuoteDialog({
  open,
  onClose,
  quote,
  onConverted,
}: {
  open: boolean;
  onClose: () => void;
  quote: Quote;
  onConverted: (job: Job) => void;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const { tenant } = useAuth();
  // The quote's service location decides what "09:00" means; the workspace is the fallback.
  const zone = quote.location?.timezone ?? tenant?.timezone;
  const initial = zonedDefaultWindow(zone);
  const [start, setStart] = useState(initial.start);
  const [end, setEnd] = useState(initial.end);
  const [title, setTitle] = useState("");
  const [technicianIds, setTechnicianIds] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const team = useQuery({ queryKey: ["team", "assignable"], queryFn: teamApi.assignable, enabled: open });

  useEffect(() => {
    if (!open) return;
    const window = zonedDefaultWindow(zone);
    setStart(window.start);
    setEnd(window.end);
    setTitle(`Service for ${quote.customer?.name ?? "customer"}`);
    setTechnicianIds([]);
    setError(null);
  }, [open, quote.customer?.name, zone]);

  const mutation = useMutation({
    mutationFn: () => quotesApi.convert(quote.id, {
      startLocal: start,
      endLocal: end,
      technicianIds,
      title: title.trim() || undefined,
    }),
    onSuccess: async (job) => {
      toast.push({ title: "Job scheduled", description: `${job.number} was created from ${quote.number}.` });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["quotes"] }),
        queryClient.invalidateQueries({ queryKey: ["quote", quote.id] }),
        queryClient.invalidateQueries({ queryKey: ["jobs"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
      onConverted(job);
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const toggleTechnician = (userId: string) => {
    setTechnicianIds((current) =>
      current.includes(userId) ? current.filter((id) => id !== userId) : [...current, userId]);
  };

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    if (new Date(end) <= new Date(start)) {
      setError("The job must end after it starts.");
      return;
    }
    if (technicianIds.length === 0) {
      setError("Assign at least one technician to the job.");
      return;
    }
    mutation.mutate();
  };

  const activeTeam = team.data ?? [];

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Schedule this quote"
      description="Turn the accepted quotation into scheduled work and assign the team. Schedule conflicts are checked before saving."
      footer={
        <>
          <Button variant="outline" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" form="convert-form" variant="secondary" loading={mutation.isPending}>
            Create job
          </Button>
        </>
      }
    >
      <form id="convert-form" onSubmit={submit} className="space-y-4" noValidate>
        {error && <ErrorBanner title="Couldn’t schedule the job" message={error} />}

        <Field label="Job title" htmlFor="convert-title">
          <Input
            id="convert-title"
            value={title}
            maxLength={160}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Weekly service visit"
          />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Starts" htmlFor="convert-start">
            <Input
              id="convert-start"
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
          <Field label="Ends" htmlFor="convert-end">
            <Input
              id="convert-end"
              type="datetime-local"
              required
              value={end}
              onChange={(event) => setEnd(event.target.value)}
            />
          </Field>
        </div>

        <div>
          <p className="label">Technicians</p>
          {team.isLoading ? (
            <p className="text-sm text-ink-400">Loading team…</p>
          ) : activeTeam.length === 0 ? (
            <p className="rounded-lg bg-amber-50 px-3 py-2 text-[12px] text-amber-700">
              No active team members yet. Invite a technician from the Team page first.
            </p>
          ) : (
            <div className="grid max-h-48 gap-1.5 overflow-y-auto rounded-lg border border-sand-200 p-2 sm:grid-cols-2">
              {activeTeam.map((member) => (
                <label
                  key={member.id}
                  className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-ink-700 hover:bg-sand-50"
                >
                  <input
                    type="checkbox"
                    checked={technicianIds.includes(member.id)}
                    onChange={() => toggleTechnician(member.id)}
                    className="h-4 w-4 rounded border-sand-300 text-brand-600 focus:ring-brand-200"
                  />
                  <span className="min-w-0 truncate">{member.fullName}</span>
                </label>
              ))}
            </div>
          )}
        </div>
      </form>
    </Modal>
  );
}