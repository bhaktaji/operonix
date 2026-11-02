import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  ArrowLeft,
  Building2,
  FileText,
  MapPin,
  Pencil,
  Plus,
  RotateCcw,
  Trash2,
} from "lucide-react";
import { customersApi } from "@/api/endpoints";
import { errorMessage } from "@/api/client";
import { CustomerDialog } from "@/components/CustomerDialog";
import { LocationDialog } from "@/components/LocationDialog";
import { Badge, Button, Card, EmptyState, ErrorBanner, PageLoader, SectionCard } from "@/components/ui/primitives";
import { ConfirmDialog } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import { statusTone } from "@/components/ui/cn";
import { formatCalendarDate, labelize } from "@/lib/format";
import { useAuth } from "@/auth/AuthProvider";

export function CustomerDetail() {
  const { customerId = "" } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();
  const { tenant } = useAuth();

  const [editOpen, setEditOpen] = useState(false);
  const [locationOpen, setLocationOpen] = useState(false);
  const [editingLocation, setEditingLocation] = useState<string | null>(null);
  const [archiveOpen, setArchiveOpen] = useState(false);
  const [deleteLocationId, setDeleteLocationId] = useState<string | null>(null);

  const customer = useQuery({
    queryKey: ["customer", customerId],
    queryFn: () => customersApi.get(customerId),
    enabled: !!customerId,
  });

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ["customer", customerId] });
    await queryClient.invalidateQueries({ queryKey: ["customers"] });
  };

  const archive = useMutation({
    mutationFn: async () => {
      if (!customer.data) return;
      return customer.data.status === "ACTIVE"
        ? customersApi.archive(customerId)
        : customersApi.restore(customerId);
    },
    onSuccess: async () => {
      toast.push({ title: customer.data?.status === "ACTIVE" ? "Customer archived" : "Customer restored" });
      setArchiveOpen(false);
      await refresh();
    },
    onError: (caught) => toast.push({ title: "Couldn’t update customer", description: errorMessage(caught), tone: "error" }),
  });

  const deleteLocation = useMutation({
    mutationFn: (locationId: string) => customersApi.deleteLocation(locationId),
    onSuccess: async () => {
      toast.push({ title: "Location removed" });
      setDeleteLocationId(null);
      await refresh();
    },
    onError: (caught) => toast.push({ title: "Couldn’t remove location", description: errorMessage(caught), tone: "error" }),
  });

  if (customer.isLoading) {
    return <PageLoader label="Loading customer" />;
  }

  if (customer.isError || !customer.data) {
    return (
      <div>
        <Link to="/customers" className="mb-5 inline-flex items-center gap-1.5 text-sm text-ink-500 hover:text-ink-900">
          <ArrowLeft className="h-4 w-4" /> Back to customers
        </Link>
        <ErrorBanner title="Customer unavailable" message={errorMessage(customer.error)} />
      </div>
    );
  }

  const data = customer.data;
  const locationToEdit = data.locations.find((location) => location.id === editingLocation) ?? null;

  return (
    <div className="animate-fade-up">
      <Link to="/customers" className="mb-5 inline-flex items-center gap-1.5 text-sm text-ink-500 hover:text-ink-900">
        <ArrowLeft className="h-4 w-4" /> Back to customers
      </Link>

      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2.5">
            <h1 className="font-display text-[26px] font-semibold tracking-tight text-ink-950 sm:text-[30px]">
              {data.name}
            </h1>
            <Badge tone={statusTone(data.status)}>{labelize(data.status)}</Badge>
          </div>
          <p className="mt-1.5 text-sm text-ink-500">
            Customer since {formatCalendarDate(data.createdAt, tenant?.timezone)}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" icon={<Pencil className="h-4 w-4" />} onClick={() => setEditOpen(true)}>
            Edit
          </Button>
          <Button
            variant={data.status === "ACTIVE" ? "outline" : "secondary"}
            icon={data.status === "ACTIVE" ? <Trash2 className="h-4 w-4" /> : <RotateCcw className="h-4 w-4" />}
            onClick={() => setArchiveOpen(true)}
          >
            {data.status === "ACTIVE" ? "Archive" : "Restore"}
          </Button>
          <Link to={`/quotes/new?customerId=${data.id}`}>
            <Button variant="secondary" icon={<FileText className="h-4 w-4" />}>New quote</Button>
          </Link>
        </div>
      </div>

      <div className="grid gap-4 xl:grid-cols-[1.4fr_1fr]">
        <SectionCard title="Contact & billing" description="The details your team and documents rely on">
          <dl className="grid gap-4 sm:grid-cols-2">
            <div>
              <dt className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Contact</dt>
              <dd className="mt-1 text-sm text-ink-800">{data.contactName ?? "—"}</dd>
            </div>
            <div>
              <dt className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Phone</dt>
              <dd className="mt-1 text-sm text-ink-800">{data.phone ?? "—"}</dd>
            </div>
            <div>
              <dt className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Email</dt>
              <dd className="mt-1 break-all text-sm text-ink-800">{data.email ?? "—"}</dd>
            </div>
            <div>
              <dt className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Billing address</dt>
              <dd className="mt-1 text-sm leading-relaxed text-ink-800">
                {data.billingAddress ? (
                  <>
                    {data.billingAddress.line1}
                    {data.billingAddress.line2 ? `, ${data.billingAddress.line2}` : ""}
                    <br />
                    {data.billingAddress.city}
                    {data.billingAddress.region ? `, ${data.billingAddress.region}` : ""} {data.billingAddress.postalCode}
                    <br />
                    {data.billingAddress.country}
                  </>
                ) : (
                  "—"
                )}
              </dd>
            </div>
            {data.notes && (
              <div className="sm:col-span-2">
                <dt className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Notes</dt>
                <dd className="mt-1 whitespace-pre-line rounded-lg bg-sand-50 px-3 py-2.5 text-sm text-ink-700">
                  {data.notes}
                </dd>
              </div>
            )}
          </dl>
        </SectionCard>

        <Card className="p-5">
          <div className="flex items-center gap-2 text-ink-500">
            <Building2 className="h-4 w-4" />
            <p className="text-[12px] font-semibold uppercase tracking-[0.12em]">Account</p>
          </div>
          <ul className="mt-4 space-y-3 text-sm">
            <li className="flex justify-between gap-3">
              <span className="text-ink-500">Service locations</span>
              <span className="font-medium text-ink-900">{data.locations.length}</span>
            </li>
            <li className="flex justify-between gap-3">
              <span className="text-ink-500">Primary site</span>
              <span className="truncate font-medium text-ink-900">
                {data.locations.find((location) => location.isPrimary)?.label ?? "Not set"}
              </span>
            </li>
            <li className="flex justify-between gap-3">
              <span className="text-ink-500">Last updated</span>
              <span className="font-medium text-ink-900">{formatCalendarDate(data.updatedAt, tenant?.timezone)}</span>
            </li>
          </ul>
        </Card>
      </div>

      <SectionCard
        className="mt-4"
        title="Service locations"
        description="Addresses and access details used when scheduling"
        actions={
          <Button
            size="sm"
            variant="outline"
            icon={<Plus className="h-3.5 w-3.5" />}
            onClick={() => {
              setEditingLocation(null);
              setLocationOpen(true);
            }}
          >
            Add location
          </Button>
        }
      >
        {data.locations.length === 0 ? (
          <EmptyState
            icon={<MapPin className="h-5 w-5" />}
            title="No service locations yet"
            description="Add an address before quoting or scheduling this customer."
            action={
              <Button
                size="sm"
                variant="secondary"
                icon={<Plus className="h-3.5 w-3.5" />}
                onClick={() => {
                  setEditingLocation(null);
                  setLocationOpen(true);
                }}
              >
                Add location
              </Button>
            }
          />
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2">
            {data.locations.map((location) => (
              <li key={location.id} className="rounded-xl border border-sand-200 p-4">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-ink-900">{location.label}</p>
                    <p className="mt-0.5 text-[12px] text-ink-500">
                      {location.line1}
                      {location.line2 ? `, ${location.line2}` : ""}
                    </p>
                    <p className="text-[12px] text-ink-500">
                      {location.city}
                      {location.region ? `, ${location.region}` : ""} {location.postalCode} · {location.country}
                    </p>
                  </div>
                  {location.isPrimary && <Badge tone="brand">Primary</Badge>}
                </div>
                {location.accessNotes && (
                  <p className="mt-2 rounded-md bg-amber-50 px-2.5 py-1.5 text-[12px] text-amber-700">
                    {location.accessNotes}
                  </p>
                )}
                <div className="mt-3 flex items-center gap-1">
                  <Button
                    size="sm"
                    variant="ghost"
                    icon={<Pencil className="h-3.5 w-3.5" />}
                    onClick={() => {
                      setEditingLocation(location.id);
                      setLocationOpen(true);
                    }}
                  >
                    Edit
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="text-rose-600 hover:bg-rose-50 hover:text-rose-700"
                    icon={<Trash2 className="h-3.5 w-3.5" />}
                    onClick={() => setDeleteLocationId(location.id)}
                  >
                    Remove
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      <CustomerDialog
        open={editOpen}
        onClose={() => setEditOpen(false)}
        customer={data}
        onSaved={() => {
          void refresh();
          navigate(`/customers/${data.id}`, { replace: true });
        }}
      />

      <LocationDialog
        open={locationOpen}
        onClose={() => setLocationOpen(false)}
        customerId={data.id}
        location={locationToEdit}
        isFirstLocation={data.locations.length === 0}
      />

      <ConfirmDialog
        open={archiveOpen}
        onClose={() => setArchiveOpen(false)}
        onConfirm={() => archive.mutate()}
        loading={archive.isPending}
        title={data.status === "ACTIVE" ? `Archive ${data.name}?` : `Restore ${data.name}?`}
        description={data.status === "ACTIVE"
          ? "Archiving hides the customer from default lists and blocks new quotes and jobs. It cannot be archived while scheduled work is open."
          : "Restoring makes the customer active again for new quotes and jobs."}
        confirmLabel={data.status === "ACTIVE" ? "Archive customer" : "Restore customer"}
        tone={data.status === "ACTIVE" ? "danger" : "primary"}
      />

      <ConfirmDialog
        open={!!deleteLocationId}
        onClose={() => setDeleteLocationId(null)}
        onConfirm={() => deleteLocationId && deleteLocation.mutate(deleteLocationId)}
        loading={deleteLocation.isPending}
        title="Remove this location?"
        description="Locations used by scheduled or in-progress jobs are protected and cannot be removed."
        confirmLabel="Remove location"
      />
    </div>
  );
}