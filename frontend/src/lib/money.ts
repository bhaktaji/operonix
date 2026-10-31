export function round2(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

export interface DraftLine {
  key: string;
  serviceItemId: string;
  description: string;
  quantity: string;
  unitPrice: string;
  taxRate: string;
}

export interface PricedDraftLine {
  key: string;
  description: string;
  quantity: number;
  unitPrice: number;
  taxRate: number;
  lineSubtotal: number;
  lineTax: number;
  lineTotal: number;
}

export interface PreviewTotals {
  lines: PricedDraftLine[];
  subtotal: number;
  discountAmount: number;
  taxAmount: number;
  total: number;
}

/** Mirrors the server's MoneyCalculator so the preview matches the saved document. */
export function calculatePreview(lines: DraftLine[], requestedDiscount: number): PreviewTotals {
  const priced = lines.map((line) => {
    const quantity = Number(line.quantity) || 0;
    const unitPrice = Number(line.unitPrice) || 0;
    const taxRate = (Number(line.taxRate) || 0) / 100;
    const lineSubtotal = round2(quantity * unitPrice);
    const lineTax = round2(lineSubtotal * taxRate);
    return {
      key: line.key,
      description: line.description,
      quantity,
      unitPrice,
      taxRate,
      lineSubtotal,
      lineTax,
      lineTotal: round2(lineSubtotal + lineTax),
    };
  });

  const subtotal = round2(priced.reduce((sum, line) => sum + line.lineSubtotal, 0));
  const taxBeforeDiscount = priced.reduce((sum, line) => sum + line.lineTax, 0);
  const discount = round2(Math.min(Math.max(requestedDiscount || 0, 0), subtotal));
  const factor = subtotal === 0 ? 0 : (subtotal - discount) / subtotal;
  const taxAmount = round2(taxBeforeDiscount * factor);
  return {
    lines: priced,
    subtotal,
    discountAmount: discount,
    taxAmount,
    total: round2(subtotal - discount + taxAmount),
  };
}

let lineCounter = 0;

export function newDraftLine(service?: {
  id: string;
  name: string;
  basePrice: number;
  taxRate: number;
}): DraftLine {
  lineCounter += 1;
  return {
    key: `line-${Date.now()}-${lineCounter}`,
    serviceItemId: service?.id ?? "",
    description: service?.name ?? "",
    quantity: "1",
    unitPrice: service ? service.basePrice.toFixed(2) : "",
    taxRate: service ? (service.taxRate * 100).toFixed(2) : "",
  };
}