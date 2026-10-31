import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { customersApi, invoicesApi, servicesApi } from "@/api/endpoints";
import type { Invoice } from "@/api/types";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { LineItemsEditor } from "@/components/LineItemsEditor";
import { Button, ErrorBanner, Field, Input, Select, Textarea } from "@/components/ui/primitives";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import { formatMoney, todayISO } from "@/lib/format";
import { calculatePreview, newDraftLine } from "@/lib/money";
import type { DraftLine } from "@/lib/money";

export function InvoiceCreateDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated?: (invoice: Invoice) => void;
}) {
  const { tenant } = useAuth();
  const currency = tenant?.currency ?? "USD";
  const queryClient = useQueryClient();
  const toast = useToast();

  const [customerId, setCustomerId] = useState("");
  const [issueDate, setIssueDate] = useState(todayISO());
  const [dueDate, setDueDate] = useState("");
  const [discount, setDiscount] = useState("0");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<DraftLine[]>([newDraftLine()]);
  const [error, setError] = useState<string | null>(null);

  const customers = useQuery({
    queryKey: ["customers", "picker"],
    queryFn: () => customersApi.list({ status: "ACTIVE", size: 100, sort: "name,asc" }),
    enabled: open,
  });
  const services = useQuery({ queryKey: ["services"], queryFn: () => servicesApi.list(), enabled: open });

  useEffect(() => {
    if (!open) return;
    setCustomerId("");
    setIssueDate(todayISO());
    setDueDate("");
    setDiscount("0");
    setNotes("");
    setLines([newDraftLine()]);
    setError(null);
  }, [open]);

  const preview = calculatePreview(lines, Number(discount) || 0);

  const mutation = useMutation({
    mutationFn: () => invoicesApi.create({
      customerId,
      issueDate,
      dueDate: dueDate || undefined,
      notes: notes.trim() || undefined,
      discountAmount: Number(discount) || 0,
      lines: lines.map((line) => ({
        serviceItemId: line.serviceItemId || null,
        description: line.description.trim(),
        quantity: Number(line.quantity) || 0,
        unitPrice: Number(line.unitPrice) || 0,
        taxRate: (Number(line.taxRate) || 0) / 100,
      })),
    }),
    onSuccess: async (created) => {
      toast.push({ title: "Invoice created", description: created.number });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["invoices"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
      onCreated?.(created);
      onClose();
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    if (!customerId) return setError("Choose a customer.");
    if (lines.length === 0) return setError("Add at least one invoice line.");
    mutation.mutate();
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="xl"
      title="New invoice"
      description="Bill work directly — or invoice a job from the schedule to copy its quote lines."
      footer={
        <>
          <Button variant="outline" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" form="invoice-create-form" variant="secondary" loading={mutation.isPending}>
            Create draft
          </Button>
        </>
      }
    >
      <form id="invoice-create-form" onSubmit={submit} className="grid gap-5 lg:grid-cols-[1.6fr_1fr]" noValidate>
        <div className="space-y-4">
          {error && <ErrorBanner message={error} />}

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Customer" htmlFor="inv-customer">
              <Select id="inv-customer" value={customerId} onChange={(event) => setCustomerId(event.target.value)}>
                <option value="">Select a customer…</option>
                {customers.data?.content.map((customer) => (
                  <option key={customer.id} value={customer.id}>{customer.name}</option>
                ))}
              </Select>
            </Field>
            <Field label="Issue date" htmlFor="inv-issue">
              <Input id="inv-issue" type="date" value={issueDate} onChange={(event) => setIssueDate(event.target.value)} />
            </Field>
            <Field label="Due date" htmlFor="inv-due" hint="Defaults to 14 days after issue">
              <Input id="inv-due" type="date" value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
            </Field>
            <Field label="Discount" htmlFor="inv-discount">
              <Input
                id="inv-discount"
                inputMode="decimal"
                value={discount}
                onChange={(event) => setDiscount(event.target.value)}
              />
            </Field>
          </div>

          <div>
            <p className="mb-2 font-display text-[15px] font-semibold text-ink-900">Line items</p>
            <LineItemsEditor
              lines={lines}
              services={services.data ?? []}
              currency={currency}
              onChange={setLines}
            />
          </div>

          <Field label="Notes" htmlFor="inv-notes">
            <Textarea
              id="inv-notes"
              rows={2}
              value={notes}
              onChange={(event) => setNotes(event.target.value)}
              placeholder="Payment terms, PO number, thank-you note…"
            />
          </Field>
        </div>

        <div className="rounded-xl2 border border-sand-200 bg-sand-50/70 p-4">
          <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">Preview</p>
          <dl className="mt-3 space-y-2 text-sm">
            <div className="flex justify-between"><dt className="text-ink-500">Subtotal</dt><dd>{formatMoney(preview.subtotal, currency)}</dd></div>
            <div className="flex justify-between"><dt className="text-ink-500">Discount</dt><dd>−{formatMoney(preview.discountAmount, currency)}</dd></div>
            <div className="flex justify-between"><dt className="text-ink-500">Tax</dt><dd>{formatMoney(preview.taxAmount, currency)}</dd></div>
            <div className="flex justify-between border-t border-sand-200 pt-2 font-semibold text-ink-950">
              <dt>Total</dt><dd>{formatMoney(preview.total, currency)}</dd>
            </div>
          </dl>
          <p className="mt-4 text-[11px] leading-relaxed text-ink-400">
            The invoice is created as a draft. Send it when you&apos;re ready, then record payments as
            they arrive.
          </p>
        </div>
      </form>
    </Modal>
  );
}