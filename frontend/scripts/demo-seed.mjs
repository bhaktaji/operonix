/**
 * Seeds a stable demo workspace so the UI can be reviewed against real data.
 * Idempotent: re-running logs into the same account when it already exists.
 *
 *   node scripts/demo-seed.mjs            (API must be on http://localhost:8080)
 */
const BASE = process.env.OPERONIX_API ?? "http://localhost:8080/api/v1";
const EMAIL = "owner@harborclean.example";
const PASSWORD = "Harbor-Clean-2026";

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
  const json = text ? JSON.parse(text) : undefined;
  if (res.status !== expect) {
    console.error(`FAIL ${method} ${path} -> ${res.status}`, JSON.stringify(json).slice(0, 300));
    process.exit(1);
  }
  return json;
}

// Reuse the plain demo account while it is still empty; otherwise start a new, fully seeded
// workspace so a run always ends with a complete story to look at.
const stamp = Date.now().toString().slice(-6);
let email = EMAIL;
let session = null;

const login = await fetch(`${BASE}/auth/login`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email: EMAIL, password: PASSWORD }),
});
if (login.ok) {
  session = await login.json();
}

if (session && (await callWith(session.accessToken, "GET", "/customers?size=1")).totalElements === 0) {
  token = session.accessToken;
  console.log("reusing the empty demo workspace");
} else {
  email = `owner+${stamp}@harborclean.example`;
  const auth = await call("POST", "/auth/register", {
    orgName: `Harbor Cleaning Co ${stamp}`,
    fullName: "Avery Whitfield",
    email,
    password: PASSWORD,
  }, 201);
  token = auth.accessToken;
  console.log(`created demo workspace ${email}`);
}

async function callWith(accessToken, method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return res.json();
}

const ws = await call("PUT", "/workspace", {
  name: "Harbor Cleaning Co",
  currency: "USD",
  timezone: "America/Los_Angeles",
});
console.log("workspace:", ws.name, ws.currency, ws.timezone);

const tech = await call("POST", "/team", {
  fullName: "Marisol Vega",
  email: `marisol+${stamp}@harborclean.example`,
  password: "Fieldwork9Clean",
  role: "TECHNICIAN",
}, 201);

const customers = [
  { name: "Harborview Dental", city: "Oakland", contact: "Dr. Lena Park" },
  { name: "Mission Street Bakery", city: "San Francisco", contact: "Tomas Reyes" },
  { name: "Bayfront Law Group", city: "San Francisco", contact: "Priya Nadar" },
  { name: "Piedmont Montessori", city: "Oakland", contact: "Janet Cole" },
];

const created = [];
for (const entry of customers) {
  const customer = await call("POST", "/customers", {
    name: entry.name,
    contactName: entry.contact,
    email: `office@${entry.name.toLowerCase().replace(/[^a-z]+/g, "")}.example`,
    phone: "+1 415 555 0134",
    notes: "Access after 6pm. Alarm code on file.",
    billingAddress: {
      line1: "120 Market Street", city: entry.city, region: "CA", postalCode: "94612", country: "US",
    },
    primaryLocation: {
      label: "Main site", line1: "120 Market Street", city: entry.city, region: "CA",
      postalCode: "94612", country: "US", accessNotes: "Side entrance", isPrimary: true,
    },
  }, 201);
  created.push(customer);
}

const services = [
  { name: "Deep clean — treatment rooms", unit: "ROOM", basePrice: 85, durationMinutes: 90 },
  { name: "Standard office clean", unit: "VISIT", basePrice: 180, durationMinutes: 120 },
  { name: "Floor care", unit: "SQUARE_FOOT", basePrice: 0.35, durationMinutes: 150 },
];
for (const service of services) {
  await call("POST", "/services", { ...service, taxRate: 0.0825, active: true }, 201);
}

// One quote per customer with one accepted → job → invoice cycle.
const locations = await call("GET", `/customers/${created[0].id}/locations`);
const quote = await call("POST", "/quotes", {
  customerId: created[0].id,
  locationId: locations[0].id,
  discountAmount: 25,
  notes: "Quarterly deep clean with disinfectant restock.",
  lines: [
    { description: "Deep clean — treatment rooms", quantity: 4, unitPrice: 85, taxRate: 0.0825 },
    { description: "Waiting room refresh", quantity: 1, unitPrice: 60, taxRate: 0.0825 },
  ],
}, 201);
await call("POST", `/quotes/${quote.id}/send`);
await call("POST", `/quotes/${quote.id}/accept`);

const start = new Date(`${new Date().toISOString().slice(0, 10)}T16:00:00Z`);
const end = new Date(start.getTime() + 90 * 60_000);
const job = await call("POST", `/quotes/${quote.id}/convert`, {
  scheduledStart: start.toISOString(),
  scheduledEnd: end.toISOString(),
  technicianIds: [tech.id],
  title: "Quarterly deep clean",
}, 201);
await call("POST", `/jobs/${job.id}/transitions`, { to: "IN_PROGRESS" });
await call("POST", `/jobs/${job.id}/transitions`, { to: "COMPLETED" });

const invoice = await call("POST", `/jobs/${job.id}/invoice`, {}, 201);
await call("POST", `/invoices/${invoice.id}/send`);
await call("POST", `/invoices/${invoice.id}/payments`, { amount: 150, method: "CARD" }, 201);

// A second quote left pending, and a future job for the schedule board.
const pending = await call("POST", "/quotes", {
  customerId: created[1].id,
  locationId: (await call("GET", `/customers/${created[1].id}/locations`))[0].id,
  lines: [{ description: "Standard office clean", quantity: 2, unitPrice: 180, taxRate: 0.0825 }],
}, 201);
await call("POST", `/quotes/${pending.id}/send`);

const tomorrow = new Date(Date.now() + 86_400_000);
const tomorrowStart = new Date(`${tomorrow.toISOString().slice(0, 10)}T15:00:00Z`);
await call("POST", "/jobs", {
  customerId: created[2].id,
  locationId: (await call("GET", `/customers/${created[2].id}/locations`))[0].id,
  title: "Weekly office clean",
  scheduledStart: tomorrowStart.toISOString(),
  scheduledEnd: new Date(tomorrowStart.getTime() + 7_200_000).toISOString(),
  technicianIds: [tech.id],
  notes: "Suite 400 — check in at reception.",
}, 201);

console.log(`\nSeeded demo workspace for ${email} / ${PASSWORD}`);
process.exit(0);