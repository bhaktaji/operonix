import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Building2, Globe2, ShieldCheck } from "lucide-react";
import { referenceApi, workspaceApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { Button, Card, ErrorBanner, Field, Input, PageHeader, SectionCard, Select, Skeleton } from "@/components/ui/primitives";
import { useToast } from "@/components/ui/toast";
import { formatDateTime } from "@/lib/format";

const FALLBACK_TIMEZONES = [
  "UTC", "Asia/Kolkata", "Asia/Dubai", "Asia/Singapore", "Asia/Tokyo", "Asia/Kuala_Lumpur",
  "Europe/London", "Europe/Berlin", "Europe/Madrid", "Europe/Paris", "Europe/Amsterdam",
  "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Toronto",
  "America/Mexico_City", "America/Sao_Paulo", "Australia/Sydney", "Pacific/Auckland", "Africa/Johannesburg",
];

function timezoneOptions(current: string): string[] {
  const supported = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
  const list = typeof supported === "function" ? supported("timeZone") : FALLBACK_TIMEZONES;
  return Array.from(new Set<string>([current, ...list])).sort();
}

const LOCALES = [
  { code: "en", label: "English" },
  { code: "en-IN", label: "English (India)" },
  { code: "en-US", label: "English (United States)" },
  { code: "en-GB", label: "English (United Kingdom)" },
  { code: "en-AU", label: "English (Australia)" },
  { code: "en-CA", label: "English (Canada)" },
  { code: "en-SG", label: "English (Singapore)" },
  { code: "de-DE", label: "Deutsch (Deutschland)" },
  { code: "fr-FR", label: "Français (France)" },
  { code: "es-ES", label: "Español (España)" },
  { code: "pt-BR", label: "Português (Brasil)" },
  { code: "ja-JP", label: "日本語 (日本)" },
  { code: "nl-NL", label: "Nederlands (Nederland)" },
];

export function Settings() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const { user } = useAuth();
  const workspace = useQuery({ queryKey: ["workspace"], queryFn: workspaceApi.get });
  const reference = useQuery({ queryKey: ["reference"], queryFn: referenceApi.all, staleTime: 60 * 60 * 1000 });

  const [form, setForm] = useState({
    name: "",
    countryCode: "",
    currency: "",
    timezone: "UTC",
    locale: "en",
    industryTemplate: "GENERAL_SERVICE",
    legalName: "",
    taxNumber: "",
  });
  const [error, setError] = useState<string | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (workspace.data && !ready) {
      setForm({
        name: workspace.data.name,
        countryCode: workspace.data.countryCode,
        currency: workspace.data.currency,
        timezone: workspace.data.timezone,
        locale: workspace.data.locale ?? "en",
        industryTemplate: workspace.data.industryTemplate ?? "GENERAL_SERVICE",
        legalName: workspace.data.legalName ?? "",
        taxNumber: workspace.data.taxNumber ?? "",
      });
      setReady(true);
    }
  }, [workspace.data, ready]);

  const save = useMutation({
    mutationFn: () => workspaceApi.update({
      name: form.name.trim(),
      countryCode: form.countryCode,
      currency: form.currency,
      timezone: form.timezone,
      locale: form.locale,
      industryTemplate: form.industryTemplate,
      legalName: form.legalName.trim() || null,
      taxNumber: form.taxNumber.trim() || null,
    }),
    onSuccess: async () => {
      toast.push({
        title: "Workspace updated",
        description: "New documents use these settings. Issued documents keep their original snapshot.",
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["workspace"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
      setError(null);
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const countries = reference.data?.countries ?? [];
  const currencies = reference.data?.currencies ?? [];
  const industries = reference.data?.industryTemplates ?? [];
  const selectedCountry = countries.find((country) => country.code === form.countryCode);
  const zones = useMemo(() => timezoneOptions(form.timezone), [form.timezone]);
  const taxLabel = selectedCountry?.taxLabel ?? "Tax identifier";

  const update = (key: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Administration"
        title="Workspace settings"
        description="Business identity, regional configuration and the defaults new documents inherit."
        actions={
          <Link to="/settings/financial" className="inline-flex">
            <Button variant="secondary" size="sm">Financial settings</Button>
          </Link>
        }
      />

      <div className="grid gap-4 xl:grid-cols-[1.5fr_1fr]">
        <div className="space-y-4">
          <SectionCard title="Business profile" description="Shown across the workspace and on documents">
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

                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="Business name" htmlFor="ws-name">
                    <Input id="ws-name" required value={form.name} onChange={update("name")} />
                  </Field>
                  <Field
                    label="Registered legal name"
                    htmlFor="ws-legal"
                    hint="Optional — used on tax documents when it differs"
                  >
                    <Input id="ws-legal" value={form.legalName} onChange={update("legalName")} />
                  </Field>
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="Business type" htmlFor="ws-industry" hint="Sets the vocabulary you see">
                    <Select id="ws-industry" value={form.industryTemplate} onChange={update("industryTemplate")}>
                      {industries.length === 0 && <option value="GENERAL_SERVICE">General service business</option>}
                      {industries.map((industry) => (
                        <option key={industry.code} value={industry.code}>
                          {industry.label}
                        </option>
                      ))}
                    </Select>
                  </Field>
                  <Field label="Country of registration" htmlFor="ws-country">
                    <Select id="ws-country" value={form.countryCode} onChange={update("countryCode")}>
                      {countries.length === 0 && <option value={form.countryCode}>{form.countryCode || "Select…"}</option>}
                      {countries.map((country) => (
                        <option key={country.code} value={country.code}>
                          {country.name}
                        </option>
                      ))}
                    </Select>
                  </Field>
                </div>

                <div className="grid gap-4 sm:grid-cols-3">
                  <Field label="Currency" htmlFor="ws-currency" hint="New documents only">
                    <Select id="ws-currency" value={form.currency} onChange={update("currency")}>
                      {currencies.length === 0 && <option value={form.currency}>{form.currency}</option>}
                      {currencies.map((currency) => (
                        <option key={currency.code} value={currency.code}>
                          {currency.code} · {currency.name}
                        </option>
                      ))}
                    </Select>
                  </Field>
                  <Field label="Timezone" htmlFor="ws-timezone">
                    <Select id="ws-timezone" value={form.timezone} onChange={update("timezone")}>
                      {zones.map((timezone) => (
                        <option key={timezone} value={timezone}>
                          {timezone.replaceAll("_", " ")}
                        </option>
                      ))}
                    </Select>
                  </Field>
                  <Field label="Language & formats" htmlFor="ws-locale">
                    <Select id="ws-locale" value={form.locale} onChange={update("locale")}>
                      {LOCALES.map((locale) => (
                        <option key={locale.code} value={locale.code}>
                          {locale.label}
                        </option>
                      ))}
                    </Select>
                  </Field>
                </div>

                <Field
                  label={taxLabel}
                  htmlFor="ws-tax"
                  hint="Optional. Only add it if your business is registered for it."
                >
                  <Input id="ws-tax" value={form.taxNumber} onChange={update("taxNumber")} />
                </Field>

                <div className="flex justify-end">
                  <Button type="submit" variant="secondary" loading={save.isPending}>
                    Save workspace
                  </Button>
                </div>
              </form>
            )}
          </SectionCard>

          <SectionCard
            title="Regional behaviour"
            description="How these settings apply, and what they never rewrite"
          >
            <ul className="space-y-3 text-[13px] leading-relaxed text-ink-600">
              <li className="flex gap-2.5">
                <Globe2 className="mt-0.5 h-4 w-4 shrink-0 text-ink-400" />
                <span>
                  Currency, language and timezone drive formatting for new quotations, invoices and
                  reports. Currency precision follows the currency itself — Japanese yen never shows
                  decimals.
                </span>
              </li>
              <li className="flex gap-2.5">
                <Building2 className="mt-0.5 h-4 w-4 shrink-0 text-ink-400" />
                <span>
                  Documents already issued keep their currency, amounts and numbering. Changing a
                  setting today cannot alter a document your customer has already received.
                </span>
              </li>
              <li className="flex gap-2.5">
                <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-ink-400" />
                <span>
                  Business type changes vocabulary only. Quotations, scheduling, invoicing and payments
                  behave identically for every industry.
                </span>
              </li>
            </ul>
          </SectionCard>
        </div>

        <div className="space-y-4">
          <Card className="p-5">
            <div className="flex items-center gap-2 text-ink-500">
              <Building2 className="h-4 w-4" />
              <p className="text-[12px] font-semibold uppercase tracking-[0.12em]">Workspace record</p>
            </div>
            <dl className="mt-4 space-y-3 text-sm">
              <div className="flex justify-between gap-3">
                <dt className="text-ink-500">Workspace address</dt>
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
              <div className="flex justify-between gap-3">
                <dt className="text-ink-500">Created</dt>
                <dd className="font-medium text-ink-900">
                  {workspace.data?.onboardedAt ? formatDateTime(workspace.data.onboardedAt, form.timezone) : "—"}
                </dd>
              </div>
            </dl>
            <p className="mt-4 text-[12px] leading-relaxed text-ink-400">
              The workspace address is permanent and used for support and exports.
            </p>
          </Card>

          <Card className="p-5">
            <div className="flex items-center gap-2 text-ink-500">
              <ShieldCheck className="h-4 w-4" />
              <p className="text-[12px] font-semibold uppercase tracking-[0.12em]">Security posture</p>
            </div>
            <ul className="mt-3 space-y-2 text-[13px] leading-relaxed text-ink-600">
              <li>• Short-lived access tokens with rotating refresh tokens and reuse detection</li>
              <li>• Rate limiting enforced across every API instance, not per server</li>
              <li>• Every record is scoped to your workspace on the server</li>
              <li>• Sensitive actions land in the audit log</li>
              <li>• Roles: owner, admin, manager, technician</li>
            </ul>
          </Card>
        </div>
      </div>
    </div>
  );
}