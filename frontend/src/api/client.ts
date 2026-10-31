import type { AuthResponse } from "./types";

const API_BASE = (import.meta.env.VITE_API_URL as string | undefined) ?? "/api/v1";
const REFRESH_KEY = "operonix.refresh";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: { field: string; message: string }[];
  /** Extra problem-detail properties (e.g. the workspace list when a sign-in needs one). */
  readonly properties: Record<string, unknown>;

  constructor(status: number, code: string, message: string,
              fieldErrors: { field: string; message: string }[] = [],
              properties: Record<string, unknown> = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.properties = properties;
  }
}

type SessionListener = (session: AuthResponse) => void;
const listeners = new Set<SessionListener>();

export function subscribeSession(listener: SessionListener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Access token lives only in memory; the refresh token survives reloads. */
export const tokens = {
  access: null as string | null,
  refresh: localStorage.getItem(REFRESH_KEY),
  set(session: AuthResponse) {
    this.access = session.accessToken;
    this.refresh = session.refreshToken;
    localStorage.setItem(REFRESH_KEY, session.refreshToken);
    listeners.forEach((listener) => listener(session));
  },
  clear() {
    this.access = null;
    this.refresh = null;
    localStorage.removeItem(REFRESH_KEY);
  },
};

let refreshInFlight: Promise<boolean> | null = null;

export async function refreshSession(): Promise<boolean> {
  if (refreshInFlight) {
    return refreshInFlight;
  }
  const token = tokens.refresh;
  if (!token) {
    return false;
  }
  refreshInFlight = (async () => {
    try {
      const response = await fetch(`${API_BASE}/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: token }),
      });
      if (!response.ok) {
        // Only a rejected token ends the session. A 5xx (API restarting, proxy error) must keep
        // the refresh token so reloading once the backend is back restores the session.
        if (response.status === 400 || response.status === 401 || response.status === 403) {
          tokens.clear();
        }
        return false;
      }
      const session = (await response.json()) as AuthResponse;
      tokens.set(session);
      return true;
    } catch {
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

async function toApiError(response: Response): Promise<ApiError> {
  let code = "INTERNAL_ERROR";
  let message = `Request failed (${response.status})`;
  let fieldErrors: ApiError["fieldErrors"] = [];
  let properties: Record<string, unknown> = {};
  try {
    const body = (await response.json()) as {
      code?: string;
      detail?: string;
      title?: string;
      errors?: { field: string; message: string }[];
    } & Record<string, unknown>;
    code = body.code ?? code;
    message = body.detail ?? body.title ?? message;
    fieldErrors = body.errors ?? [];
    properties = body;
  } catch {
    // Non-JSON error body: usually the dev proxy or a gateway when the API is down.
    if (response.status >= 500) {
      message = "The Operonix API is unavailable. Check that the backend is running.";
    }
  }
  return new ApiError(response.status, code, message, fieldErrors, properties);
}

export async function request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (tokens.access) {
    headers.set("Authorization", `Bearer ${tokens.access}`);
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR",
      "Cannot reach the Operonix API. Check that the backend is running.");
  }

  if (response.status === 401 && retry && tokens.refresh && !path.startsWith("/auth/")) {
    const refreshed = await refreshSession();
    if (refreshed) {
      return request<T>(path, init, false);
    }
  }

  if (!response.ok) {
    throw await toApiError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export function qs(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, String(value));
    }
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return "Something went wrong. Please try again.";
}

export function fieldErrors(error: unknown): Record<string, string> {
  if (error instanceof ApiError) {
    return Object.fromEntries(error.fieldErrors.map((item) => [item.field, item.message]));
  }
  return {};
}

export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 403 || error.code === "FORBIDDEN");
}