import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { authApi } from "@/api/endpoints";
import { ApiError, refreshSession, subscribeSession, tokens } from "@/api/client";
import type { Role, TenantSummary, UserSummary } from "@/api/types";

interface AuthContextValue {
  status: "loading" | "ready";
  user: UserSummary | null;
  tenant: TenantSummary | null;
  login: (email: string, password: string) => Promise<void>;
  register: (input: { orgName: string; fullName: string; email: string; password: string }) => Promise<void>;
  logout: () => Promise<void>;
  hasRole: (...roles: Role[]) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<"loading" | "ready">("loading");
  const [user, setUser] = useState<UserSummary | null>(null);
  const [tenant, setTenant] = useState<TenantSummary | null>(null);

  useEffect(() => {
    let active = true;

    const unsubscribe = subscribeSession((session) => {
      if (!active) return;
      setUser(session.user);
      setTenant(session.tenant);
    });

    (async () => {
      if (tokens.refresh) {
        const restored = await refreshSession();
        if (restored && tokens.access) {
          try {
            const me = await authApi.me();
            if (active) {
              setUser(me.user);
              setTenant(me.tenant);
            }
          } catch (error) {
            // A 401/403 means the session is genuinely gone. A network or 5xx failure does not:
            // keep the refresh token so the user is not signed out by a backend restart.
            if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
              tokens.clear();
            }
          }
        }
      }
      if (active) {
        setStatus("ready");
      }
    })();

    return () => {
      active = false;
      unsubscribe();
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const session = await authApi.login(email, password);
    tokens.set(session);
    setUser(session.user);
    setTenant(session.tenant);
  }, []);

  const register = useCallback(async (input: { orgName: string; fullName: string; email: string; password: string }) => {
    const session = await authApi.register(input);
    tokens.set(session);
    setUser(session.user);
    setTenant(session.tenant);
  }, []);

  const logout = useCallback(async () => {
    const refresh = tokens.refresh;
    tokens.clear();
    setUser(null);
    setTenant(null);
    if (refresh) {
      await authApi.logout(refresh).catch(() => undefined);
    }
  }, []);

  const hasRole = useCallback((...roles: Role[]) => !!user && roles.includes(user.role), [user]);

  const value = useMemo<AuthContextValue>(() => ({
    status, user, tenant, login, register, logout, hasRole,
  }), [status, user, tenant, login, register, logout, hasRole]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return context;
}