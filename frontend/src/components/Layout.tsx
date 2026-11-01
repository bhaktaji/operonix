import { useEffect, useRef, useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import {
  BarChart3,
  CalendarDays,
  ChevronDown,
  FileText,
  LayoutDashboard,
  LogOut,
  Menu,
  Package,
  Receipt,
  Sparkles,
  ScrollText,
  Settings,
  UserCog,
  Users,
  X,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useAuth } from "@/auth/AuthProvider";
import type { Role } from "@/api/types";
import { cn } from "@/components/ui/cn";
import { Avatar } from "@/components/ui/primitives";
import { BrandMark } from "@/components/Brand";
import { useI18n } from "@/i18n/I18nProvider";
import type { MessageKey } from "@/i18n/messages";

interface NavItem {
  to: string;
  labelKey: MessageKey;
  icon: LucideIcon;
  end?: boolean;
  roles?: Role[];
}

interface NavGroup {
  labelKey: MessageKey;
  items: NavItem[];
}

const OFFICE: Role[] = ["OWNER", "ADMIN", "MANAGER"];
const ADMIN: Role[] = ["OWNER", "ADMIN"];

/**
 * Navigation is grouped by what the user is doing, not by database table. Groups keep the sidebar
 * readable as the platform grows; role filtering keeps it honest for technicians.
 */
const NAV_GROUPS: NavGroup[] = [
  {
    labelKey: "nav.group.operations",
    items: [
      { to: "/", labelKey: "nav.dashboard", icon: LayoutDashboard, end: true },
      { to: "/customers", labelKey: "nav.customers", icon: Users, roles: OFFICE },
      { to: "/quotes", labelKey: "nav.quotes", icon: FileText, roles: OFFICE },
      { to: "/schedule", labelKey: "nav.schedule", icon: CalendarDays },
      { to: "/invoices", labelKey: "nav.invoices", icon: Receipt, roles: OFFICE },
      { to: "/analytics", labelKey: "nav.analytics", icon: BarChart3, roles: OFFICE },
      { to: "/assistant", labelKey: "nav.assistant", icon: Sparkles, roles: OFFICE },
    ],
  },
  {
    labelKey: "nav.group.business",
    items: [
      { to: "/catalog", labelKey: "nav.catalog", icon: Package },
      { to: "/team", labelKey: "nav.team", icon: UserCog, roles: ADMIN },
    ],
  },
  {
    labelKey: "nav.group.admin",
    items: [
      { to: "/settings", labelKey: "nav.settings", icon: Settings, roles: ADMIN },
      { to: "/audit", labelKey: "nav.audit", icon: ScrollText, roles: ADMIN },
    ],
  },
];

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const { user, tenant, logout } = useAuth();
  const { t } = useI18n();
  const navigate = useNavigate();

  const groups = NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => !item.roles || (user && item.roles.includes(user.role))),
  })).filter((group) => group.items.length > 0);

  const signOut = async () => {
    await logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="flex h-full flex-col">
      <div className="px-5 pb-5 pt-6">
        <BrandMark onDark />
      </div>

      <nav className="flex-1 space-y-6 overflow-y-auto px-3 pb-4">
        {groups.map((group) => (
          <div key={group.labelKey}>
            <p className="px-3 pb-2 text-[10px] font-semibold uppercase tracking-[0.18em] text-ink-500">
              {t(group.labelKey)}
            </p>
            <div className="space-y-0.5">
              {group.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  onClick={onNavigate}
                  className={({ isActive }) => cn("nav-link", isActive && "nav-link-active")}
                >
                  <item.icon className="h-[17px] w-[17px]" />
                  {t(item.labelKey)}
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>

      <div className="mx-3 mb-3 rounded-xl border border-white/[0.08] bg-white/[0.04] p-3">
        <div className="flex items-center gap-2.5">
          <Avatar name={user?.fullName ?? "?"} className="h-8 w-8 bg-brand-500/25 text-brand-100" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13px] font-medium text-white">{user?.fullName}</p>
            <p className="truncate text-[11px] text-ink-400">
              {tenant?.name}
              {user?.role ? ` · ${user.role.charAt(0) + user.role.slice(1).toLowerCase()}` : ""}
            </p>
          </div>
          <button
            type="button"
            onClick={signOut}
            aria-label={t("nav.signOut")}
            title={t("nav.signOut")}
            className="rounded-md p-1.5 text-ink-400 transition hover:bg-white/10 hover:text-white"
          >
            <LogOut className="h-4 w-4" />
          </button>
        </div>
      </div>
      <div className="flex items-center justify-between px-5 pb-5 text-[10px] text-ink-500">
        <span>{t("nav.footer")}</span>
        <span className="rounded border border-white/10 px-1.5 py-0.5 font-mono text-[9px] text-ink-400">
          v2.0
        </span>
      </div>
    </div>
  );
}

export function AppLayout() {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const { user, tenant, logout } = useAuth();
  const { t } = useI18n();
  const navigate = useNavigate();

  useEffect(() => {
    const onClick = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setDrawerOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen]);

  const signOut = async () => {
    setMenuOpen(false);
    await logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="min-h-screen bg-sand-100">
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-[264px] bg-ink-950 lg:block">
        <SidebarContent />
      </aside>

      {drawerOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div
            className="absolute inset-0 animate-fade-in bg-ink-950/60"
            onClick={() => setDrawerOpen(false)}
            aria-hidden="true"
          />
          <aside className="relative h-full w-[280px] animate-fade-in bg-ink-950 shadow-float">
            <button
              type="button"
              onClick={() => setDrawerOpen(false)}
              aria-label="Close menu"
              className="absolute right-3 top-5 rounded-md p-1.5 text-ink-400 hover:bg-white/10 hover:text-white"
            >
              <X className="h-4 w-4" />
            </button>
            <SidebarContent onNavigate={() => setDrawerOpen(false)} />
          </aside>
        </div>
      )}

      <div className="lg:pl-[264px]">
        <header className="sticky top-0 z-20 border-b border-sand-200 bg-white/85 backdrop-blur">
          <div className="flex items-center justify-between gap-3 px-4 py-3 sm:px-6 xl:px-8">
            <div className="flex min-w-0 items-center gap-3">
              <button
                type="button"
                onClick={() => setDrawerOpen(true)}
                aria-label="Open menu"
                className="rounded-lg p-2 text-ink-600 hover:bg-sand-100 lg:hidden"
              >
                <Menu className="h-5 w-5" />
              </button>
              <div className="min-w-0">
                <p className="truncate text-[12px] font-medium text-ink-500">
                  {tenant?.name ?? t("app.name")}
                  {tenant?.countryCode && (
                    <span className="ml-2 rounded border border-sand-200 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-ink-400">
                      {tenant.countryCode} · {tenant.currency}
                    </span>
                  )}
                </p>
              </div>
            </div>

            <div className="relative" ref={menuRef}>
              <button
                type="button"
                onClick={() => setMenuOpen((open) => !open)}
                className="flex items-center gap-2 rounded-full border border-sand-200 bg-white py-1 pl-1 pr-3 text-sm shadow-sm transition hover:border-sand-300"
                aria-haspopup="menu"
                aria-expanded={menuOpen}
              >
                <Avatar name={user?.fullName ?? "?"} className="h-7 w-7 bg-ink-950" />
                <span className="hidden max-w-[140px] truncate font-medium text-ink-800 sm:block">
                  {user?.fullName}
                </span>
                <ChevronDown className="h-3.5 w-3.5 text-ink-400" />
              </button>
              {menuOpen && (
                <div
                  role="menu"
                  className="absolute right-0 top-11 w-64 overflow-hidden rounded-xl2 border border-sand-200 bg-white shadow-float animate-fade-up"
                >
                  <div className="border-b border-sand-200 px-4 py-3">
                    <p className="truncate text-sm font-semibold text-ink-900">{user?.fullName}</p>
                    <p className="truncate text-xs text-ink-400">{user?.email}</p>
                    <p className="mt-1 text-[11px] font-medium text-ink-500">
                      {tenant?.name} · {user?.role}
                    </p>
                  </div>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={signOut}
                    className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-sm text-ink-600 transition hover:bg-sand-50 hover:text-ink-900"
                  >
                    <LogOut className="h-4 w-4" /> {t("nav.signOut")}
                  </button>
                </div>
              )}
            </div>
          </div>
        </header>

        <main className="mx-auto w-full max-w-[1400px] px-4 pb-16 pt-6 sm:px-6 xl:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}