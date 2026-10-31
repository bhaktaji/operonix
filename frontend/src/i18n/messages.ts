/**
 * Translation catalogue (en is the source of truth).
 *
 * Scope note: shared chrome, navigation, authentication and onboarding are catalogued here because they
 * are the surfaces every translated market sees first. Feature-page copy still lives in its components
 * and is migrated into this catalogue as each locale is activated — see docs/FRONTEND_GLOBALIZATION.md.
 */
export const en = {
  "app.name": "Operonix",
  "app.tagline": "The Operating Platform for Growing Businesses.",
  "app.shortDescription":
    "Quotations, scheduling, invoicing and payments for service businesses anywhere in the world.",

  "nav.group.operations": "Operations",
  "nav.group.business": "Business",
  "nav.group.admin": "Administration",
  "nav.dashboard": "Overview",
  "nav.customers": "Customers",
  "nav.quotes": "Quotations",
  "nav.schedule": "Scheduling",
  "nav.invoices": "Invoices",
  "nav.payments": "Payments",
  "nav.catalog": "Catalog",
  "nav.team": "Team",
  "nav.settings": "Workspace settings",
  "nav.audit": "Audit log",
  "nav.signOut": "Sign out",
  "nav.footer": "Global business platform",

  "auth.signIn.title": "Sign in to your workspace",
  "auth.signIn.subtitle": "Pick up your quotes, jobs and collections exactly where you left them.",
  "auth.signIn.email": "Work email",
  "auth.signIn.password": "Password",
  "auth.signIn.submit": "Sign in",
  "auth.signIn.newHere": "New to Operonix?",
  "auth.signIn.createWorkspace": "Create your workspace",

  "auth.register.kicker": "Start free",
  "auth.register.title": "Create your workspace",
  "auth.register.subtitle":
    "Tell us where you operate. Operonix configures currency, language and timezone for you — you can change everything later.",
  "auth.register.business": "Business name",
  "auth.register.yourName": "Your name",
  "auth.register.country": "Country of registration",
  "auth.register.industry": "What does your business do?",
  "auth.register.currency": "Billing currency",
  "auth.register.timezone": "Timezone",
  "auth.register.language": "Workspace language",
  "auth.register.submit": "Create workspace",
  "auth.register.haveAccount": "Already have an account?",
  "auth.register.signIn": "Sign in",
  "auth.register.step.business": "Business",
  "auth.register.step.access": "Access",
  "auth.register.optionalNote": "Legal and tax details are optional and can be added later in settings.",

  "common.loading": "Loading",
  "common.save": "Save",
  "common.cancel": "Cancel",
  "common.tryAgain": "Try again",
  "common.optional": "Optional",
  "common.required": "Required",
  "common.selectPlaceholder": "Select…",
} as const;

export type MessageKey = keyof typeof en;
export type MessageCatalogue = Record<MessageKey, string>;

/** Every additional locale is a partial catalogue that falls back to English per key. */
export const catalogues: Record<string, Partial<MessageCatalogue>> = {
  en,
};

export function translate(
  locale: string | undefined,
  key: MessageKey,
  vars?: Record<string, string | number>,
): string {
  const language = (locale ?? "en").split("-")[0].toLowerCase();
  const catalogue = catalogues[language] ?? catalogues.en;
  let message: string = catalogue[key] ?? en[key] ?? key;
  if (vars) {
    Object.entries(vars).forEach(([name, value]) => {
      message = message.replaceAll(`{${name}}`, String(value));
    });
  }
  return message;
}