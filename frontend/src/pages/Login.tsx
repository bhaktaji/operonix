import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ArrowRight, Eye, EyeOff } from "lucide-react";
import { useAuth } from "@/auth/AuthProvider";
import { AuthShell } from "@/components/AuthShell";
import { Button, ErrorBanner, Field, Input } from "@/components/ui/primitives";
import { ApiError, errorMessage } from "@/api/client";
import { useI18n } from "@/i18n/I18nProvider";
import type { WorkspaceOption } from "@/api/types";

export function Login() {
  const { login } = useAuth();
  const { t } = useI18n();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [workspace, setWorkspace] = useState<string | undefined>(undefined);
  const [choices, setChoices] = useState<WorkspaceOption[]>([]);
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const destination = (location.state as { from?: string } | null)?.from ?? "/";

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setPending(true);
    try {
      await login(email.trim(), password, workspace);
      navigate(destination, { replace: true });
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === "WORKSPACE_REQUIRED") {
        const options = Array.isArray(caught.properties.workspaces)
          ? (caught.properties.workspaces as WorkspaceOption[])
          : [];
        setChoices(options);
        setWorkspace(options[0]?.slug);
        setError(null);
      } else {
        setError(errorMessage(caught));
      }
    } finally {
      setPending(false);
    }
  };

  return (
    <AuthShell>
      <div>
        <p className="kicker">Welcome back</p>
        <h1 className="mt-2 font-display text-[30px] font-semibold leading-tight tracking-tight text-ink-950">
          {t("auth.signIn.title")}
        </h1>
        <p className="mt-2 text-sm leading-relaxed text-ink-500">{t("auth.signIn.subtitle")}</p>

        <form onSubmit={onSubmit} className="mt-8 space-y-4" noValidate>
          {error && <ErrorBanner title="Couldn’t sign you in" message={error} />}

          <Field label={t("auth.signIn.email")} htmlFor="email">
            <Input
              id="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="you@company.com"
            />
          </Field>

          <Field label={t("auth.signIn.password")} htmlFor="password">
            <div className="relative">
              <Input
                id="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder="Your password"
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

          {choices.length > 0 && (
            <Field
              label="Workspace"
              htmlFor="workspace"
              hint="This email belongs to more than one workspace. Choose the one to open."
            >
              <select
                id="workspace"
                className="input"
                value={workspace}
                onChange={(event) => setWorkspace(event.target.value)}
              >
                {choices.map((choice) => (
                  <option key={choice.slug} value={choice.slug}>
                    {choice.name} · {choice.role.toLowerCase()}
                  </option>
                ))}
              </select>
            </Field>
          )}

          <Button variant="secondary" type="submit" className="w-full" loading={pending}>
            {t("auth.signIn.submit")}
            {!pending && <ArrowRight className="h-4 w-4" />}
          </Button>
        </form>

        <p className="mt-7 text-center text-sm text-ink-500">
          {t("auth.signIn.newHere")}{" "}
          <Link to="/register" className="font-semibold text-brand-700 hover:text-brand-800">
            {t("auth.signIn.createWorkspace")}
          </Link>
        </p>
      </div>
    </AuthShell>
  );
}