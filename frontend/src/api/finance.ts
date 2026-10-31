import { request } from "./client";

/**
 * Finance configuration API (P1a): legal entities, business locations, tax profiles, numbering
 * rules and exchange rates. Kept out of `endpoints.ts` because it is a self-contained surface with
 * its own shapes.
 */

export interface TaxComponentInfo {
  code: string;
  name: string;
  rate: number;
  compound: boolean;
  position: number;
}

export interface TaxDefinition {
  id: string;
  code: string;
  name: string;
  countryCode: string | null;
  mode: "EXCLUSIVE" | "INCLUSIVE";
  rounding: string;
  components: TaxComponentInfo[];
}

export interface TaxProfile {
  id: string;
  name: string;
  legalEntityId: string | null;
  taxDefinitionId: string | null;
  taxDefinitionCode: string | null;
  mode: "EXCLUSIVE" | "INCLUSIVE" | null;
  effectiveMode: "EXCLUSIVE" | "INCLUSIVE";
  totalRate: number;
  components: TaxComponentInfo[];
  defaultProfile: boolean;
  active: boolean;
  version: number;
}

export interface TaxProfileInput {
  name: string;
  legalEntityId?: string | null;
  taxDefinitionId: string;
  mode?: "EXCLUSIVE" | "INCLUSIVE" | null;
  makeDefault?: boolean;
  active?: boolean;
}

export interface LegalEntity {
  id: string;
  name: string;
  countryCode: string;
  taxNumber: string | null;
  taxNumberLabel: string | null;
  baseCurrency: string;
  taxMode: "EXCLUSIVE" | "INCLUSIVE";
  address: string | null;
  defaultEntity: boolean;
  active: boolean;
  version: number;
}

export interface LegalEntityInput {
  name: string;
  countryCode: string;
  taxNumber?: string | null;
  baseCurrency: string;
  taxMode?: "EXCLUSIVE" | "INCLUSIVE";
  taxNumberLabel?: string | null;
  address?: string | null;
  makeDefault?: boolean;
}

export interface BusinessLocation {
  id: string;
  legalEntityId: string;
  name: string;
  countryCode: string;
  timezone: string;
  address: string | null;
  defaultLocation: boolean;
  active: boolean;
  version: number;
}

export interface BusinessLocationInput {
  legalEntityId?: string | null;
  name: string;
  countryCode: string;
  timezone: string;
  address?: string | null;
  makeDefault?: boolean;
  active?: boolean;
}

export interface NumberRule {
  docType: "QUOTE" | "JOB" | "INVOICE";
  prefix: string;
  includeYear: boolean;
  padding: number;
  resetPolicy: "YEARLY" | "NEVER";
  sample: string;
}

export interface ExchangeRate {
  base: string;
  quote: string;
  rate: number;
  asOf: string;
  source: string;
}

export interface FinancialSettings {
  tenantId: string;
  locale: string;
  baseCurrency: string;
  currencyPrecision: number;
  defaultTaxMode: "EXCLUSIVE" | "INCLUSIVE";
  defaultTaxProfileId: string | null;
  defaultLegalEntityId: string | null;
  fiscalYearStartMonth: number;
  weekStart: number;
  dateFormat: string;
  numberFormat: string;
}

/** A printed tax row returned on a quote or invoice. */
export interface TaxAmount {
  code: string;
  name: string;
  rate: number;
  base: number;
  amount: number;
}

export const financeApi = {
  legalEntities: {
    list: () => request<LegalEntity[]>("/legal-entities"),
    create: (input: LegalEntityInput) =>
      request<LegalEntity>("/legal-entities", { method: "POST", body: JSON.stringify(input) }),
    update: (id: string, input: LegalEntityInput) =>
      request<LegalEntity>(`/legal-entities/${id}`, { method: "PUT", body: JSON.stringify(input) }),
    remove: (id: string) => request<void>(`/legal-entities/${id}`, { method: "DELETE" }),
  },
  locations: {
    list: () => request<BusinessLocation[]>("/business-locations"),
    create: (input: BusinessLocationInput) =>
      request<BusinessLocation>("/business-locations", { method: "POST", body: JSON.stringify(input) }),
    update: (id: string, input: BusinessLocationInput) =>
      request<BusinessLocation>(`/business-locations/${id}`, { method: "PUT", body: JSON.stringify(input) }),
    remove: (id: string) => request<void>(`/business-locations/${id}`, { method: "DELETE" }),
  },
  tax: {
    definitions: () => request<TaxDefinition[]>("/tax/definitions"),
    profiles: () => request<TaxProfile[]>("/tax/profiles"),
    createProfile: (input: TaxProfileInput) =>
      request<TaxProfile>("/tax/profiles", { method: "POST", body: JSON.stringify(input) }),
    updateProfile: (id: string, input: TaxProfileInput) =>
      request<TaxProfile>(`/tax/profiles/${id}`, { method: "PUT", body: JSON.stringify(input) }),
    removeProfile: (id: string) => request<void>(`/tax/profiles/${id}`, { method: "DELETE" }),
  },
  numbering: {
    list: () => request<NumberRule[]>("/number-rules"),
    update: (docType: string, input: { prefix: string; includeYear: boolean; padding: number; resetPolicy: string }) =>
      request<NumberRule>(`/number-rules/${docType}`, { method: "PUT", body: JSON.stringify(input) }),
  },
  rates: {
    list: () => request<ExchangeRate[]>("/exchange-rates"),
    create: (input: { base: string; quote: string; rate: number; asOf?: string }) =>
      request<ExchangeRate>("/exchange-rates", { method: "POST", body: JSON.stringify(input) }),
  },
  settings: {
    get: () => request<FinancialSettings>("/workspace/financial-settings"),
    update: (input: Partial<Pick<FinancialSettings,
      "locale" | "defaultTaxProfileId" | "defaultLegalEntityId" | "fiscalYearStartMonth" | "weekStart">>) =>
      request<FinancialSettings>("/workspace/financial-settings", {
        method: "PUT",
        body: JSON.stringify(input),
      }),
  },
};
