import type { ServiceItem } from "@/api/types";
import { Button, Input, Select } from "@/components/ui/primitives";
import { Plus, Trash2 } from "lucide-react";
import { calculatePreview } from "@/lib/money";
import type { DraftLine } from "@/lib/money";
import { newDraftLine } from "@/lib/money";
import { formatMoney } from "@/lib/format";

export function LineItemsEditor({
  lines,
  services,
  currency,
  onChange,
  disabled = false,
}: {
  lines: DraftLine[];
  services: ServiceItem[];
  currency: string;
  onChange: (lines: DraftLine[]) => void;
  disabled?: boolean;
}) {
  const preview = calculatePreview(lines, 0);
  const priceByKey = new Map(preview.lines.map((line) => [line.key, line]));

  const update = (key: string, changes: Partial<DraftLine>) => {
    onChange(lines.map((line) => (line.key === key ? { ...line, ...changes } : line)));
  };

  const selectService = (key: string, serviceId: string) => {
    const service = services.find((item) => item.id === serviceId);
    if (!service) {
      update(key, { serviceItemId: "" });
      return;
    }
    update(key, {
      serviceItemId: service.id,
      description: service.name,
      unitPrice: service.basePrice.toFixed(2),
      taxRate: (service.taxRate * 100).toFixed(2),
    });
  };

  const addLine = (serviceId = "") => {
    const service = services.find((item) => item.id === serviceId);
    onChange([...lines, newDraftLine(service)]);
  };

  return (
    <div className="space-y-3">
      {lines.length === 0 && (
        <div className="rounded-lg border border-dashed border-sand-300 px-4 py-8 text-center">
          <p className="text-sm font-medium text-ink-700">No line items yet</p>
          <p className="mt-1 text-[12px] text-ink-400">Pick a catalog service or add a custom line.</p>
          {!disabled && (
            <div className="mt-4">
              <Button size="sm" variant="outline" icon={<Plus className="h-3.5 w-3.5" />} onClick={() => addLine()}>
                Add line
              </Button>
            </div>
          )}
        </div>
      )}

      {lines.map((line, index) => {
        const priced = priceByKey.get(line.key);
        return (
          <div key={line.key} className="rounded-lg border border-sand-200 bg-white p-3">
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-12 sm:items-end">
              <div className="col-span-2 sm:col-span-4">
                <label className="label" htmlFor={`svc-${line.key}`}>Catalog service</label>
                <Select
                  id={`svc-${line.key}`}
                  value={line.serviceItemId}
                  disabled={disabled}
                  onChange={(event) => selectService(line.key, event.target.value)}
                >
                  <option value="">Custom line…</option>
                  {services.filter((service) => service.active || service.id === line.serviceItemId).map((service) => (
                    <option key={service.id} value={service.id}>
                      {service.name} · {formatMoney(service.basePrice, currency)}
                    </option>
                  ))}
                </Select>
              </div>

              <div className="col-span-2 sm:col-span-4">
                <label className="label" htmlFor={`desc-${line.key}`}>Description</label>
                <Input
                  id={`desc-${line.key}`}
                  value={line.description}
                  disabled={disabled}
                  placeholder={`Line ${index + 1}`}
                  onChange={(event) => update(line.key, { description: event.target.value })}
                />
              </div>

              <div className="sm:col-span-1">
                <label className="label" htmlFor={`qty-${line.key}`}>Qty</label>
                <Input
                  id={`qty-${line.key}`}
                  inputMode="decimal"
                  value={line.quantity}
                  disabled={disabled}
                  onChange={(event) => update(line.key, { quantity: event.target.value })}
                />
              </div>

              <div className="sm:col-span-1">
                <label className="label" htmlFor={`price-${line.key}`}>Price</label>
                <Input
                  id={`price-${line.key}`}
                  inputMode="decimal"
                  value={line.unitPrice}
                  disabled={disabled}
                  onChange={(event) => update(line.key, { unitPrice: event.target.value })}
                />
              </div>

              <div className="sm:col-span-1">
                <label className="label" htmlFor={`tax-${line.key}`}>Tax %</label>
                <Input
                  id={`tax-${line.key}`}
                  inputMode="decimal"
                  value={line.taxRate}
                  disabled={disabled}
                  onChange={(event) => update(line.key, { taxRate: event.target.value })}
                />
              </div>

              <div className="flex items-end justify-between gap-2 sm:col-span-1 sm:flex-col sm:items-end">
                <div className="text-right">
                  <p className="text-[10px] font-semibold uppercase tracking-wide text-ink-400">Total</p>
                  <p className="text-sm font-semibold text-ink-900">
                    {formatMoney(priced?.lineTotal ?? 0, currency)}
                  </p>
                </div>
                {!disabled && (
                  <button
                    type="button"
                    aria-label={`Remove line ${index + 1}`}
                    onClick={() => onChange(lines.filter((item) => item.key !== line.key))}
                    className="rounded-md p-1.5 text-ink-400 transition hover:bg-rose-50 hover:text-rose-600"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                )}
              </div>
            </div>
          </div>
        );
      })}

      {!disabled && lines.length > 0 && (
        <Button size="sm" variant="ghost" icon={<Plus className="h-3.5 w-3.5" />} onClick={() => addLine()}>
          Add another line
        </Button>
      )}
    </div>
  );
}