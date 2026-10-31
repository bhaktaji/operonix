import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { Link } from "react-router-dom";
import { financeApi } from "@/api/finance";
import type { LegalEntity, NumberRule, TaxProfile } from "@/api/finance";
import { referenceApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { Button, Card, ErrorBanner, Field, Input, PageHeader, SectionCard, Select, Skeleton } from "@/components/ui/primitives";
import { useToast } from "@/components/ui/toast";
import { formatDate } from "@/lib/format";

/**
 * Financial configuration for a workspace: who sells, from where, under which tax rules, with which
 * numbering and FX rates. Everything here is data the document services read — nothing on this page
 * rewrites an issued document.
 */
export function FinanceSettings() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const reference = useQuery({ queryKey: ["reference"], queryFn: referenceApi.all, staleTime: 60 * 60 * 1000 });
  const entities = useQuery({ queryKey: ["legal-entities"], queryFn: financeApi.legalEntities.list });
  const locations = useQuery({ queryKey: ["business-locations"], queryFn: financeApi.locations.list });
  const definitions = useQuery({ queryKey: ["tax-definitions"], queryFn: financeApi.tax.definitions });
  const profiles = useQuery({ queryKey: ["tax-profiles"], queryFn: financeApi.tax.profiles });
  const rules = useQuery({ queryKey: ["number-rules"], queryFn: financeApi.numbering.list });
  const rates = useQuery({ queryKey: ["exchange-rates"], queryFn: financeApi.rates.list });
  const settings = useQuery({ queryKey: ["financial-settings"], queryFn: financeApi.settings.get });

  const [error, setError] = useState<string | null>(null);
  const countries = reference.data?.countries ?? [];
  const currencies = reference.data?.currencies ?? [];
  const defaultEntity = entities.data?.find((entity) => entity.defaultEntity) ?? entities.data?.[0];
  const defaultProfile = profiles.data?.find((profile) => profile.defaultProfile) ?? profiles.data?.[0];

  const [entityForm, setEntityForm] = useState({ name: "", countryCode: "", baseCurrency: "", taxNumber: "", taxMode: "EXCLUSIVE" as "EXCLUSIVE" | "INCLUSIVE" });
  const [locationForm, setLocationForm] = useState({ name: "", countryCode: "", timezone: "UTC" });
  const [profileForm, setProfileForm] = useState({ name: "", taxDefinitionId: "" });
  const [rateForm, setRateForm] = useState({ base: "", quote: "", rate: "" });
  const [ruleDrafts, setRuleDrafts] = useState<Record<string, { prefix: string; padding: number; resetPolicy: string }>>({});

  useEffect(() => {
    if (!defaultEntity) return;
    setEntityForm((current) => current.countryCode
      ? current
      : { ...current, countryCode: defaultEntity.countryCode, baseCurrency: defaultEntity.baseCurrency });
    setLocationForm((current) => current.countryCode
      ? current
      : { ...current, countryCode: defaultEntity.countryCode, timezone: "UTC" });
  }, [defaultEntity]);

  useEffect(() => {
    if (rules.data && Object.keys(ruleDrafts).length === 0) {
      setRuleDrafts(Object.fromEntries(rules.data.map((rule) => [rule.docType, {
        prefix: rule.prefix, padding: rule.padding, resetPolicy: rule.resetPolicy,
      }])));
    }
  }, [rules.data, ruleDrafts]);

  const invalidate = () => {
    ["legal-entities", "business-locations", "tax-profiles", "number-rules", "exchange-rates", "financial-settings"]
      .forEach((key) => queryClient.invalidateQueries({ queryKey: [key] }));
  };

  const run = <T,>(action: () => Promise<T>, message: string) =>
    action()
      .then(() => { setError(null); toast.push({ title: message }); invalidate(); })
      .catch((cause) => setError(errorMessage(cause)));

  const createEntity = useMutation({
    mutationFn: () => financeApi.legalEntities.create({
      name: entityForm.name,
      countryCode: entityForm.countryCode,
      baseCurrency: entityForm.baseCurrency,
      taxNumber: entityForm.taxNumber || null,
      taxMode: entityForm.taxMode,
    }),
    onSuccess: () => { toast.push({ title: "Legal entity added" }); setEntityForm({ ...entityForm, name: "", taxNumber: "" }); invalidate(); },
    onError: (cause: unknown) => setError(errorMessage(cause)),
  });

  const createLocation = useMutation({
    mutationFn: () => financeApi.locations.create({
      name: locationForm.name,
      countryCode: locationForm.countryCode,
      timezone: locationForm.timezone,
      legalEntityId: defaultEntity?.id ?? null,
    }),
    onSuccess: () => { toast.push({ title: "Location added" }); setLocationForm({ ...locationForm, name: "" }); invalidate(); },
    onError: (cause: unknown) => setError(errorMessage(cause)),
  });

  const createProfile = useMutation({
    mutationFn: () => financeApi.tax.createProfile({
      name: profileForm.name,
      taxDefinitionId: profileForm.taxDefinitionId,
    }),
    onSuccess: () => { toast.push({ title: "Tax profile added" }); setProfileForm({ name: "", taxDefinitionId: "" }); invalidate(); },
    onError: (cause: unknown) => setError(errorMessage(cause)),
  });

  const createRate = useMutation({
    mutationFn: () => financeApi.rates.create({
      base: rateForm.base,
      quote: rateForm.quote,
      rate: Number(rateForm.rate),
    }),
    onSuccess: () => { toast.push({ title: "Rate saved" }); setRateForm({ base: "", quote: "", rate: "" }); invalidate(); },
    onError: (cause: unknown) => setError(errorMessage(cause)),
  });

  const currencyOptions = useMemo(() => currencies.map((currency) => ({
    value: currency.code,
    label: `${currency.code} — ${currency.name}`,
  })), [currencies]);

  const definitionOptions = useMemo(() => (definitions.data ?? []).map((definition) => ({
    value: definition.id,
    label: `${definition.name} · ${definition.countryCode ?? "Global"} · ${definition.mode.toLowerCase()}`,
  })), [definitions.data]);

  const loading = entities.isLoading || profiles.isLoading || settings.isLoading;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Financial settings"
        description="Who sells, from where, under which tax rules — and how documents are numbered."
        actions={
          <Link to="/settings" className="inline-flex">
            <Button variant="ghost" size="sm"><ArrowLeft className="mr-1.5 h-4 w-4" /> Workspace settings</Button>
          </Link>
        }
      />

      {error && <ErrorBanner message={error} />}

      {loading ? (
        <Card><Skeleton className="h-40 w-full" /></Card>
      ) : (
        <div className="grid gap-6 xl:grid-cols-2">
          <SectionCard
            title="Selling entities"
            description="The legal party that issues your documents. Invoices snapshot the entity and its tax number."
          >
            <div className="space-y-3">
              {(entities.data ?? []).map((entity: LegalEntity) => (
                <div key={entity.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-line/70 bg-surface/50 px-3 py-2.5">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium text-ink-900">
                      {entity.name}
                      {entity.defaultEntity && <span className="ml-2 text-[11px] uppercase tracking-wide text-brand-700">default</span>}
                    </p>
                    <p className="text-xs text-ink-500">
                      {entity.countryCode} · {entity.baseCurrency} · {entity.taxMode.toLowerCase()}
                      {entity.taxNumber ? ` · ${entity.taxNumberLabel ?? "Tax ID"} ${entity.taxNumber}` : ""}
                    </p>
                  </div>
                </div>
              ))}
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Entity name"><Input value={entityForm.name} onChange={(event) => setEntityForm({ ...entityForm, name: event.target.value })} placeholder="Acme Trading LLP" /></Field>
                <Field label="Country">
                  <Select value={entityForm.countryCode} onChange={(event) => setEntityForm({ ...entityForm, countryCode: event.target.value })}>
                    <option value="">Select country</option>
                    {countries.map((country) => <option key={country.code} value={country.code}>{country.name}</option>)}
                  </Select>
                </Field>
                <Field label="Base currency">
                  <Select value={entityForm.baseCurrency} onChange={(event) => setEntityForm({ ...entityForm, baseCurrency: event.target.value })}>
                    <option value="">Select currency</option>
                    {currencyOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                  </Select>
                </Field>
                <Field label="Tax number (optional)"><Input value={entityForm.taxNumber} onChange={(event) => setEntityForm({ ...entityForm, taxNumber: event.target.value })} /></Field>
                <Field label="Default pricing mode">
                  <Select value={entityForm.taxMode} onChange={(event) => setEntityForm({ ...entityForm, taxMode: event.target.value as "EXCLUSIVE" | "INCLUSIVE" })}>
                    <option value="EXCLUSIVE">Tax added on top (exclusive)</option>
                    <option value="INCLUSIVE">Price includes tax (inclusive)</option>
                  </Select>
                </Field>
              </div>
              <Button
                size="sm"
                disabled={createEntity.isPending || !entityForm.name || !entityForm.countryCode || !entityForm.baseCurrency}
                onClick={() => createEntity.mutate()}
              >
                Add entity
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title="Business locations"
            description="Operational sites. Customer addresses live on the customer record."
          >
            <div className="space-y-3">
              {(locations.data ?? []).map((location) => (
                <div key={location.id} className="rounded-xl border border-line/70 bg-surface/50 px-3 py-2.5">
                  <p className="text-sm font-medium text-ink-900">
                    {location.name}
                    {location.defaultLocation && <span className="ml-2 text-[11px] uppercase tracking-wide text-brand-700">default</span>}
                  </p>
                  <p className="text-xs text-ink-500">{location.countryCode} · {location.timezone}</p>
                </div>
              ))}
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Location name"><Input value={locationForm.name} onChange={(event) => setLocationForm({ ...locationForm, name: event.target.value })} placeholder="Depot — North" /></Field>
                <Field label="Country">
                  <Select value={locationForm.countryCode} onChange={(event) => setLocationForm({ ...locationForm, countryCode: event.target.value })}>
                    <option value="">Select country</option>
                    {countries.map((country) => <option key={country.code} value={country.code}>{country.name}</option>)}
                  </Select>
                </Field>
                <Field label="Time zone"><Input value={locationForm.timezone} onChange={(event) => setLocationForm({ ...locationForm, timezone: event.target.value })} /></Field>
              </div>
              <Button
                size="sm"
                disabled={createLocation.isPending || !locationForm.name || !locationForm.countryCode}
                onClick={() => createLocation.mutate()}
              >
                Add location
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title="Tax profiles"
            description="Named tax setups your catalog items can use. Issued documents keep the profile they were created with."
          >
            <div className="space-y-3">
              {(profiles.data ?? []).map((profile: TaxProfile) => (
                <div key={profile.id} className="rounded-xl border border-line/70 bg-surface/50 px-3 py-2.5">
                  <p className="text-sm font-medium text-ink-900">
                    {profile.name}
                    {profile.defaultProfile && <span className="ml-2 text-[11px] uppercase tracking-wide text-brand-700">default</span>}
                  </p>
                  <p className="text-xs text-ink-500">
                    {profile.effectiveMode.toLowerCase()} · {(profile.totalRate * 100).toFixed(2)}% ·{" "}
                    {profile.components.map((component) => `${component.code} ${(component.rate * 100).toFixed(2)}%`).join(" + ")}
                  </p>
                </div>
              ))}
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Profile name"><Input value={profileForm.name} onChange={(event) => setProfileForm({ ...profileForm, name: event.target.value })} placeholder="Domestic standard" /></Field>
                <Field label="Tax rule">
                  <Select value={profileForm.taxDefinitionId} onChange={(event) => setProfileForm({ ...profileForm, taxDefinitionId: event.target.value })}>
                    <option value="">Select a tax rule</option>
                    {definitionOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                  </Select>
                </Field>
              </div>
              <Button
                size="sm"
                disabled={createProfile.isPending || !profileForm.name || !profileForm.taxDefinitionId}
                onClick={() => createProfile.mutate()}
              >
                Add tax profile
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title="Document numbering"
            description="The default reproduces the numbers you already have; changes only affect new documents."
          >
            <div className="space-y-3">
              {(rules.data ?? []).map((rule: NumberRule) => {
                const draft = ruleDrafts[rule.docType] ?? { prefix: rule.prefix, padding: rule.padding, resetPolicy: rule.resetPolicy };
                return (
                  <div key={rule.docType} className="grid items-end gap-3 rounded-xl border border-line/70 bg-surface/50 px-3 py-3 sm:grid-cols-[1fr_90px_1fr_auto]">
                    <Field label={rule.docType.toLowerCase()}>
                      <Input
                        value={draft.prefix}
                        onChange={(event) => setRuleDrafts({ ...ruleDrafts, [rule.docType]: { ...draft, prefix: event.target.value.toUpperCase() } })}
                      />
                    </Field>
                    <Field label="Digits">
                      <Input
                        type="number"
                        min={1}
                        max={10}
                        value={draft.padding}
                        onChange={(event) => setRuleDrafts({ ...ruleDrafts, [rule.docType]: { ...draft, padding: Number(event.target.value) } })}
                      />
                    </Field>
                    <Field label="Resets">
                      <Select
                        value={draft.resetPolicy}
                        onChange={(event) => setRuleDrafts({ ...ruleDrafts, [rule.docType]: { ...draft, resetPolicy: event.target.value } })}
                      >
                        <option value="YEARLY">Every year</option>
                        <option value="NEVER">Never</option>
                      </Select>
                    </Field>
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => run(() => financeApi.numbering.update(rule.docType, {
                        prefix: draft.prefix,
                        includeYear: true,
                        padding: draft.padding,
                        resetPolicy: draft.resetPolicy,
                      }), "Numbering updated")}
                    >
                      Save
                    </Button>
                  </div>
                );
              })}
            </div>
          </SectionCard>

          <SectionCard
            title="Exchange rates"
            description="Used when a document is issued in a currency other than your base currency. The rate is frozen on the document."
          >
            <div className="space-y-3">
              {(rates.data ?? []).slice(0, 6).map((rate) => (
                <div key={`${rate.base}-${rate.quote}-${rate.asOf}`} className="flex items-center justify-between rounded-xl border border-line/70 bg-surface/50 px-3 py-2">
                  <p className="text-sm text-ink-900">{rate.base} → {rate.quote}</p>
                  <p className="text-xs text-ink-500">{rate.rate} · {formatDate(rate.asOf)}</p>
                </div>
              ))}
              {(rates.data ?? []).length === 0 && (
                <p className="text-sm text-ink-500">No rates yet. Single-currency workspaces never need one.</p>
              )}
              <div className="grid gap-3 sm:grid-cols-4">
                <Field label="From">
                  <Select value={rateForm.base} onChange={(event) => setRateForm({ ...rateForm, base: event.target.value })}>
                    <option value="">—</option>
                    {currencyOptions.map((option) => <option key={option.value} value={option.value}>{option.value}</option>)}
                  </Select>
                </Field>
                <Field label="To">
                  <Select value={rateForm.quote} onChange={(event) => setRateForm({ ...rateForm, quote: event.target.value })}>
                    <option value="">—</option>
                    {currencyOptions.map((option) => <option key={option.value} value={option.value}>{option.value}</option>)}
                  </Select>
                </Field>
                <Field label="Rate"><Input value={rateForm.rate} onChange={(event) => setRateForm({ ...rateForm, rate: event.target.value })} placeholder="0.012" /></Field>
                <Button
                  size="sm"
                  disabled={createRate.isPending || !rateForm.base || !rateForm.quote || !rateForm.rate}
                  onClick={() => createRate.mutate()}
                >
                  Add rate
                </Button>
              </div>
            </div>
          </SectionCard>

          <SectionCard
            title="Workspace preferences"
            description="Defaults for new documents. Existing documents keep their own values."
          >
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Default entity">
                <Select
                  value={settings.data?.defaultLegalEntityId ?? ""}
                  onChange={(event) => run(() => financeApi.settings.update({ defaultLegalEntityId: event.target.value }), "Default entity saved")}
                >
                  <option value="">—</option>
                  {(entities.data ?? []).map((entity) => <option key={entity.id} value={entity.id}>{entity.name}</option>)}
                </Select>
              </Field>
              <Field label="Default tax profile">
                <Select
                  value={settings.data?.defaultTaxProfileId ?? ""}
                  onChange={(event) => run(() => financeApi.settings.update({ defaultTaxProfileId: event.target.value }), "Default tax profile saved")}
                >
                  <option value="">—</option>
                  {(profiles.data ?? []).map((profile) => <option key={profile.id} value={profile.id}>{profile.name}</option>)}
                </Select>
              </Field>
              <Field label="Fiscal year starts">
                <Select
                  value={String(settings.data?.fiscalYearStartMonth ?? 1)}
                  onChange={(event) => run(() => financeApi.settings.update({ fiscalYearStartMonth: Number(event.target.value) }), "Fiscal year saved")}
                >
                  {["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
                    .map((month, index) => <option key={month} value={index + 1}>{month}</option>)}
                </Select>
              </Field>
              <Field label="Currency precision">
                <Input readOnly value={`${settings.data?.baseCurrency ?? ""} · ${settings.data?.currencyPrecision ?? 2} decimal${settings.data?.currencyPrecision === 1 ? "" : "s"}`} />
              </Field>
            </div>
            {defaultProfile && (
              <p className="mt-3 text-xs text-ink-500">
                New documents default to <strong className="font-medium text-ink-700">{defaultProfile.name}</strong>{" "}
                ({defaultProfile.effectiveMode.toLowerCase()}).
              </p>
            )}
          </SectionCard>
        </div>
      )}
    </div>
  );
}
