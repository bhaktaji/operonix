export type Role = "OWNER" | "ADMIN" | "MANAGER" | "TECHNICIAN";

export interface UserSummary {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  tenantId: string;
}

export interface TenantSummary {
  id: string;
  name: string;
  slug: string;
  currency: string;
  timezone: string;
  /** ISO 3166-1 alpha-2 market. Drives defaults only, never business logic. */
  countryCode: string;
  /** BCP-47 tag used for locale-aware formatting. */
  locale: string;
  /** Industry template code; selects vocabulary, never behaviour. */
  industryTemplate: string;
  legalName?: string | null;
}

export interface Country {
  code: string;
  name: string;
  defaultCurrency: string;
  defaultLocale: string;
  callingCode: string;
  taxLabel?: string | null;
}

export interface Currency {
  code: string;
  name: string;
  symbol: string;
  minorUnits: number;
}

export interface IndustryTemplate {
  code: string;
  label: string;
  description: string;
}

export interface ReferenceData {
  countries: Country[];
  currencies: Currency[];
  industryTemplates: IndustryTemplate[];
}

export interface WorkspaceOption {
  slug: string;
  name: string;
  role: Role;
}

export interface AuthResponse {
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
  user: UserSummary;
  tenant: TenantSummary;
}

export interface MeResponse {
  user: UserSummary;
  tenant: TenantSummary;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type CustomerStatus = "ACTIVE" | "ARCHIVED";

export interface Address {
  line1: string;
  line2?: string | null;
  city: string;
  region?: string | null;
  postalCode: string;
  country: string;
}

export interface CustomerSummary {
  id: string;
  name: string;
  contactName?: string | null;
  email?: string | null;
  phone?: string | null;
  status: CustomerStatus;
}

export interface ServiceLocation {
  id: string;
  label: string;
  line1: string;
  line2?: string | null;
  city: string;
  region?: string | null;
  postalCode: string;
  country: string;
  accessNotes?: string | null;
  isPrimary: boolean;
}

export interface Customer {
  id: string;
  name: string;
  contactName?: string | null;
  email?: string | null;
  phone?: string | null;
  notes?: string | null;
  status: CustomerStatus;
  billingAddress?: Address | null;
  locations: ServiceLocation[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export type ServiceUnit = "HOUR" | "VISIT" | "SQUARE_FOOT" | "ROOM" | "FLAT";

export interface ServiceItem {
  id: string;
  name: string;
  description?: string | null;
  unit: ServiceUnit;
  basePrice: number;
  durationMinutes: number;
  taxRate: number;
  taxProfileId?: string | null;
  cost: number;
  active: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export type QuoteStatus = "DRAFT" | "SENT" | "ACCEPTED" | "DECLINED" | "EXPIRED" | "CONVERTED";

export interface QuoteLine {
  id: string;
  serviceItemId?: string | null;
  position: number;
  description: string;
  quantity: number;
  unitPrice: number;
  taxRate: number;
  lineSubtotal: number;
  lineTax: number;
  lineTotal: number;
}

export interface Quote {
  id: string;
  number: string;
  status: QuoteStatus;
  customer: CustomerSummary;
  location?: ServiceLocation | null;
  issueDate: string;
  validUntil: string;
  currency: string;
  notes?: string | null;
  subtotal: number;
  discountAmount: number;
  taxAmount: number;
  total: number;
  convertedJobId?: string | null;
  lines: QuoteLine[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export type JobStatus = "SCHEDULED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
export type Recurrence = "NONE" | "WEEKLY" | "BIWEEKLY" | "MONTHLY";

export interface Technician {
  id: string;
  fullName: string;
}

export interface Job {
  id: string;
  number: string;
  title: string;
  status: JobStatus;
  scheduledStart: string;
  scheduledEnd: string;
  customer: CustomerSummary;
  location: ServiceLocation | null;
  quoteId?: string | null;
  recurrence: Recurrence;
  seriesId?: string | null;
  technicians: Technician[];
  notes?: string | null;
  completedAt?: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export type InvoiceStatus = "DRAFT" | "SENT" | "PARTIALLY_PAID" | "PAID" | "VOID";
export type PaymentMethod = "CASH" | "CARD" | "BANK_TRANSFER" | "CHECK" | "OTHER";

/** One printed tax row on a document: CGST 9% on 100.00 = 9.00. */
export interface TaxAmount {
  code: string;
  name: string;
  rate: number;
  base: number;
  amount: number;
}

export interface InvoiceLine {
  id: string;
  serviceItemId?: string | null;
  position: number;
  description: string;
  quantity: number;
  unitPrice: number;
  taxRate: number;
  lineSubtotal: number;
  lineTax: number;
  lineTotal: number;
  /** Printed tax rows snapshotted when the invoice was priced. */
  taxComponents?: TaxAmount[];
}

export interface Payment {
  id: string;
  amount: number;
  method: PaymentMethod;
  reference?: string | null;
  paidAt: string;
  recordedBy: string;
  createdAt: string;
  /** Set when the payment was reversed; the row stays on the timeline but no longer counts. */
  reversedAt?: string | null;
  reversedBy?: string | null;
}

export interface Invoice {
  id: string;
  number: string;
  status: InvoiceStatus;
  overdue: boolean;
  customer: CustomerSummary;
  jobId?: string | null;
  issueDate: string;
  dueDate: string;
  currency: string;
  notes?: string | null;
  subtotal: number;
  discountAmount: number;
  taxAmount: number;
  total: number;
  amountPaid: number;
  balance: number;
  paidAt?: string | null;
  voidReason?: string | null;
  /** Seller and tax snapshot taken at issue time (P1a). */
  legalEntityId?: string | null;
  taxProfileName?: string | null;
  taxMode?: "EXCLUSIVE" | "INCLUSIVE";
  taxNumber?: string | null;
  reverseCharge?: boolean;
  fxRate?: number;
  baseCurrency?: string;
  baseTotal?: number;
  baseAmountPaid?: number;
  taxComponents?: TaxAmount[];
  lines: InvoiceLine[];
  payments: Payment[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface Bucket {
  key: string;
  label: string;
  revenue: number;
  invoiceCount: number;
}

export interface ServiceRevenue {
  label: string;
  revenue: number;
}

export interface Dashboard {
  revenueThisMonth?: number | null;
  revenuePreviousMonth?: number | null;
  revenueDeltaPct?: number | null;
  outstandingTotal?: number | null;
  overdueTotal?: number | null;
  jobsToday: number;
  jobsThisWeek: number;
  quotesPending?: number | null;
  quoteAcceptanceRate?: number | null;
  activeCustomers?: number | null;
  revenueByDay: Bucket[];
  topServices: ServiceRevenue[];
  upcomingJobs: Job[];
}

export interface RevenueReport {
  groupBy: string;
  buckets: Bucket[];
  total: number;
}

export interface TechnicianPerformance {
  userId: string;
  fullName: string;
  jobsCompleted: number;
  scheduledMinutes: number;
}

export interface TeamMember {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  active: boolean;
  /** Labour cost per hour in the workspace currency; 0 means not configured. */
  hourlyCost?: number | null;
  lastLoginAt?: string | null;
  createdAt: string;
}

export interface Workspace {
  id: string;
  name: string;
  slug: string;
  countryCode: string;
  currency: string;
  timezone: string;
  locale: string;
  industryTemplate: string;
  legalName?: string | null;
  taxNumber?: string | null;
  onboardedAt?: string | null;
  users: number;
}

export interface WorkspaceInput {
  name: string;
  countryCode: string;
  currency: string;
  timezone: string;
  locale: string;
  industryTemplate: string;
  legalName?: string | null;
  taxNumber?: string | null;
}

export interface RegisterInput {
  orgName: string;
  fullName: string;
  email: string;
  password: string;
  countryCode?: string;
  currency?: string;
  timezone?: string;
  locale?: string;
  industryTemplate?: string;
  legalName?: string;
}

// ------------------------------------------------------------------ costing (P1b)

export type ExpenseCategory = "LABOR" | "MATERIAL" | "EQUIPMENT" | "SUBCONTRACTOR" | "OTHER";

export interface Vendor {
  id: string;
  name: string;
  contactName?: string | null;
  email?: string | null;
  phone?: string | null;
  taxNumber?: string | null;
  defaultRate: number;
  paymentTerms: string;
  active: boolean;
  version: number;
}

export interface VendorInput {
  name: string;
  contactName?: string | null;
  email?: string | null;
  phone?: string | null;
  taxNumber?: string | null;
  defaultRate?: number;
  paymentTerms?: string;
  active?: boolean;
}

export interface WorkLog {
  id: string;
  jobId: string;
  userId: string;
  userName: string;
  startAt: string;
  endAt?: string | null;
  minutes?: number | null;
  manual: boolean;
  notes?: string | null;
  /** Absent when the person has no hourly cost configured. */
  cost?: number | null;
  version: number;
}

export interface WorkLogInput {
  userId?: string;
  startAt: string;
  endAt?: string | null;
  notes?: string;
}

export interface Expense {
  id: string;
  jobId?: string | null;
  jobNumber?: string | null;
  vendorId?: string | null;
  vendorName?: string | null;
  description: string;
  amount: number;
  currency: string;
  fxRate: number;
  workspaceAmount: number;
  receiptReference?: string | null;
  expenseDate: string;
  category: ExpenseCategory;
  notes?: string | null;
  version: number;
}

export interface ExpenseInput {
  description: string;
  amount: number;
  currency?: string;
  expenseDate: string;
  category: ExpenseCategory;
  vendorId?: string | null;
  jobId?: string | null;
  receiptReference?: string | null;
  notes?: string | null;
}

export interface JobCost {
  jobId: string;
  currency: string;
  laborCost: number;
  materialCost: number;
  equipmentCost: number;
  subcontractorCost: number;
  otherCost: number;
  totalCost: number;
  computedAt?: string | null;
  version: number;
}

/** Amounts are absent (not zero) when the job's profitability is INCOMPLETE. */
export interface Profitability {
  jobId: string;
  currency: string;
  status: "COMPLETE" | "INCOMPLETE";
  reasons: string[];
  revenue?: number | null;
  laborCost: number;
  materialCost: number;
  equipmentCost: number;
  subcontractorCost: number;
  otherCost: number;
  totalCost: number;
  directProfit?: number | null;
  marginPercent?: number | null;
  plannedRevenue?: number | null;
  plannedCost?: number | null;
  plannedMargin?: number | null;
  actualMinutes?: number | null;
  scheduledMinutes?: number | null;
  computedAt?: string | null;
  scopeNote: string;
}

export interface JobCostingView {
  cost: JobCost;
  profitability: Profitability;
  workLogs: WorkLog[];
  expenses: Expense[];
  vendors: Vendor[];
}

export interface AuditEvent {
  id: string;
  action: string;
  entityType: string;
  entityId?: string | null;
  actorUserId?: string | null;
  detail?: string | null;
  ipAddress?: string | null;
  createdAt: string;
}

export interface LineInput {
  serviceItemId?: string | null;
  description?: string;
  quantity: number;
  unitPrice?: number;
  taxRate?: number;
}

export interface QuoteInput {
  customerId: string;
  locationId?: string | null;
  issueDate?: string;
  validUntil?: string;
  discountAmount?: number;
  notes?: string;
  lines: LineInput[];
}

export interface JobInput {
  customerId: string;
  locationId: string;
  title?: string;
  scheduledStart: string;
  scheduledEnd: string;
  technicianIds: string[];
  notes?: string;
}

export interface InvoiceInput {
  customerId: string;
  jobId?: string | null;
  issueDate?: string;
  dueDate?: string;
  notes?: string;
  discountAmount?: number;
  lines: LineInput[];
}