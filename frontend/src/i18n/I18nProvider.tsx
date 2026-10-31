import { createContext, useContext, useEffect, useMemo } from "react";
import type { ReactNode } from "react";
import { useAuth } from "@/auth/AuthProvider";
import { setFormatLocale } from "@/lib/format";
import { translate } from "./messages";
import type { MessageKey } from "./messages";

interface I18nValue {
  locale: string;
  t: (key: MessageKey, vars?: Record<string, string | number>) => string;
}

const I18nContext = createContext<I18nValue | null>(null);

/**
 * Binds the workspace language to formatting and copy. It sits inside AuthProvider, so the locale
 * follows the signed-in workspace and falls back to the browser for signed-out pages (login/register).
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const { tenant } = useAuth();
  const locale = tenant?.locale || (typeof navigator !== "undefined" ? navigator.language : "en");

  useEffect(() => {
    setFormatLocale(locale);
    if (typeof document !== "undefined") {
      document.documentElement.lang = locale;
    }
  }, [locale]);

  const value = useMemo<I18nValue>(() => ({
    locale,
    t: (key, vars) => translate(locale, key, vars),
  }), [locale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const context = useContext(I18nContext);
  if (!context) {
    throw new Error("useI18n must be used inside I18nProvider");
  }
  return context;
}