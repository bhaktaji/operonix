import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { customersApi } from "@/api/endpoints";
import type { LocationInput } from "@/api/endpoints";
import type { ServiceLocation } from "@/api/types";
import { errorMessage, fieldErrors } from "@/api/client";
import { Button, ErrorBanner, Field, Input, Textarea } from "@/components/ui/primitives";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";

function blank(primary: boolean): LocationInput {
  return {
    label: "",
    line1: "",
    city: "",
    region: "",
    postalCode: "",
    country: "US",
    accessNotes: "",
    timezone: "",
    isPrimary: primary,
  };
}

export function LocationDialog({
  open,
  onClose,
  customerId,
  location,
  isFirstLocation = false,
}: {
  open: boolean;
  onClose: () => void;
  customerId: string;
  location?: ServiceLocation | null;
  isFirstLocation?: boolean;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [form, setForm] = useState<LocationInput>(blank(true));
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!open) return;
    setError(null);
    setErrors({});
    if (location) {
      setForm({
        label: location.label,
        line1: location.line1,
        line2: location.line2 ?? "",
        city: location.city,
        region: location.region ?? "",
        postalCode: location.postalCode,
        country: location.country,
        accessNotes: location.accessNotes ?? "",
        timezone: location.timezone ?? "",
        isPrimary: location.isPrimary,
      });
    } else {
      setForm(blank(true));
    }
  }, [open, location]);

  const set = <K extends keyof LocationInput>(key: K, value: LocationInput[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  const mutation = useMutation({
    mutationFn: (input: LocationInput) =>
      location
        ? customersApi.updateLocation(location.id, input)
        : customersApi.addLocation(customerId, input),
    onSuccess: async () => {
      toast.push({ title: location ? "Location updated" : "Location added" });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["customer", customerId] }),
        queryClient.invalidateQueries({ queryKey: ["customers"] }),
      ]);
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
    mutation.mutate({
      ...form,
      label: form.label.trim(),
      line1: form.line1.trim(),
      line2: form.line2?.trim() || undefined,
      city: form.city.trim(),
      region: form.region?.trim() || undefined,
      postalCode: form.postalCode.trim(),
      country: form.country.trim().toUpperCase(),
      accessNotes: form.accessNotes?.trim() || undefined,
      timezone: form.timezone?.trim() || undefined,
      isPrimary: isFirstLocation ? true : form.isPrimary,
    });
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={location ? "Edit service location" : "Add a service location"}
      description="Where the crew checks in, parks, and finds access details."
      footer={
        <>
          <Button variant="outline" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" form="location-form" variant="secondary" loading={mutation.isPending}>
            {location ? "Save location" : "Add location"}
          </Button>
        </>
      }
    >
      <form id="location-form" onSubmit={onSubmit} className="space-y-4" noValidate>
        {error && <ErrorBanner message={error} />}

        <Field label="Label" htmlFor="loc-label" error={errors.label} hint="e.g. HQ — Floor 4">
          <Input id="loc-label" required value={form.label} onChange={(event) => set("label", event.target.value)} />
        </Field>

        <Field label="Street address" htmlFor="loc-line1" error={errors.line1}>
          <Input id="loc-line1" required value={form.line1} onChange={(event) => set("line1", event.target.value)} />
        </Field>

        <Field label="Address line 2" htmlFor="loc-line2" error={errors.line2}>
          <Input id="loc-line2" value={form.line2 ?? ""} onChange={(event) => set("line2", event.target.value)} />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="City" htmlFor="loc-city" error={errors.city}>
            <Input id="loc-city" required value={form.city} onChange={(event) => set("city", event.target.value)} />
          </Field>
          <Field label="State / region" htmlFor="loc-region" error={errors.region}>
            <Input id="loc-region" value={form.region ?? ""} onChange={(event) => set("region", event.target.value)} />
          </Field>
          <Field label="Postal code" htmlFor="loc-postal" error={errors.postalCode}>
            <Input id="loc-postal" required value={form.postalCode} onChange={(event) => set("postalCode", event.target.value)} />
          </Field>
          <Field label="Country" htmlFor="loc-country" error={errors.country} hint="Two-letter ISO code">
            <Input
              id="loc-country"
              required
              maxLength={2}
              value={form.country}
              onChange={(event) => set("country", event.target.value.toUpperCase())}
            />
          </Field>
        </div>

        <Field
          label="Time zone"
          htmlFor="loc-timezone"
          error={errors.timezone}
          hint="IANA zone for this address, e.g. America/Los_Angeles. Leave blank to use the workspace time zone."
        >
          <Input
            id="loc-timezone"
            value={form.timezone ?? ""}
            onChange={(event) => set("timezone", event.target.value)}
            placeholder="America/Los_Angeles"
          />
        </Field>

        <Field label="Access notes" htmlFor="loc-access" error={errors.accessNotes}>
          <Textarea
            id="loc-access"
            rows={2}
            value={form.accessNotes ?? ""}
            onChange={(event) => set("accessNotes", event.target.value)}
            placeholder="Badge at dock, alarm code with supervisor, no bleach on stone…"
          />
        </Field>

        <label className="flex items-center gap-2.5 text-sm text-ink-700">
          <input
            type="checkbox"
            checked={isFirstLocation ? true : form.isPrimary}
            disabled={isFirstLocation}
            onChange={(event) => set("isPrimary", event.target.checked)}
            className="h-4 w-4 rounded border-sand-300 text-brand-600 focus:ring-brand-200"
          />
          Make this the primary location
        </label>
      </form>
    </Modal>
  );
}