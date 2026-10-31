import type { ReactNode } from "react";
import { Link, Navigate, Outlet, useLocation } from "react-router-dom";
import { ShieldAlert } from "lucide-react";
import { useAuth } from "./AuthProvider";
import type { Role } from "@/api/types";

export function SplashScreen() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-ink-950">
      <div className="flex flex-col items-center gap-4">
        <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-brand-500/15 text-brand-300 ring-1 ring-inset ring-brand-400/30">
          <span className="font-display text-xl font-semibold">O</span>
        </div>
        <div className="h-1 w-24 overflow-hidden rounded-full bg-white/10">
          <div className="h-full w-1/2 animate-pulse rounded-full bg-brand-400" />
        </div>
      </div>
    </div>
  );
}

export function RequireAuth() {
  const { status, user } = useAuth();
  const location = useLocation();

  if (status === "loading") {
    return <SplashScreen />;
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <Outlet />;
}

export function PublicOnly({ children }: { children: ReactNode }) {
  const { status, user } = useAuth();
  if (status === "loading") {
    return <SplashScreen />;
  }
  if (user) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

export function RoleGate({ allow, children }: { allow: Role[]; children: ReactNode }) {
  const { user } = useAuth();
  if (!user || !allow.includes(user.role)) {
    return (
      <div className="mx-auto mt-10 max-w-lg">
        <div className="card p-8 text-center">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl2 bg-rose-50 text-rose-500">
            <ShieldAlert className="h-6 w-6" />
          </div>
          <h1 className="font-display text-lg font-semibold text-ink-950">You don&apos;t have access</h1>
          <p className="mt-1.5 text-sm text-ink-500">
            This area is limited to workspace managers. Ask an owner or admin if you need access.
          </p>
          <div className="mt-6">
            <Link
              to="/"
              className="inline-flex h-10 items-center rounded-lg border border-sand-300 bg-white px-4 text-sm font-medium text-ink-800 transition hover:bg-sand-50"
            >
              Back to dashboard
            </Link>
          </div>
        </div>
      </div>
    );
  }
  return <>{children}</>;
}