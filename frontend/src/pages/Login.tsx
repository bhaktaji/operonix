import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { Eye, EyeOff } from "lucide-react";
import { useAuth } from "@/auth/AuthProvider";
import { AuthShell } from "@/components/AuthShell";
import { Button, ErrorBanner, Field, Input } from "@/components/ui/primitives";
import { errorMessage } from "@/api/client";

export function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const destination = (location.state as { from?: string } | null)?.from ?? "/";

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setPending(true);
    try {
      await login(email.trim(), password);
      navigate(destination, { replace: true });
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setPending(false);
    }
  };

  return (
    <AuthShell>
      <div>
        <p className="kicker">Welcome back</p>
        <h1 className="mt-2 font-display text-[28px] font-semibold tracking-tight text-ink-950">
          Sign in to your workspace
        </h1>
        <p className="mt-2 text-sm text-ink-500">
          Pick up where you left off — quotes, crews and collections in one place.
        </p>

        <form onSubmit={onSubmit} className="mt-7 space-y-4" noValidate>
          {error && <ErrorBanner title="Couldn’t sign you in" message={error} />}

          <Field label="Work email" htmlFor="email">
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

          <Field label="Password" htmlFor="password">
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

          <Button type="submit" variant="secondary" className="w-full" loading={pending}>
            Sign in
          </Button>
        </form>

        <p className="mt-6 text-center text-sm text-ink-500">
          New to Operonix?{" "}
          <Link to="/register" className="font-semibold text-brand-700 hover:text-brand-800">
            Create your workspace
          </Link>
        </p>
      </div>
    </AuthShell>
  );
}