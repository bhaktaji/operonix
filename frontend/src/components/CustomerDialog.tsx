import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { customersApi } from "@/api/endpoints";
import type { CustomerInput } from "@/api/endpoints";
import type { Customer } from "@/api/types";
import { errorMessage, fieldErrors } from "@/api/client";
import { Button, ErrorBanner, Field, Input, Textarea } from "@/components/ui/primitives";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";

interface FormState {
  name: string;
  contactName: string;
  email: string;
  phone: string;
  notes: string;
  line1: string;
  line2: string;
  city: string;
  region: string;
  postalCode: string;
  country: string;
}

const emptyForm: FormState = {
  name: "",
  contactName: "",
  email: "",
  phone: "",
  notes: "",
  line1: "",
  line2: "",
  city: "",
  region: "",
  postalCode: "",
  country: "US",
};

export function CustomerDialog({
  open,
  onClose,
  customer,
  onSaved,
}: {
  open: boolean;
  onClose: () => void;
  customer?: Customer | null;
  onSaved?: (customer: Customer) => void;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [form, setForm] = useState<FormState>(emptyForm);
  const [withAddress, setWithAddress] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const isEdit = !!customer;

  useEffect(() => {
    if (!open) return;
    setError(null);
    setErrors({});
    if (customer) {
      setForm({
        name: customer.name,
        contactName: customer.contactName ?? "",
        email: customer.email ?? "",
        phone: customer.phone ?? "",
        notes: customer.notes ?? "",
        line1: customer.billingAddress?.line1 ?? "",
        line2: customer.billingAddress?.line2 ?? "",
        city: customer.billingAddress?.city ?? "",
        region: customer.billingAddress?.region ?? "",
        postalCode: customer.billingAddress?.postalCode ?? "",
        country: customer.billingAddress?.country ?? "US",
      });
      setWithAddress(!!customer.billingAddress);
    } else {
      setForm(emptyForm);
      setWithAddress(false);
    }
  }, [open, customer]);

  const set = (key: keyof FormState) => (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  const mutation = useMutation({
    mutationFn: (input: CustomerInput) =>
      customer ? customersApi.update(customer.id, input) : customersApi.create(input),
    onSuccess: async (saved) => {
      toast.push({ title: isEdit ? "Customer updated" : "Customer added" });
      await queryClient.invalidateQueries({ queryKey: ["customers"] });
      queryClient.setQueryData(["customer", saved.id], saved);
      onSaved?.(saved);
      onClose();
    },
    onError: (caught) => {
      setError(errorMessage(caught));
      setErrors(fieldErrors(caught));
    },
  });

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setErrors({});
    const payload: CustomerInput = {
      name: form.name.trim(),
      contactName: form.contactName.trim() || undefined,
      email: form.email.trim() || undefined,
      phone: form.phone.trim() || undefined,
      notes: form.notes.trim() || undefined,
      billingAddress: withAddress
        ? {
            line1: form.line1.trim(),
            line2: form.line2.trim() || undefined,
            city: form.city.trim(),
            region: form.region.trim() || undefined,
            postalCode: form.postalCode.trim(),
            country: form.country.trim().toUpperCase(),
          }
        : null,
    };
    if (!isEdit && withAddress) {
      // Reuse the billing address as the first service location; it can be refined later.
      payload.primaryLocation = {
        label: "Main site",
        line1: form.line1.trim(),
        line2: form.line2.trim() || undefined,
        city: form.city.trim(),
        region: form.region.trim() || undefined,
        postalCode: form.postalCode.trim(),
        country: form.country.trim().toUpperCase(),
        isPrimary: true,
      };
    }
    mutation.mutate(payload);
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isEdit ? `Edit ${customer?.name}` : "New customer"}
      description={isEdit ? "Update contact and billing details." : "Add the client and, optionally, their first service address."}
      footer={
        <>
          <Button variant="outline" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" form="customer-form" variant="secondary" loading={mutation.isPending}>
            {isEdit ? "Save changes" : "Add customer"}
          </Button>
        </>
      }
    >
      <form id="customer-form" onSubmit={onSubmit} className="space-y-4" noValidate>
        {error && <ErrorBanner message={error} />}

        <Field label="Business or household name" htmlFor="cust-name" error={errors.name}>
          <Input id="cust-name" required value={form.name} onChange={set("name")} placeholder="Brightline Offices" />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Primary contact" htmlFor="cust-contact" error={errors.contactName}>
            <Input id="cust-contact" value={form.contactName} onChange={set("contactName")} placeholder="Mia Chen" />
          </Field>
          <Field label="Phone" htmlFor="cust-phone" error={errors.phone}>
            <Input id="cust-phone" value={form.phone} onChange={set("phone")} placeholder="+1-555-0142" />
          </Field>
        </div>

        <Field label="Email" htmlFor="cust-email" error={errors.email}>
          <Input id="cust-email" type="email" value={form.email} onChange={set("email")} placeholder="billing@brightline.example" />
        </Field>

        <Field label="Notes" htmlFor="cust-notes" error={errors.notes}>
          <Textarea id="cust-notes" rows={2} value={form.notes} onChange={set("notes")} placeholder="Key at reception. No bleach on stone." />
        </Field>

        <label className="flex items-center gap-2.5 text-sm text-ink-700">
          <input
            type="checkbox"
            checked={withAddress}
            onChange={(event) => setWithAddress(event.target.checked)}
            className="h-4 w-4 rounded border-sand-300 text-brand-600 focus:ring-brand-200"
          />
          Add a billing address
        </label>

        {withAddress && (
          <div className="grid gap-4 rounded-lg border border-sand-200 bg-sand-50/60 p-3 sm:grid-cols-2">
            <Field label="Street" htmlFor="bill-line1" error={errors["billingAddress.line1"]} className="sm:col-span-2">
              <Input id="bill-line1" value={form.line1} onChange={set("line1")} />
            </Field>
            <Field label="Address line 2" htmlFor="bill-line2">
              <Input id="bill-line2" value={form.line2} onChange={set("line2")} />
            </Field>
            <Field label="City" htmlFor="bill-city" error={errors["billingAddress.city"]}>
              <Input id="bill-city" value={form.city} onChange={set("city")} />
            </Field>
            <Field label="State / region" htmlFor="bill-region">
              <Input id="bill-region" value={form.region} onChange={set("region")} />
            </Field>
            <Field label="Postal code" htmlFor="bill-postal" error={errors["billingAddress.postalCode"]}>
              <Input id="bill-postal" value={form.postalCode} onChange={set("postalCode")} />
            </Field>
            <Field label="Country" htmlFor="bill-country" error={errors["billingAddress.country"]}>
              <Input id="bill-country" maxLength={2} value={form.country} onChange={set("country")} />
            </Field>
          </div>
        )}

        {!isEdit && withAddress && (
          <p className="rounded-lg bg-sand-100 px-3 py-2 text-[12px] text-ink-500">
            We&apos;ll also save this address as their first service location. You can add more
            locations from the customer profile.
          </p>
        )}
        {!isEdit && !withAddress && (
          <p className="rounded-lg bg-amber-50 px-3 py-2 text-[12px] text-amber-700">
            No address? Add the customer first, then create a service location from their profile
            before quoting or scheduling.
          </p>
        )}
      </form>
    </Modal>
  );
}