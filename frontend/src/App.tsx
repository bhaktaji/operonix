import { Route, Routes } from "react-router-dom";
import { AppLayout } from "@/components/Layout";
import { PublicOnly, RequireAuth, RoleGate } from "@/auth/RequireAuth";
import { Login } from "@/pages/Login";
import { Register } from "@/pages/Register";
import { Dashboard } from "@/pages/Dashboard";
import { Customers } from "@/pages/Customers";
import { CustomerDetail } from "@/pages/CustomerDetail";
import { Quotes } from "@/pages/Quotes";
import { QuoteEditor } from "@/pages/QuoteEditor";
import { QuoteDetail } from "@/pages/QuoteDetail";
import { Schedule } from "@/pages/Schedule";
import { Invoices } from "@/pages/Invoices";
import { InvoiceDetail } from "@/pages/InvoiceDetail";
import { Catalog } from "@/pages/Catalog";
import { Team } from "@/pages/Team";
import { Settings } from "@/pages/Settings";
import { FinanceSettings } from "@/pages/FinanceSettings";
import { AuditLog } from "@/pages/AuditLog";
import { NotFound } from "@/pages/NotFound";

const OFFICE: Array<"OWNER" | "ADMIN" | "MANAGER"> = ["OWNER", "ADMIN", "MANAGER"];
const ADMIN: Array<"OWNER" | "ADMIN"> = ["OWNER", "ADMIN"];

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<PublicOnly><Login /></PublicOnly>} />
      <Route path="/register" element={<PublicOnly><Register /></PublicOnly>} />

      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route index element={<Dashboard />} />

          <Route path="customers" element={<RoleGate allow={OFFICE}><Customers /></RoleGate>} />
          <Route path="customers/:customerId" element={<RoleGate allow={OFFICE}><CustomerDetail /></RoleGate>} />

          <Route path="quotes" element={<RoleGate allow={OFFICE}><Quotes /></RoleGate>} />
          <Route path="quotes/new" element={<RoleGate allow={OFFICE}><QuoteEditor /></RoleGate>} />
          <Route path="quotes/:quoteId" element={<RoleGate allow={OFFICE}><QuoteDetail /></RoleGate>} />
          <Route path="quotes/:quoteId/edit" element={<RoleGate allow={OFFICE}><QuoteEditor /></RoleGate>} />

          <Route path="schedule" element={<Schedule />} />

          <Route path="invoices" element={<RoleGate allow={OFFICE}><Invoices /></RoleGate>} />
          <Route path="invoices/:invoiceId" element={<RoleGate allow={OFFICE}><InvoiceDetail /></RoleGate>} />

          <Route path="catalog" element={<Catalog />} />
          <Route path="team" element={<RoleGate allow={ADMIN}><Team /></RoleGate>} />
          <Route path="settings" element={<RoleGate allow={ADMIN}><Settings /></RoleGate>} />
          <Route path="settings/financial" element={<RoleGate allow={ADMIN}><FinanceSettings /></RoleGate>} />
          <Route path="audit" element={<RoleGate allow={ADMIN}><AuditLog /></RoleGate>} />

          <Route path="*" element={<NotFound />} />
        </Route>
      </Route>
    </Routes>
  );
}