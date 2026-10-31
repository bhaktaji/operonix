package com.operonix.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single source of truth for money arithmetic (LLD §5.1).
 *
 * <p>Three things became data instead of constants in P1a:
 *
 * <ul>
 *   <li><b>Scale</b> — the number of decimal places comes from {@code currency.minor_units}, so JPY
 *       (0) or KWD (3) round correctly instead of being forced to 2.</li>
 *   <li><b>Mode</b> — {@link TaxMode#EXCLUSIVE} means the entered unit price is net of tax;
 *       {@link TaxMode#INCLUSIVE} means it already contains it, and the gross the user typed must
 *       still be the total.</li>
 *   <li><b>Components</b> — a line can carry several named rates (CGST 9% + SGST 9%) that are
 *       printed separately but summed into one tax amount.</li>
 * </ul>
 *
 * <p>Order of operations is fixed and identical in every mode: price the lines, clamp the header
 * discount to the subtotal, scale the tax by the discounted fraction, then reconcile the component
 * breakdown to the authoritative document tax. All arithmetic is {@link BigDecimal} with HALF_UP
 * rounding at the currency's scale, so the same input always produces the same output.
 *
 * <p>Compound components are summed rather than compounded; every jurisdiction seeded in V3 applies
 * its components additively. Recorded here so nobody assumes compounding happens.
 */
public final class MoneyCalculator {

    public static final int MONEY_SCALE = 2;
    public static final int QUANTITY_SCALE = 3;
    public static final int TAX_SCALE = 4;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    private MoneyCalculator() {
    }

    /** Whether a unit price already contains its tax. */
    public enum TaxMode {
        EXCLUSIVE,
        INCLUSIVE;

        public static TaxMode parse(String value) {
            if (value == null || value.isBlank()) {
                return EXCLUSIVE;
            }
            return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "INCLUSIVE", "INCLUSIVE_TAX", "TAX_INCLUSIVE" -> INCLUSIVE;
                default -> EXCLUSIVE;
            };
        }
    }

    /** One printed tax rate on a line, e.g. {@code CGST} at 0.09. */
    public record Component(String code, String name, BigDecimal rate, boolean compound) {

        public Component(String code, String name, BigDecimal rate) {
            this(code, name, rate, false);
        }
    }

    public record Line(UUID serviceItemId, String description, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal taxRate, List<Component> components, TaxMode mode) {

        /** Legacy shape: a single rate, tax added on top. Keeps every pre-P1a caller valid. */
        public Line(UUID serviceItemId, String description, BigDecimal quantity, BigDecimal unitPrice,
                    BigDecimal taxRate) {
            this(serviceItemId, description, quantity, unitPrice, taxRate, List.of(), TaxMode.EXCLUSIVE);
        }

        public Line {
            components = components == null ? List.of() : List.copyOf(components);
            mode = mode == null ? TaxMode.EXCLUSIVE : mode;
        }

        /** Sum of component rates, or the legacy single rate when no components are attached. */
        public BigDecimal effectiveRate() {
            if (components.isEmpty()) {
                return taxRate;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Component component : components) {
                sum = sum.add(component.rate());
            }
            return sum;
        }

        List<Component> printedComponents() {
            if (!components.isEmpty()) {
                return components;
            }
            return List.of(new Component("TAX", "Tax", taxRate == null ? BigDecimal.ZERO : taxRate, false));
        }
    }

    /** A line as stored on the document; {@code components} are the reconciled printed tax rows. */
    public record PricedLine(UUID serviceItemId, String description, BigDecimal quantity, BigDecimal unitPrice,
                             BigDecimal taxRate, BigDecimal lineSubtotal, BigDecimal lineTax,
                             BigDecimal lineTotal, List<TaxComponentAmount> components, TaxMode mode) {

        public PricedLine {
            components = components == null ? List.of() : List.copyOf(components);
        }
    }

    /** A single printed tax row on a document: {@code CGST 9% on 100.00 = 9.00}. */
    public record TaxComponentAmount(String code, String name, BigDecimal rate, BigDecimal base,
                                     BigDecimal amount) {
    }

    public record Totals(List<PricedLine> lines, BigDecimal subtotal, BigDecimal discountAmount,
                         BigDecimal taxAmount, BigDecimal total, List<TaxComponentAmount> taxComponents) {

        public Totals {
            taxComponents = taxComponents == null ? List.of() : List.copyOf(taxComponents);
        }
    }

    /** Two-decimal calculation, identical to the pre-P1a behaviour. */
    public static Totals calculate(List<Line> lines, BigDecimal requestedDiscount) {
        return calculate(lines, requestedDiscount, MONEY_SCALE);
    }

    /**
     * @param scale decimal places of the document currency (0 for JPY, 2 for USD/INR/EUR, 3 for KWD)
     */
    public static Totals calculate(List<Line> lines, BigDecimal requestedDiscount, int moneyScale) {
        int scale = Math.max(0, moneyScale);
        List<PricedLine> priced = new ArrayList<>(lines.size());
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxBeforeDiscount = BigDecimal.ZERO;

        for (Line line : lines) {
            BigDecimal gross = line.quantity().multiply(line.unitPrice()).setScale(scale, RoundingMode.HALF_UP);
            BigDecimal net;
            BigDecimal lineTax;
            if (line.mode() == TaxMode.INCLUSIVE) {
                // The typed price is the gross: derive the net so that net + tax == gross exactly.
                BigDecimal divisor = BigDecimal.ONE.add(line.effectiveRate());
                net = gross.divide(divisor, scale, RoundingMode.HALF_UP);
                lineTax = gross.subtract(net);
            } else {
                net = gross;
                lineTax = net.multiply(line.effectiveRate()).setScale(scale, RoundingMode.HALF_UP);
            }
            BigDecimal lineTotal = net.add(lineTax);
            priced.add(new PricedLine(line.serviceItemId(), line.description(), line.quantity(), line.unitPrice(),
                    line.taxRate(), net, lineTax, lineTotal, List.of(), line.mode()));
            subtotal = subtotal.add(net);
            taxBeforeDiscount = taxBeforeDiscount.add(lineTax);
        }
        subtotal = subtotal.setScale(scale, RoundingMode.HALF_UP);

        BigDecimal discount = requestedDiscount == null ? zero(scale) : requestedDiscount.max(BigDecimal.ZERO)
                .min(subtotal).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal discountedBase = subtotal.subtract(discount);
        BigDecimal factor = subtotal.signum() == 0
                ? BigDecimal.ZERO
                : discountedBase.divide(subtotal, 6, RoundingMode.HALF_UP);
        BigDecimal tax = taxBeforeDiscount.multiply(factor).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal total = discountedBase.add(tax).setScale(scale, RoundingMode.HALF_UP);

        // Third pass: allocate each line's discounted tax to its printed components. The allocation is
        // reconciled per line, then rolled up to the document, so both the line rows and the document
        // summary add up to the authoritative tax total.
        List<PricedLine> allocated = new ArrayList<>(priced.size());
        BigDecimal allocatedTax = zero(scale);
        for (int i = 0; i < priced.size(); i++) {
            PricedLine line = priced.get(i);
            BigDecimal base = line.lineSubtotal().multiply(factor).setScale(scale, RoundingMode.HALF_UP);
            BigDecimal targetTax = line.lineTax().multiply(factor).setScale(scale, RoundingMode.HALF_UP);
            allocated.add(new PricedLine(line.serviceItemId(), line.description(), line.quantity(),
                    line.unitPrice(), line.taxRate(), line.lineSubtotal(), line.lineTax(), line.lineTotal(),
                    allocate(base, targetTax, lines.get(i).printedComponents(), scale), line.mode()));
            allocatedTax = allocatedTax.add(targetTax);
        }

        List<TaxComponentAmount> components = rollUp(allocated, tax.subtract(allocatedTax));
        return new Totals(List.copyOf(allocated), subtotal, discount, tax, total, components);
    }

    /** Allocates one line's tax to its components and reconciles any rounding drift into the last row. */
    private static List<TaxComponentAmount> allocate(BigDecimal base, BigDecimal targetTax,
                                                     List<Component> components, int scale) {
        if (components.isEmpty()) {
            return List.of();
        }
        List<TaxComponentAmount> rows = new ArrayList<>(components.size());
        BigDecimal sum = zero(scale);
        for (Component component : components) {
            BigDecimal rate = component.rate() == null ? BigDecimal.ZERO : component.rate();
            BigDecimal amount = base.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
            rows.add(new TaxComponentAmount(component.code(), component.name(), rate,
                    base.setScale(scale, RoundingMode.HALF_UP), amount));
            sum = sum.add(amount);
        }
        BigDecimal drift = targetTax.subtract(sum);
        if (drift.signum() != 0) {
            int target = lastTaxable(rows);
            TaxComponentAmount row = rows.get(target);
            rows.set(target, new TaxComponentAmount(row.code(), row.name(), row.rate(), row.base(),
                    row.amount().add(drift)));
        }
        return rows;
    }

    /** Sums per-line rows by tax code and absorbs the document-level rounding drift. */
    private static List<TaxComponentAmount> rollUp(List<PricedLine> lines, BigDecimal drift) {
        Map<String, ComponentTotal> buckets = new LinkedHashMap<>();
        for (PricedLine line : lines) {
            for (TaxComponentAmount row : line.components()) {
                ComponentTotal total = buckets.computeIfAbsent(row.code(),
                        code -> new ComponentTotal(code, row.name(), row.rate()));
                total.addBase(row.base());
                total.addAmount(row.amount());
            }
        }
        if (buckets.isEmpty()) {
            return List.of();
        }

        if (drift.signum() != 0) {
            ComponentTotal target = null;
            for (ComponentTotal candidate : buckets.values()) {
                if (candidate.rate.signum() > 0) {
                    target = candidate;
                }
            }
            (target == null ? buckets.values().iterator().next() : target).addAmount(drift);
        }

        List<TaxComponentAmount> result = new ArrayList<>(buckets.size());
        for (ComponentTotal total : buckets.values()) {
            result.add(new TaxComponentAmount(total.code, total.name, total.rate, total.base, total.amount));
        }
        return result;
    }

    private static int lastTaxable(List<TaxComponentAmount> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).rate().signum() > 0) {
                return i;
            }
        }
        return rows.size() - 1;
    }

    /** Mutable accumulator for one printed tax code across all lines of a document. */
    private static final class ComponentTotal {
        private final String code;
        private final String name;
        private final BigDecimal rate;
        private BigDecimal base = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;

        private ComponentTotal(String code, String name, BigDecimal rate) {
            this.code = code;
            this.name = name;
            this.rate = rate;
        }

        private void addBase(BigDecimal value) {
            base = base.add(value);
        }

        private void addAmount(BigDecimal value) {
            amount = amount.add(value);
        }
    }

    /** Rounds to the given currency scale. */
    public static BigDecimal money(BigDecimal value, int scale) {
        return value == null ? zero(scale) : value.setScale(Math.max(0, scale), RoundingMode.HALF_UP);
    }

    /** Rounds to the two-decimal default used by every pre-P1a code path. */
    public static BigDecimal money(BigDecimal value) {
        return money(value, MONEY_SCALE);
    }

    public static BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(Math.max(0, scale), RoundingMode.HALF_UP);
    }
}
