import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Sparkles } from "lucide-react";
import { servicesApi } from "@/api/endpoints";
import type { ServiceInput } from "@/api/endpoints";
import type { ServiceItem, ServiceUnit } from "@/api/types";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { Badge, Button, Card, EmptyState, ErrorBanner, Field, Input, PageHeader, Select, Skeleton, Textarea } from "@/components/ui/primitives";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import { formatMoney, formatPercent, labelize } from "@/lib/format";

const emptyForm = {
  name: "",
  description: "",
  unit: "VISIT" as ServiceUnit,
  basePrice: "0.00",
  durationMinutes: "120",
  taxRate: "0.00",
  active: true,
};

export function Catalog() {
  const { tenant, hasRole } = useAuth();
  const canManage = hasRole("OWNER", "ADMIN", "MANAGER");
  const queryClient = useQueryClient();
  const toast = useToast();
  const currency = tenant?.currency ?? "USD";

  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<ServiceItem | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [error, setError] = useState<string | null>(null);

  const services = useQuery({ queryKey: ["services"], queryFn: () => servicesApi.list() });

  useEffect(() => {
    if (!dialogOpen) return;
    setError(null);
    if (editing) {
      setForm({
        name: editing.name,
        description: editing.description ?? "",
        unit: editing.unit,
        basePrice: editing.basePrice.toFixed(2),
        durationMinutes: String(editing.durationMinutes),
        taxRate: (editing.taxRate * 100).toFixed(2),
        active: editing.active,
      });
    } else {
      setForm(emptyForm);
    }
  }, [dialogOpen, editing]);

  const mutation = useMutation({
    mutationFn: (input: ServiceInput) =>
      editing ? servicesApi.update(editing.id, input) : servicesApi.create(input),
    onSuccess: async () => {
      toast.push({ title: editing ? "Service updated" : "Service added" });
      await queryClient.invalidateQueries({ queryKey: ["services"] });
      setDialogOpen(false);
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    mutation.mutate({
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      unit: form.unit,
      basePrice: Number(form.basePrice) || 0,
      durationMinutes: Number(form.durationMinutes) || 120,
      taxRate: (Number(form.taxRate) || 0) / 100,
      active: form.active,
    });
  };

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Pricing"
        title="Service catalog"
        description="The priced building blocks for quotes and invoices. Deactivate instead of deleting."
        actions={canManage ? (
          <Button
            variant="secondary"
            icon={<Plus className="h-4 w-4" />}
            onClick={() => {
              setEditing(null);
              setDialogOpen(true);
            }}
          >
            New service
          </Button>
        ) : undefined}
      />

      <Card className="overflow-hidden">
        {services.isError ? (
          <div className="p-5"><ErrorBanner message={errorMessage(services.error)} /></div>
        ) : services.isLoading ? (
          <div className="space-y-2 p-4">
            {Array.from({ length: 5 }).map((_, index) => <Skeleton key={index} className="h-14 w-full" />)}
          </div>
        ) : (services.data?.length ?? 0) === 0 ? (
          <EmptyState
            icon={<Sparkles className="h-5 w-5" />}
            title="No services yet"
            description="Add your standard cleans and rates so quoting takes seconds."
            action={canManage ? (
              <Button variant="secondary" icon={<Plus className="h-4 w-4" />} onClick={() => setDialogOpen(true)}>
                New service
              </Button>
            ) : undefined}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] border-collapse">
              <thead className="border-b border-sand-200 bg-sand-50/70">
                <tr>
                  <th className="table-head">Service</th>
                  <th className="table-head">Unit</th>
                  <th className="table-head text-right">Base price</th>
                  <th className="table-head text-right">Duration</th>
                  <th className="table-head text-right">Tax</th>
                  <th className="table-head">Status</th>
                  {canManage && <th className="table-head text-right">Edit</th>}
                </tr>
              </thead>
              <tbody className="divide-y divide-sand-100">
                {services.data?.map((service) => (
                  <tr key={service.id} className="transition hover:bg-sand-50/60">
                    <td className="table-cell">
                      <p className="font-medium text-ink-900">{service.name}</p>
                      {service.description && <p className="max-w-md truncate text-[12px] text-ink-400">{service.description}</p>}
                    </td>
                    <td className="table-cell">{labelize(service.unit)}</td>
                    <td className="table-cell text-right font-medium text-ink-900">{formatMoney(service.basePrice, currency)}</td>
                    <td className="table-cell text-right">{service.durationMinutes} min</td>
                    <td className="table-cell text-right">{formatPercent(service.taxRate)}</td>
                    <td className="table-cell">
                      <Badge tone={service.active ? "success" : "neutral"}>{service.active ? "Active" : "Inactive"}</Badge>
                    </td>
                    {canManage && (
                      <td className="table-cell text-right">
                        <Button
                          size="sm"
                          variant="ghost"
                          icon={<Pencil className="h-3.5 w-3.5" />}
                          onClick={() => {
                            setEditing(service);
                            setDialogOpen(true);
                          }}
                        >
                          Edit
                        </Button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        title={editing ? `Edit ${editing.name}` : "New service"}
        description="Services snapshot their price and tax onto the quote or invoice at the time of use."
        footer={
          <>
            <Button variant="outline" type="button" onClick={() => setDialogOpen(false)}>Cancel</Button>
            <Button type="submit" form="service-form" variant="secondary" loading={mutation.isPending}>
              {editing ? "Save service" : "Add service"}
            </Button>
          </>
        }
      >
        <form id="service-form" onSubmit={submit} className="space-y-4" noValidate>
          {error && <ErrorBanner message={error} />}

          <Field label="Service name" htmlFor="svc-name">
            <Input
              id="svc-name"
              required
              maxLength={120}
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
              placeholder="Deep clean — 2 bed apartment"
            />
          </Field>

          <Field label="Description" htmlFor="svc-desc">
            <Textarea
              id="svc-desc"
              rows={2}
              maxLength={500}
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
            />
          </Field>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Billing unit" htmlFor="svc-unit">
              <Select
                id="svc-unit"
                value={form.unit}
                onChange={(event) => setForm({ ...form, unit: event.target.value as ServiceUnit })}
              >
                {(["VISIT", "HOUR", "SQUARE_FOOT", "ROOM", "FLAT"] as ServiceUnit[]).map((unit) => (
                  <option key={unit} value={unit}>{labelize(unit)}</option>
                ))}
              </Select>
            </Field>
            <Field label="Base price" htmlFor="svc-price">
              <Input
                id="svc-price"
                inputMode="decimal"
                value={form.basePrice}
                onChange={(event) => setForm({ ...form, basePrice: event.target.value })}
              />
            </Field>
            <Field label="Default duration (minutes)" htmlFor="svc-duration">
              <Input
                id="svc-duration"
                type="number"
                min={15}
                max={1440}
                value={form.durationMinutes}
                onChange={(event) => setForm({ ...form, durationMinutes: event.target.value })}
              />
            </Field>
            <Field label="Tax rate (%)" htmlFor="svc-tax">
              <Input
                id="svc-tax"
                inputMode="decimal"
                value={form.taxRate}
                onChange={(event) => setForm({ ...form, taxRate: event.target.value })}
              />
            </Field>
          </div>

          <label className="flex items-center gap-2.5 text-sm text-ink-700">
            <input
              type="checkbox"
              checked={form.active}
              onChange={(event) => setForm({ ...form, active: event.target.checked })}
              className="h-4 w-4 rounded border-sand-300 text-brand-600 focus:ring-brand-200"
            />
            Active — available for new quotes and invoices
          </label>
        </form>
      </Modal>
    </div>
  );
}