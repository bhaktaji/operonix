import { qs, request } from "./client";
import type {
  AuditEvent,
  AuthResponse,
  Customer,
  CustomerSummary,
  Dashboard,
  Invoice,
  InvoiceInput,
  Job,
  JobInput,
  MeResponse,
  Page,
  Payment,
  Quote,
  QuoteInput,
  ReferenceData,
  RevenueReport,
  Role,
  ServiceItem,
  ServiceLocation,
  ServiceUnit,
  TeamMember,
  Technician,
  Workspace,
  WorkspaceInput,
  RegisterInput,
} from "./types";

// ------------------------------------------------------------------ auth

export const authApi = {
  /** `workspace` is only needed when the same email belongs to more than one workspace. */
  login: (email: string, password: string, workspace?: string) =>
    request<AuthResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password, workspace }),
    }),
  register: (input: RegisterInput) =>
    request<AuthResponse>("/auth/register", { method: "POST", body: JSON.stringify(input) }),
  me: () => request<MeResponse>("/auth/me"),
  logout: (refreshToken: string) =>
    request<void>("/auth/logout", { method: "POST", body: JSON.stringify({ refreshToken }) }),
};

// ------------------------------------------------------------- workspace

export const workspaceApi = {
  get: () => request<Workspace>("/workspace"),
  update: (input: WorkspaceInput) =>
    request<Workspace>("/workspace", { method: "PUT", body: JSON.stringify(input) }),
};

// -------------------------------------------------------------- reference
export const referenceApi = {
  /** Public: countries, currencies and industry templates used by onboarding and settings. */
  all: () => request<ReferenceData>("/reference"),
};

// ------------------------------------------------------------------ team

export const teamApi = {
  list: () => request<TeamMember[]>("/team"),
  /** Name-only staff list for assignment pickers (manager and above). */
  assignable: () => request<Technician[]>("/team/assignable"),
  create: (input: { fullName: string; email: string; password: string; role: Role }) =>
    request<TeamMember>("/team", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: { fullName?: string; role?: Role; active?: boolean }) =>
    request<TeamMember>(`/team/${id}`, { method: "PUT", body: JSON.stringify(input) }),
};

// -------------------------------------------------------------- customers

export interface CustomerInput {
  name: string;
  contactName?: string;
  email?: string;
  phone?: string;
  notes?: string;
  billingAddress?: {
    line1: string;
    line2?: string;
    city: string;
    region?: string;
    postalCode: string;
    country: string;
  } | null;
  primaryLocation?: LocationInput | null;
}

export interface LocationInput {
  label: string;
  line1: string;
  line2?: string;
  city: string;
  region?: string;
  postalCode: string;
  country: string;
  accessNotes?: string;
  isPrimary: boolean;
}

export const customersApi = {
  list: (params: { q?: string; status?: string; page?: number; size?: number; sort?: string }) =>
    request<Page<CustomerSummary>>(`/customers${qs(params)}`),
  get: (id: string) => request<Customer>(`/customers/${id}`),
  create: (input: CustomerInput) =>
    request<Customer>("/customers", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: CustomerInput) =>
    request<Customer>(`/customers/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  archive: (id: string) => request<Customer>(`/customers/${id}/archive`, { method: "POST" }),
  restore: (id: string) => request<Customer>(`/customers/${id}/restore`, { method: "POST" }),
  locations: (id: string) => request<ServiceLocation[]>(`/customers/${id}/locations`),
  addLocation: (id: string, input: LocationInput) =>
    request<ServiceLocation>(`/customers/${id}/locations`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  updateLocation: (id: string, input: LocationInput) =>
    request<ServiceLocation>(`/locations/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteLocation: (id: string) => request<void>(`/locations/${id}`, { method: "DELETE" }),
};

// --------------------------------------------------------------- catalog

export interface ServiceInput {
  name: string;
  description?: string;
  unit: ServiceUnit;
  basePrice: number;
  durationMinutes: number;
  taxRate: number;
  taxProfileId?: string | null;
  active: boolean;
}

export const servicesApi = {
  list: (params: { q?: string; active?: string } = {}) =>
    request<ServiceItem[]>(`/services${qs(params)}`),
  create: (input: ServiceInput) =>
    request<ServiceItem>("/services", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: ServiceInput) =>
    request<ServiceItem>(`/services/${id}`, { method: "PUT", body: JSON.stringify(input) }),
};

// ---------------------------------------------------------------- quotes

export const quotesApi = {
  list: (params: { status?: string; customerId?: string; q?: string; page?: number; size?: number; sort?: string }) =>
    request<Page<Quote>>(`/quotes${qs(params)}`),
  get: (id: string) => request<Quote>(`/quotes/${id}`),
  create: (input: QuoteInput) =>
    request<Quote>("/quotes", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: QuoteInput) =>
    request<Quote>(`/quotes/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  remove: (id: string) => request<void>(`/quotes/${id}`, { method: "DELETE" }),
  send: (id: string) => request<Quote>(`/quotes/${id}/send`, { method: "POST" }),
  accept: (id: string) => request<Quote>(`/quotes/${id}/accept`, { method: "POST" }),
  decline: (id: string) => request<Quote>(`/quotes/${id}/decline`, { method: "POST" }),
  convert: (id: string, input: {
    scheduledStart: string;
    scheduledEnd: string;
    technicianIds: string[];
    title?: string;
  }) => request<Job>(`/quotes/${id}/convert`, { method: "POST", body: JSON.stringify(input) }),
};

// ------------------------------------------------------------------ jobs

export const jobsApi = {
  list: (params: {
    from?: string;
    to?: string;
    status?: string;
    technicianId?: string;
    customerId?: string;
    mine?: boolean;
    page?: number;
    size?: number;
    sort?: string;
  }) => request<Page<Job>>(`/jobs${qs(params)}`),
  get: (id: string) => request<Job>(`/jobs/${id}`),
  create: (input: JobInput) =>
    request<Job>("/jobs", { method: "POST", body: JSON.stringify(input) }),
  createSeries: (input: JobInput & { recurrence: string; occurrences: number }) =>
    request<Job[]>("/jobs/series", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: JobInput) =>
    request<Job>(`/jobs/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  assign: (id: string, userId: string) =>
    request<Job>(`/jobs/${id}/assignments`, {
      method: "POST",
      body: JSON.stringify({ userId }),
    }),
  unassign: (id: string, userId: string) =>
    request<Job>(`/jobs/${id}/assignments/${userId}`, { method: "DELETE" }),
  transition: (id: string, to: string, note?: string) =>
    request<Job>(`/jobs/${id}/transitions`, {
      method: "POST",
      body: JSON.stringify({ to, note }),
    }),
  invoice: (id: string, input: { dueDate?: string; notes?: string; discountAmount?: number } = {}) =>
    request<Invoice>(`/jobs/${id}/invoice`, { method: "POST", body: JSON.stringify(input) }),
};

// -------------------------------------------------------------- invoices

export const invoicesApi = {
  list: (params: {
    status?: string;
    customerId?: string;
    overdue?: boolean;
    q?: string;
    page?: number;
    size?: number;
    sort?: string;
  }) => request<Page<Invoice>>(`/invoices${qs(params)}`),
  get: (id: string) => request<Invoice>(`/invoices/${id}`),
  create: (input: InvoiceInput) =>
    request<Invoice>("/invoices", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: Omit<InvoiceInput, "jobId">) =>
    request<Invoice>(`/invoices/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  send: (id: string) => request<Invoice>(`/invoices/${id}/send`, { method: "POST" }),
  void: (id: string, reason: string) =>
    request<Invoice>(`/invoices/${id}/void`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
  addPayment: (id: string, input: {
    amount: number;
    method: string;
    reference?: string;
    paidAt?: string;
  }) => request<Payment>(`/invoices/${id}/payments`, {
    method: "POST",
    body: JSON.stringify(input),
  }),
  reversePayment: (invoiceId: string, paymentId: string) =>
    request<Invoice>(`/invoices/${invoiceId}/payments/${paymentId}`, { method: "DELETE" }),
};

// ------------------------------------------------------------- reporting

export const reportsApi = {
  dashboard: () => request<Dashboard>("/reports/dashboard"),
  revenue: (params: { from: string; to: string; groupBy?: string }) =>
    request<RevenueReport>(`/reports/revenue${qs(params)}`),
  technicians: (params: { from: string; to: string }) =>
    request<import("./types").TechnicianPerformance[]>(`/reports/technicians${qs(params)}`),
};

// ----------------------------------------------------------------- audit

export const auditApi = {
  list: (params: { page?: number; size?: number; action?: string; from?: string; to?: string }) =>
    request<Page<AuditEvent>>(`/audit-events${qs(params)}`),
};
