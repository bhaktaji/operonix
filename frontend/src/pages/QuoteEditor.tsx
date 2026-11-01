import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { ArrowLeft, CalendarClock } from "lucide-react";
import { customersApi, quotesApi, servicesApi } from "@/api/endpoints";
import type { QuoteInput } from "@/api/types";
import { errorMessage, fieldErrors } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { LineItemsEditor } from "@/components/LineItemsEditor";
import { Button, Card, ErrorBanner, Field, Input, PageHeader, Select, Textarea } from "@/components/ui/primitives";
import { useToast } from "@/components/ui/toast";
import { addDaysISO, formatMoney, todayISO } from "@/lib/format";
import { calculatePreview, newDraftLine } from "@/lib/money";
import type { DraftLine } from "@/lib/money";

export function QuoteEditor() {
  const { quoteId } = useParams();
  const isEdit = !!quoteId;
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();
  const { tenant } = useAuth();
  const currency = tenant?.currency ?? "USD";

  const [customerId, setCustomerId] = useState(searchParams.get("customerId") ?? "");
  const [locationId, setLocationId] = useState("");
  const [issueDate, setIssueDate] = useState(todayISO());
  const [validUntil, setValidUntil] = useState(addDaysISO(todayISO(), 14));
  const [discount, setDiscount] = useState("0");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<DraftLine[]>([newDraftLine()]);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [hydrated, setHydrated] = useState(!isEdit);

  const services = useQuery({ queryKey: ["services"], queryFn: () => servicesApi.list() });
  const customers = useQuery({
    queryKey: ["customers", "picker"],
    queryFn: () => customersApi.list({ status: "ACTIVE", size: 100, sort: "name,asc" }),
  });
  const locations = useQuery({
    queryKey: ["locations", customerId],
    queryFn: () => customersApi.locations(customerId),
    enabled: !!customerId,
  });
  const quote = useQuery({
    queryKey: ["quote", quoteId],
    queryFn: () => quotesApi.get(quoteId!),
    enabled: isEdit,
  });

  useEffect(() => {
    if (!quote.data || hydrated) return;
    const data = quote.data;
    setCustomerId(data.customer?.id ?? "");
    setLocationId(data.location?.id ?? "");
    setIssueDate(data.issueDate);
    setValidUntil(data.validUntil);
    setDiscount(String(data.discountAmount ?? 0));
    setNotes(data.notes ?? "");
    setLines(data.lines.map((line) => ({
      key: line.id,
      serviceItemId: line.serviceItemId ?? "",
      description: line.description,
      quantity: String(line.quantity),
      unitPrice: String(line.unitPrice),
      taxRate: String(Number((line.taxRate * 100).toFixed(4))),
    })));
    setHydrated(true);
  }, [quote.data, hydrated]);

  useEffect(() => {
    if (!locationId && locations.data && locations.data.length > 0) {
      setLocationId(locations.data[0].id);
    }
  }, [locations.data, locationId]);

  const preview = useMemo(
    () => calculatePreview(lines, Number(discount) || 0),
    [lines, discount],
  );

  const mutation = useMutation({
    mutationFn: (input: QuoteInput) =>
      isEdit ? quotesApi.update(quoteId!, input) : quotesApi.create(input),
    onSuccess: async (saved) => {
      toast.push({ title: isEdit ? "Quote updated" : "Draft quote created" });
      await queryClient.invalidateQueries({ queryKey: ["quotes"] });
      queryClient.setQueryData(["quote", saved.id], saved);
      navigate(`/quotes/${saved.id}`, { replace: true });
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
    if (!customerId) {
      setError("Choose a customer for this quote.");
      return;
    }
    if (validUntil < issueDate) {
      setError("The validity date cannot be before the issue date.");
      return;
    }
    const payload: QuoteInput = {
      customerId,
      locationId: locationId || null,
      issueDate,
      validUntil,
      discountAmount: Number(discount) || 0,
      notes: notes.trim() || undefined,
      lines: lines.map((line) => ({
        serviceItemId: line.serviceItemId || null,
        description: line.description.trim(),
        quantity: Number(line.quantity) || 0,
        unitPrice: Number(line.unitPrice) || 0,
        taxRate: (Number(line.taxRate) || 0) / 100,
      })),
    };
    mutation.mutate(payload);
  };

  if (isEdit && quote.isLoading) {
    return <div className="py-20 text-center text-sm text-ink-500">Loading quote…</div>;
  }

  if (isEdit && quote.isError) {
    return (
      <div>
        <ErrorBanner title="Quote unavailable" message={errorMessage(quote.error)} />
      </div>
    );
  }

  const locked = isEdit && quote.data?.status !== "DRAFT";

  return (
    <div className="animate-fade-up">
      <Link to={isEdit ? `/quotes/${quoteId}` : "/quotes"} className="mb-5 inline-flex items-center gap-1.5 text-sm text-ink-500 hover:text-ink-900">
        <ArrowLeft className="h-4 w-4" /> {isEdit ? `Back to ${quote.data?.number}` : "Back to quotes"}
      </Link>

      <PageHeader
        kicker={isEdit ? "Edit draft" : "New quote"}
        title={isEdit ? quote.data?.number ?? "Quote" : "Build a quote"}
        description="Prices come from your catalog — override any line for negotiated work."
      />

      {locked && (
        <div className="mb-4">
          <ErrorBanner
            title="This quote is no longer editable"
            message={`Quotes can only be edited while in draft (current status: ${quote.data?.status}).`}
          />
        </div>
      )}

      <form onSubmit={onSubmit} className="grid gap-4 xl:grid-cols-[1.6fr_1fr]">
        <div className="min-w-0 space-y-4">
          {error && <ErrorBanner message={error} />}

          <Card className="p-5">
            <h2 className="font-display text-[15px] font-semibold text-ink-900">Customer & timing</h2>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <Field label="Customer" htmlFor="quote-customer" error={errors.customerId}>
                <Select
                  id="quote-customer"
                  value={customerId}
                  disabled={locked}
                  onChange={(event) => {
                    setCustomerId(event.target.value);
                    setLocationId("");
                  }}
                >
                  <option value="">Select a customer…</option>
                  {customers.data?.content.map((customer) => (
                    <option key={customer.id} value={customer.id}>{customer.name}</option>
                  ))}
                </Select>
                {customers.data && customers.data.content.length === 0 && (
                  <p className="mt-1.5 text-xs text-amber-700">
                    No active customers yet. <Link to="/customers" className="font-medium underline">Add one first</Link>.
                  </p>
                )}
              </Field>

              <Field label="Service location" htmlFor="quote-location" error={errors.locationId}>
                <Select
                  id="quote-location"
                  value={locationId}
                  disabled={locked || !customerId || locations.isLoading}
                  onChange={(event) => setLocationId(event.target.value)}
                >
                  <option value="">{customerId ? "Default location" : "Choose a customer first"}</option>
                  {locations.data?.map((location) => (
                    <option key={location.id} value={location.id}>
                      {location.label} — {location.city}
                    </option>
                  ))}
                </Select>
              </Field>

              <Field label="Issue date" htmlFor="quote-issue" error={errors.issueDate}>
                <Input
                  id="quote-issue"
                  type="date"
                  value={issueDate}
                  disabled={locked}
                  onChange={(event) => setIssueDate(event.target.value)}
                />
              </Field>

              <Field label="Valid until" htmlFor="quote-valid" error={errors.validUntil}>
                <Input
                  id="quote-valid"
                  type="date"
                  value={validUntil}
                  disabled={locked}
                  onChange={(event) => setValidUntil(event.target.value)}
                />
              </Field>
            </div>
          </Card>

          <Card className="p-5">
            <div className="mb-4 flex items-center justify-between gap-2">
              <h2 className="font-display text-[15px] font-semibold text-ink-900">Line items</h2>
              <span className="text-[12px] text-ink-400">{lines.length} line{lines.length === 1 ? "" : "s"}</span>
            </div>
            <LineItemsEditor
              lines={lines}
              services={services.data ?? []}
              currency={currency}
              onChange={setLines}
              disabled={locked}
            />
            {errors.lines && <p className="mt-2 text-xs font-medium text-rose-600">{errors.lines}</p>}
          </Card>

          <Card className="p-5">
            <h2 className="font-display text-[15px] font-semibold text-ink-900">Notes</h2>
            <div className="mt-3">
              <Field label="Visible to the customer" htmlFor="quote-notes" error={errors.notes}>
                <Textarea
                  id="quote-notes"
                  rows={3}
                  value={notes}
                  disabled={locked}
                  onChange={(event) => setNotes(event.target.value)}
                  placeholder="Scope, exclusions, access instructions…"
                />
              </Field>
            </div>
          </Card>
        </div>

        <div className="space-y-4 xl:sticky xl:top-6 xl:self-start">
          <Card className="p-5">
            <h2 className="font-display text-[15px] font-semibold text-ink-900">Summary</h2>
            <dl className="mt-4 space-y-2.5 text-sm">
              <div className="flex justify-between">
                <dt className="text-ink-500">Subtotal</dt>
                <dd className="font-medium text-ink-900">{formatMoney(preview.subtotal, currency)}</dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-ink-500">Discount</dt>
                <dd className="w-28">
                  <Input
                    aria-label="Discount amount"
                    inputMode="decimal"
                    value={discount}
                    disabled={locked}
                    onChange={(event) => setDiscount(event.target.value)}
                    className="h-8 py-1 text-right text-[13px]"
                  />
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-ink-500">Tax</dt>
                <dd className="font-medium text-ink-900">{formatMoney(preview.taxAmount, currency)}</dd>
              </div>
              <div className="flex justify-between border-t border-sand-200 pt-2.5">
                <dt className="font-medium text-ink-900">Total</dt>
                <dd className="font-display text-lg font-semibold text-ink-950">
                  {formatMoney(preview.total, currency)}
                </dd>
              </div>
            </dl>

            <div className="mt-5 space-y-2">
              <Button
                type="submit"
                variant="secondary"
                className="w-full"
                loading={mutation.isPending}
                disabled={locked}
              >
                {isEdit ? "Save changes" : "Save draft"}
              </Button>
              <Link to={isEdit ? `/quotes/${quoteId}` : "/quotes"} className="block">
                <Button type="button" variant="outline" className="w-full">Cancel</Button>
              </Link>
            </div>
          </Card>

          <div className="rounded-xl2 border border-sand-200 bg-white/60 p-4 text-[12px] leading-relaxed text-ink-500">
            <p className="flex items-center gap-1.5 font-medium text-ink-700">
              <CalendarClock className="h-3.5 w-3.5" /> Server-side totals
            </p>
            <p className="mt-1">
              Line prices and taxes are recomputed when saved. A quote can be sent once it has at
              least one priced line, and edited only while it is a draft.
            </p>
          </div>
        </div>
      </form>
    </div>
  );
}