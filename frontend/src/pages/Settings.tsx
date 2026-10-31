import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Building2, ShieldCheck } from "lucide-react";
import { workspaceApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { Button, Card, ErrorBanner, Field, Input, PageHeader, SectionCard, Select, Skeleton } from "@/components/ui/primitives";
import { useToast } from "@/components/ui/toast";

const currencies = [
  { code: "USD", label: "US dollar" },
  { code: "EUR", label: "Euro" },
  { code: "GBP", label: "British pound" },
  { code: "INR", label: "Indian rupee" },
  { code: "AUD", label: "Australian dollar" },
  { code: "CAD", label: "Canadian dollar" },
];

const timezones = [
  "America/New_York",
  "America/Chicago",
  "America/Denver",
  "America/Los_Angeles",
  "America/Toronto",
  "Europe/London",
  "Europe/Berlin",
  "Europe/Madrid",
  "Asia/Kolkata",
  "Asia/Singapore",
  "Australia/Sydney",
  "UTC",
];

export function Settings() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const { user } = useAuth();
  const workspace = useQuery({ queryKey: ["workspace"], queryFn: workspaceApi.get });

  const [form, setForm] = useState({ name: "", currency: "USD", timezone: "America/New_York" });
  const [error, setError] = useState<string | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (workspace.data && !ready) {
      setForm({
        name: workspace.data.name,
        currency: workspace.data.currency,
        timezone: workspace.data.timezone,
      });
      setReady(true);
    }
  }, [workspace.data, ready]);

  const save = useMutation({
    mutationFn: () => workspaceApi.update({
      name: form.name.trim(),
      currency: form.currency,
      timezone: form.timezone,
    }),
    onSuccess: async () => {
      toast.push({
        title: "Workspace updated",
        description: "New documents use this currency; existing ones keep their original amounts.",
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["workspace"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
      setError(null);
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const currentTimezone = workspace.data?.timezone;
  const timezoneOptions = currentTimezone && !timezones.includes(currentTimezone)
    ? [currentTimezone, ...timezones]
    : timezones;

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Administration"
        title="Workspace"
        description="Business identity, currency and the timezone used for “today” across reports and jobs."
      />

      <div className="grid gap-4 xl:grid-cols-[1.4fr_1fr]">
        <SectionCard title="Business profile" description="Shown across the workspace">
          {workspace.isError ? (
            <ErrorBanner message={errorMessage(workspace.error)} />
          ) : workspace.isLoading ? (
            <div className="space-y-3">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          ) : (
            <form
              className="space-y-4"
              noValidate
              onSubmit={(event) => {
                event.preventDefault();
                save.mutate();
              }}
            >
              {error && <ErrorBanner message={error} />}

              <Field label="Business name" htmlFor="ws-name">
                <Input
                  id="ws-name"
                  required
                  value={form.name}
                  onChange={(event) => setForm({ ...form, name: event.target.value })}
                />
              </Field>

              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Currency" htmlFor="ws-currency" hint="Applied to new quotes and invoices">
                  <Select
                    id="ws-currency"
                    value={form.currency}
                    onChange={(event) => setForm({ ...form, currency: event.target.value })}
                  >
                    {currencies.map((currency) => (
                      <option key={currency.code} value={currency.code}>
                        {currency.code} — {currency.label}
                      </option>
                    ))}
                  </Select>
                </Field>
                <Field label="Timezone" htmlFor="ws-timezone">
                  <Select
                    id="ws-timezone"
                    value={form.timezone}
                    onChange={(event) => setForm({ ...form, timezone: event.target.value })}
                  >
                    {timezoneOptions.map((timezone) => (
                      <option key={timezone} value={timezone}>{timezone}</option>
                    ))}
                  </Select>
                </Field>
              </div>

              <div className="flex justify-end">
                <Button type="submit" variant="secondary" loading={save.isPending}>Save workspace</Button>
              </div>
            </form>
          )}
        </SectionCard>

        <div className="space-y-4">
          <Card className="p-5">
            <div className="flex items-center gap-2 text-ink-500">
              <Building2 className="h-4 w-4" />
              <p className="text-[12px] font-semibold uppercase tracking-[0.12em]">Workspace details</p>
            </div>
            <dl className="mt-4 space-y-3 text-sm">
              <div className="flex justify-between gap-3">
                <dt className="text-ink-500">Workspace slug</dt>
                <dd className="font-medium text-ink-900">{workspace.data?.slug ?? "—"}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-500">Team members</dt>
                <dd className="font-medium text-ink-900">{workspace.data?.users ?? "—"}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-500">Your role</dt>
                <dd className="font-medium text-ink-900">{user?.role}</dd>
              </div>
            </dl>
            <p className="mt-4 text-[12px] leading-relaxed text-ink-400">
              The slug is permanent and used for support and exports. Currency changes never rewrite
              historical documents.
            </p>
          </Card>

          <Card className="p-5">
            <div className="flex items-center gap-2 text-ink-500">
              <ShieldCheck className="h-4 w-4" />
              <p className="text-[12px] font-semibold uppercase tracking-[0.12em]">Security posture</p>
            </div>
            <ul className="mt-3 space-y-2 text-[13px] text-ink-600">
              <li>• Short-lived JWT access tokens with rotating refresh tokens</li>
              <li>• Every record is scoped to your workspace on the server</li>
              <li>• Sensitive actions land in the audit log</li>
              <li>• Role-based access for owners, admins, managers, technicians</li>
            </ul>
          </Card>
        </div>
      </div>
    </div>
  );
}