import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Eye, EyeOff } from "lucide-react";
import { useAuth } from "@/auth/AuthProvider";
import { AuthShell } from "@/components/AuthShell";
import { Button, ErrorBanner, Field, Input } from "@/components/ui/primitives";
import { errorMessage, fieldErrors } from "@/api/client";

export function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ orgName: "", fullName: "", email: "", password: "" });
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [pending, setPending] = useState(false);

  const update = (key: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setErrors({});
    setPending(true);
    try {
      await register({
        orgName: form.orgName.trim(),
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        password: form.password,
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
        <p className="kicker">Start free</p>
        <h1 className="mt-2 font-display text-[28px] font-semibold tracking-tight text-ink-950">
          Create your workspace
        </h1>
        <p className="mt-2 text-sm text-ink-500">
          You&apos;ll be the owner. Invite your crew once you&apos;re in.
        </p>

        <form onSubmit={onSubmit} className="mt-7 space-y-4" noValidate>
          {error && <ErrorBanner title="Couldn’t create the workspace" message={error} />}

          <Field label="Business name" htmlFor="orgName" error={errors.orgName}>
            <Input
              id="orgName"
              required
              autoComplete="organization"
              value={form.orgName}
              onChange={update("orgName")}
              placeholder="Sparkle & Co. Cleaning"
            />
          </Field>

          <Field label="Your name" htmlFor="fullName" error={errors.fullName}>
            <Input
              id="fullName"
              required
              autoComplete="name"
              value={form.fullName}
              onChange={update("fullName")}
              placeholder="Dana Reed"
            />
          </Field>

          <Field label="Work email" htmlFor="email" error={errors.email}>
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
            label="Password"
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

          <Button type="submit" variant="secondary" className="w-full" loading={pending}>
            Create workspace
          </Button>
        </form>

        <p className="mt-6 text-center text-sm text-ink-500">
          Already have an account?{" "}
          <Link to="/login" className="font-semibold text-brand-700 hover:text-brand-800">
            Sign in
          </Link>
        </p>
      </div>
    </AuthShell>
  );
}