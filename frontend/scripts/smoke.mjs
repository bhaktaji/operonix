// Override when the API is not on the default port, e.g.
//   OPERONIX_BASE_URL=http://localhost:8090/api/v1 node scripts/smoke.mjs
const BASE = process.env.OPERONIX_BASE_URL ?? "http://localhost:8080/api/v1";

const stamp = Date.now();
let token = "";

async function call(method, path, body, expect = 200) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : undefined; } catch { json = text; }
  if (res.status !== expect) {
    console.error(`FAIL ${method} ${path} -> ${res.status}`, JSON.stringify(json).slice(0, 500));
    process.exit(1);
  }
  console.log(`ok ${res.status} ${method} ${path}`);
  return json;
}

// 1. register tenant + owner
const auth = await call("POST", "/auth/register", {
  orgName: `Sparkle Clean ${stamp}`,
  fullName: "Avery Owner",
  email: `avery.${stamp}@example.com`,
  password: "Sunrise-Meadow-42",
}, 201);
token = auth.accessToken;

// 2. workspace
const ws = await call("GET", "/workspace");
console.log("   workspace:", ws.name, ws.currency, ws.timezone, "users=", ws.users);

// 3. customer with primary location
const customer = await call("POST", "/customers", {
  name: "Harborview Dental",
  contactName: "Dr. Lena Park",
  email: "office@harborview.example",
  phone: "+1 415 555 0101",
  notes: "Alarm code 4471. Clean after 6pm only.",
  billingAddress: { line1: "18 Pier Street", city: "Oakland", region: "CA", postalCode: "94607", country: "US" },
  primaryLocation: {
    label: "Main clinic", line1: "18 Pier Street", city: "Oakland", region: "CA",
    postalCode: "94607", country: "US", accessNotes: "Key in lockbox", isPrimary: true,
  },
}, 201);
console.log("   customer:", customer.id, "locations:", customer.locations.length);

// 4. catalog
const service = await call("POST", "/services", {
  name: "Deep clean — treatment rooms",
  description: "Floors, surfaces, fixtures",
  unit: "ROOM",
  basePrice: 85.0,
  durationMinutes: 90,
  taxRate: 0.0825,
  active: true,
}, 201);

// 5. quote
const quote = await call("POST", "/quotes", {
  customerId: customer.id,
  locationId: customer.locations[0].id,
  notes: "Includes disinfectant restock.",
  discountAmount: 20.0,
  lines: [
    { serviceItemId: service.id, quantity: 3, taxRate: 0.0825 },
    { description: "Waiting room refresh", quantity: 1, unitPrice: 60.0, taxRate: 0.0825 },
  ],
}, 201);
console.log("   quote:", quote.number, quote.status, "total=", quote.total);

await call("POST", `/quotes/${quote.id}/send`);
const accepted = await call("POST", `/quotes/${quote.id}/accept`);
if (accepted.total !== quote.total) throw new Error("accept changed the total");
console.log("   accepted total:", accepted.total);

// 6. hire a technician, then convert to job
const tech = await call("POST", "/team", {
  fullName: "Morgan Tech",
  email: `morgan.${stamp}@example.com`,
  password: "Sunrise-Meadow-42",
  role: "TECHNICIAN",
}, 201);

const start = new Date(Date.now() + 86400000).toISOString();
const end = new Date(Date.now() + 86400000 + 5400000).toISOString();
const job = await call("POST", `/quotes/${quote.id}/convert`, {
  scheduledStart: start, scheduledEnd: end, technicianIds: [tech.id], title: "Opening deep clean",
}, 201);
console.log("   job:", job.number, job.status);

await call("POST", `/jobs/${job.id}/transitions`, { to: "IN_PROGRESS" });
await call("POST", `/jobs/${job.id}/transitions`, { to: "COMPLETED" });

// 7. invoice from job (inherits quote discount)
const invoice = await call("POST", `/jobs/${job.id}/invoice`, {}, 201);
console.log("   invoice:", invoice.number, invoice.status, "total=", invoice.total, "discount=", invoice.discountAmount);

// 8. partial payment, then remainder
const payment = await call("POST", `/invoices/${invoice.id}/payments`, { amount: 100.0, method: "CARD", reference: "ch_42" }, 201);
console.log("   payment:", payment.amount);
const partial = await call("GET", `/invoices/${invoice.id}`);
console.log("   after payment: status=", partial.status, "balance=", partial.balance);

await call("POST", `/invoices/${invoice.id}/payments`, { amount: partial.balance, method: "BANK_TRANSFER" }, 201);
const paid = await call("GET", `/invoices/${invoice.id}`);
console.log("   final: status=", paid.status, "balance=", paid.balance, "paidAt=", paid.paidAt);

// 9. reporting
const dash = await call("GET", "/reports/dashboard");
console.log("   dashboard: revenueThisMonth=", dash.revenueThisMonth, "jobsThisWeek=", dash.jobsThisWeek, "activeCustomers=", dash.activeCustomers);

// 10. audit + team
const audit = await call("GET", "/audit-events?size=5");
console.log("   audit events:", audit.content.map((e) => e.action).join(", "));
const team = await call("GET", "/team");
console.log("   team size:", team.length);

// 11. tenant isolation: second workspace must not see the first customer.
// A new client registers while signed out, so the previous session's token is dropped first.
token = "";
const other = await call("POST", "/auth/register", {
  orgName: `Other Co ${stamp}`,
  fullName: "Riley Other",
  email: `riley.${stamp}@example.com`,
  password: "Sunrise-Meadow-42",
}, 201);
token = other.accessToken;
const foreign = await fetch(`${BASE}/customers/${customer.id}`, { headers: { Authorization: `Bearer ${token}` } });
console.log("   cross-tenant GET customer ->", foreign.status, foreign.status === 404 ? "(isolated)" : "(LEAK!)");
const foreignInvoices = await call("GET", "/invoices");
console.log("   other tenant invoice count:", foreignInvoices.totalElements);

// 12. unauthenticated access
const anon = await fetch(`${BASE}/customers`);
console.log("   anonymous GET customers ->", anon.status);

console.log("\nSEED OK");
process.exit(0);