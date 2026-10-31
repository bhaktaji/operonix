import { useEffect, useRef, useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import {
  CalendarDays,
  ChevronDown,
  FileText,
  LayoutDashboard,
  LogOut,
  Menu,
  Receipt,
  ScrollText,
  Settings,
  Sparkles,
  UserCog,
  Users,
  X,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useAuth } from "@/auth/AuthProvider";
import type { Role } from "@/api/types";
import { cn } from "@/components/ui/cn";
import { Avatar } from "@/components/ui/primitives";

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  end?: boolean;
  roles?: Role[];
}

const NAV_ITEMS: NavItem[] = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard, end: true },
  { to: "/customers", label: "Customers", icon: Users, roles: ["OWNER", "ADMIN", "MANAGER"] },
  { to: "/quotes", label: "Quotes", icon: FileText, roles: ["OWNER", "ADMIN", "MANAGER"] },
  { to: "/schedule", label: "Schedule", icon: CalendarDays },
  { to: "/invoices", label: "Invoices", icon: Receipt, roles: ["OWNER", "ADMIN", "MANAGER"] },
  { to: "/catalog", label: "Service catalog", icon: Sparkles },
  { to: "/team", label: "Team", icon: UserCog, roles: ["OWNER", "ADMIN"] },
  { to: "/settings", label: "Workspace", icon: Settings, roles: ["OWNER", "ADMIN"] },
  { to: "/audit", label: "Audit log", icon: ScrollText, roles: ["OWNER", "ADMIN"] },
];

function BrandMark({ compact = false }: { compact?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand-500/15 text-brand-300 ring-1 ring-inset ring-brand-400/30">
        <Sparkles className="h-[18px] w-[18px]" />
      </span>
      {!compact && (
        <span className="font-display text-[17px] font-semibold tracking-tight text-white">
          Operonix
        </span>
      )}
    </div>
  );
}

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const { user, tenant, logout } = useAuth();
  const navigate = useNavigate();

  const visible = NAV_ITEMS.filter((item) => !item.roles || (user && item.roles.includes(user.role)));

  const signOut = async () => {
    await logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="flex h-full flex-col">
      <div className="px-5 pb-4 pt-5">
        <BrandMark />
      </div>

      <nav className="flex-1 space-y-0.5 overflow-y-auto px-3 pb-4">
        <p className="px-3 pb-2 pt-3 text-[10px] font-semibold uppercase tracking-[0.18em] text-ink-500">
          Workspace
        </p>
        {visible.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            onClick={onNavigate}
            className={({ isActive }) => cn("nav-link", isActive && "nav-link-active")}
          >
            <item.icon className="h-[17px] w-[17px]" />
            {item.label}
          </NavLink>
        ))}
      </nav>

      <div className="mx-3 mb-3 rounded-xl border border-white/10 bg-white/[0.04] p-3">
        <div className="flex items-center gap-2.5">
          <Avatar name={user?.fullName ?? "?"} className="h-8 w-8 bg-brand-500/20 text-brand-100" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13px] font-medium text-white">{user?.fullName}</p>
            <p className="truncate text-[11px] text-ink-400">
              {tenant?.name} · {user?.role ? user.role.charAt(0) + user.role.slice(1).toLowerCase() : ""}
            </p>
          </div>
          <button
            type="button"
            onClick={signOut}
            aria-label="Sign out"
            title="Sign out"
            className="rounded-md p-1.5 text-ink-400 transition hover:bg-white/10 hover:text-white"
          >
            <LogOut className="h-4 w-4" />
          </button>
        </div>
      </div>
      <p className="px-5 pb-4 text-[10px] text-ink-500">Operonix · v1.0</p>
    </div>
  );
}

export function AppLayout() {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const { user, tenant, logout } = useAuth();
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
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-[248px] bg-ink-950 lg:block">
        <SidebarContent />
      </aside>

      {drawerOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div
            className="absolute inset-0 animate-fade-in bg-ink-950/60"
            onClick={() => setDrawerOpen(false)}
            aria-hidden="true"
          />
          <aside className="relative h-full w-[270px] animate-fade-in bg-ink-950 shadow-float">
            <button
              type="button"
              onClick={() => setDrawerOpen(false)}
              aria-label="Close menu"
              className="absolute right-3 top-4 rounded-md p-1.5 text-ink-400 hover:bg-white/10 hover:text-white"
            >
              <X className="h-4 w-4" />
            </button>
            <SidebarContent onNavigate={() => setDrawerOpen(false)} />
          </aside>
        </div>
      )}

      <div className="lg:pl-[248px]">
        <header className="sticky top-0 z-20 border-b border-sand-200 bg-sand-100/90 backdrop-blur lg:hidden">
          <div className="flex items-center justify-between px-4 py-3">
            <button
              type="button"
              onClick={() => setDrawerOpen(true)}
              aria-label="Open menu"
              className="rounded-lg p-2 text-ink-600 hover:bg-sand-200"
            >
              <Menu className="h-5 w-5" />
            </button>
            <span className="font-display text-[15px] font-semibold text-ink-950">Operonix</span>
            <div className="h-9 w-9 rounded-full bg-ink-900 text-center text-[13px] font-semibold leading-9 text-white">
              {(user?.fullName ?? "?").charAt(0)}
            </div>
          </div>
        </header>

        <div className="hidden justify-end px-6 pt-5 lg:flex xl:px-10">
          <div className="relative" ref={menuRef}>
            <button
              type="button"
              onClick={() => setMenuOpen((open) => !open)}
              className="flex items-center gap-2 rounded-full border border-sand-200 bg-white py-1 pl-1 pr-3 text-sm shadow-sm transition hover:border-sand-300"
              aria-haspopup="menu"
              aria-expanded={menuOpen}
            >
              <Avatar name={user?.fullName ?? "?"} className="h-7 w-7" />
              <span className="max-w-[140px] truncate font-medium text-ink-800">{user?.fullName}</span>
              <ChevronDown className="h-3.5 w-3.5 text-ink-400" />
            </button>
            {menuOpen && (
              <div
                role="menu"
                className="absolute right-0 top-11 w-60 overflow-hidden rounded-xl2 border border-sand-200 bg-white shadow-float animate-fade-up"
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
                  <LogOut className="h-4 w-4" /> Sign out
                </button>
              </div>
            )}
          </div>
        </div>

        <main className="mx-auto w-full max-w-[1360px] px-4 pb-16 pt-6 sm:px-6 lg:px-6 lg:pt-2 xl:px-10">
          <Outlet />
        </main>
      </div>
    </div>
  );
}