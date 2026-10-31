import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { invoicesApi } from "@/api/endpoints";
import type { Invoice } from "@/api/types";
import { errorMessage } from "@/api/client";
import { Button, ErrorBanner, Field, Input, Select } from "@/components/ui/primitives";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import { formatMoney, fromDateTimeLocal, toDateTimeLocal } from "@/lib/format";

export function RecordPaymentDialog({
  open,
  onClose,
  invoice,
}: {
  open: boolean;
  onClose: () => void;
  invoice: Invoice;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState("CASH");
  const [reference, setReference] = useState("");
  const [paidAt, setPaidAt] = useState(toDateTimeLocal(new Date()));
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setAmount(invoice.balance > 0 ? String(invoice.balance) : "");
    setMethod("CASH");
    setReference("");
    setPaidAt(toDateTimeLocal(new Date()));
    setError(null);
  }, [open, invoice]);

  const mutation = useMutation({
    mutationFn: () => invoicesApi.addPayment(invoice.id, {
      amount: Number(amount),
      method,
      reference: reference.trim() || undefined,
      paidAt: paidAt ? fromDateTimeLocal(paidAt) : undefined,
    }),
    onSuccess: async () => {
      toast.push({ title: "Payment recorded" });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["invoice", invoice.id] }),
        queryClient.invalidateQueries({ queryKey: ["invoices"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
      onClose();
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    const value = Number(amount);
    if (!Number.isFinite(value) || value <= 0) return setError("Enter an amount greater than zero.");
    if (value > invoice.balance) {
      return setError(`The remaining balance is ${formatMoney(invoice.balance, invoice.currency)}.`);
    }
    mutation.mutate();
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Record a payment"
      description={`${invoice.number} · balance ${formatMoney(invoice.balance, invoice.currency)}`}
      size="sm"
      footer={
        <>
          <Button variant="outline" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" form="payment-form" variant="secondary" loading={mutation.isPending}>
            Record payment
          </Button>
        </>
      }
    >
      <form id="payment-form" onSubmit={submit} className="space-y-4" noValidate>
        {error && <ErrorBanner message={error} />}

        <Field label="Amount" htmlFor="pay-amount" hint={`Cannot exceed ${formatMoney(invoice.balance, invoice.currency)}`}>
          <Input
            id="pay-amount"
            inputMode="decimal"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            placeholder="0.00"
          />
        </Field>

        <Field label="Method" htmlFor="pay-method">
          <Select id="pay-method" value={method} onChange={(event) => setMethod(event.target.value)}>
            <option value="CASH">Cash</option>
            <option value="CARD">Card</option>
            <option value="BANK_TRANSFER">Bank transfer</option>
            <option value="CHECK">Check</option>
            <option value="OTHER">Other</option>
          </Select>
        </Field>

        <Field label="Reference" htmlFor="pay-reference" hint="Check number, transfer ID, receipt…">
          <Input
            id="pay-reference"
            value={reference}
            maxLength={120}
            onChange={(event) => setReference(event.target.value)}
          />
        </Field>

        <Field label="Received at" htmlFor="pay-date">
          <Input
            id="pay-date"
            type="datetime-local"
            value={paidAt}
            onChange={(event) => setPaidAt(event.target.value)}
          />
        </Field>
      </form>
    </Modal>
  );
}