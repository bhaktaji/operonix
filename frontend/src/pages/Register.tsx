import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, Check, Eye, EyeOff } from "lucide-react";
import { useAuth } from "@/auth/AuthProvider";
import { AuthShell } from "@/components/AuthShell";
import { Button, ErrorBanner, Field, Input, Select } from "@/components/ui/primitives";
import { referenceApi } from "@/api/endpoints";
import { errorMessage, fieldErrors } from "@/api/client";
import { browserTimezone } from "@/lib/format";
import { useI18n } from "@/i18n/I18nProvider";

const FALLBACK_TIMEZONES = [
  "UTC", "Asia/Kolkata", "Asia/Dubai", "Asia/Singapore", "Asia/Tokyo", "Asia/Kuala_Lumpur",
  "Europe/London", "Europe/Dublin", "Europe/Berlin", "Europe/Madrid", "Europe/Paris", "Europe/Amsterdam",
  "Europe/Lisbon", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
  "America/Toronto", "America/Mexico_City", "America/Sao_Paulo", "Australia/Sydney", "Pacific/Auckland",
  "Africa/Johannesburg",
];

function timezoneOptions(current: string): string[] {
  const supported = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
  const list = typeof supported === "function" ? supported("timeZone") : FALLBACK_TIMEZONES;
  const merged = new Set<string>([current, browserTimezone(), ...list]);
  return Array.from(merged).sort();
}

export function Register() {
  const { register } = useAuth();
  const { t } = useI18n();
  const navigate = useNavigate();
  const reference = useQuery({ queryKey: ["reference"], queryFn: referenceApi.all, staleTime: 60 * 60 * 1000 });

  const [step, setStep] = useState<1 | 2>(1);
  const [form, setForm] = useState({
    orgName: "",
    countryCode: "",
    industryTemplate: "GENERAL_SERVICE",
    currency: "",
    timezone: browserTimezone(),
    locale: "",
    fullName: "",
    email: "",
    password: "",
  });
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [pending, setPending] = useState(false);

  const countries = reference.data?.countries ?? [];
  const currencies = reference.data?.currencies ?? [];
  const industries = reference.data?.industryTemplates ?? [];
  const selectedCountry = countries.find((country) => country.code === form.countryCode);
  const zones = useMemo(() => timezoneOptions(form.timezone), [form.timezone]);

  // Country is the only required regional choice: everything else is derived and editable.
  useEffect(() => {
    if (!selectedCountry) {
      return;
    }
    setForm((current) => ({
      ...current,
      currency: current.currency || selectedCountry.defaultCurrency,
      locale: current.locale || selectedCountry.defaultLocale,
    }));
  }, [selectedCountry]);

  const update = (key: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  const businessStepValid = form.orgName.trim().length >= 2 && !!form.countryCode && !!form.currency;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (step === 1) {
      if (!businessStepValid) {
        setError("Add your business name and country to continue.");
        return;
      }
      setError(null);
      setStep(2);
      return;
    }
    setError(null);
    setErrors({});
    setPending(true);
    try {
      await register({
        orgName: form.orgName.trim(),
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        password: form.password,
        countryCode: form.countryCode,
        currency: form.currency,
        timezone: form.timezone,
        locale: form.locale || selectedCountry?.defaultLocale,
        industryTemplate: form.industryTemplate,
      });
      navigate("/", { replace: true });
    } catch (caught) {
      setError(errorMessage(caught));
      setErrors(fieldErrors(caught));
    } finally {
      setPending(false);
    }
  };

  return (
    <AuthShell>
      <div>
        <p className="kicker">{t("auth.register.kicker")}</p>
        <h1 className="mt-2 font-display text-[30px] font-semibold leading-tight tracking-tight text-ink-950">
          {t("auth.register.title")}
        </h1>
        <p className="mt-2 text-sm leading-relaxed text-ink-500">{t("auth.register.subtitle")}</p>

        <ol className="mt-6 flex items-center gap-3 text-[12px] font-medium">
          {[t("auth.register.step.business"), t("auth.register.step.access")].map((label, index) => {
            const number = index + 1;
            const state = step === number ? "current" : step > number ? "done" : "todo";
            return (
              <li key={label} className="flex items-center gap-2">
                <span
                  className={
                    state === "current"
                      ? "flex h-6 w-6 items-center justify-center rounded-full bg-ink-950 text-[11px] text-white"
                      : state === "done"
                        ? "flex h-6 w-6 items-center justify-center rounded-full bg-mint-100 text-mint-700"
                        : "flex h-6 w-6 items-center justify-center rounded-full bg-sand-200 text-[11px] text-ink-500"
                  }
                >
                  {state === "done" ? <Check className="h-3 w-3" /> : number}
                </span>
                <span className={state === "todo" ? "text-ink-400" : "text-ink-700"}>{label}</span>
                {index === 0 && <span className="mx-1 h-px w-6 bg-sand-300" />}
              </li>
            );
          })}
        </ol>

        <form onSubmit={onSubmit} className="mt-6 space-y-4" noValidate>
          {error && <ErrorBanner title="Couldn’t create the workspace" message={error} />}

          {step === 1 ? (
            <>
              <Field label={t("auth.register.business")} htmlFor="orgName" error={errors.orgName}>
                <Input
                  id="orgName"
                  required
                  autoComplete="organization"
                  value={form.orgName}
                  onChange={update("orgName")}
                  placeholder="Northwind Services"
                />
              </Field>

              <div className="grid gap-4 sm:grid-cols-2">
                <Field label={t("auth.register.country")} htmlFor="countryCode">
                  <Select id="countryCode" required value={form.countryCode} onChange={update("countryCode")}>
                    <option value="">{t("common.selectPlaceholder")}</option>
                    {countries.map((country) => (
                      <option key={country.code} value={country.code}>
                        {country.name}
                      </option>
                    ))}
                  </Select>
                </Field>

                <Field label={t("auth.register.industry")} htmlFor="industryTemplate">
                  <Select
                    id="industryTemplate"
                    value={form.industryTemplate}
                    onChange={update("industryTemplate")}
                  >
                    {industries.map((industry) => (
                      <option key={industry.code} value={industry.code}>
                        {industry.label}
                      </option>
                    ))}
                  </Select>
                </Field>
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <Field label={t("auth.register.currency")} htmlFor="currency" hint="Changeable later">
                  <Select id="currency" required value={form.currency} onChange={update("currency")}>
                    <option value="">{t("common.selectPlaceholder")}</option>
                    {currencies.map((currency) => (
                      <option key={currency.code} value={currency.code}>
                        {currency.code} · {currency.name}
                      </option>
                    ))}
                  </Select>
                </Field>

                <Field label={t("auth.register.timezone")} htmlFor="timezone">
                  <Select id="timezone" value={form.timezone} onChange={update("timezone")}>
                    {zones.map((zone) => (
                      <option key={zone} value={zone}>
                        {zone.replaceAll("_", " ")}
                      </option>
                    ))}
                  </Select>
                </Field>
              </div>

              <Button variant="secondary" type="submit" className="w-full" disabled={!businessStepValid}>
                Continue
                <ArrowRight className="h-4 w-4" />
              </Button>
            </>
          ) : (
            <>
              <button
                type="button"
                onClick={() => setStep(1)}
                className="inline-flex items-center gap-1 text-[12px] font-medium text-ink-500 hover:text-ink-800"
              >
                <ArrowLeft className="h-3.5 w-3.5" /> Back to business details
              </button>

              <div className="rounded-lg border border-sand-200 bg-sand-50 px-3.5 py-2.5 text-[12px] text-ink-600">
                <span className="font-medium text-ink-800">{form.orgName}</span>
                {selectedCountry && ` · ${selectedCountry.name}`} · {form.currency} ·{" "}
                {form.timezone.replaceAll("_", " ")}
              </div>

              <Field label={t("auth.register.yourName")} htmlFor="fullName" error={errors.fullName}>
                <Input
                  id="fullName"
                  required
                  autoComplete="name"
                  value={form.fullName}
                  onChange={update("fullName")}
                  placeholder="Dana Reed"
                />
              </Field>

              <Field label={t("auth.signIn.email")} htmlFor="email" error={errors.email}>
                <Input
                  id="email"
                  type="email"
                  required
                  autoComplete="email"
                  value={form.email}
                  onChange={update("email")}
                  placeholder="you@company.com"
                />
              </Field>

              <Field
                label={t("auth.signIn.password")}
                htmlFor="password"
                error={errors.password}
                hint="At least 12 characters, including a letter and a number."
              >
                <div className="relative">
                  <Input
                    id="password"
                    type={showPassword ? "text" : "password"}
                    required
                    autoComplete="new-password"
                    value={form.password}
                    onChange={update("password")}
                    placeholder="Pick a strong password"
                    className="pr-11"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((visible) => !visible)}
                    aria-label={showPassword ? "Hide password" : "Show password"}
                    className="absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-2 text-ink-400 transition hover:text-ink-700"
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </Field>

              <Button variant="secondary" type="submit" className="w-full" loading={pending}>
                {t("auth.register.submit")}
              </Button>

              <p className="text-[11px] leading-relaxed text-ink-400">{t("auth.register.optionalNote")}</p>
            </>
          )}
        </form>

        <p className="mt-6 text-center text-sm text-ink-500">
          {t("auth.register.haveAccount")}{" "}
          <Link to="/login" className="font-semibold text-brand-700 hover:text-brand-800">
            {t("auth.register.signIn")}
          </Link>
        </p>
      </div>
    </AuthShell>
  );
}