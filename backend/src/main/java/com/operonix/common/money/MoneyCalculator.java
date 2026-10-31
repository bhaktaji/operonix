package com.operonix.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Single source of truth for money arithmetic (LLD §5.1).
 * Money is rounded HALF_UP to 2 dp; the header discount reduces the taxable base proportionally.
 */
public final class MoneyCalculator {

    public static final int MONEY_SCALE = 2;
    public static final int QUANTITY_SCALE = 3;
    public static final int TAX_SCALE = 4;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    private MoneyCalculator() {
    }

    public record Line(UUID serviceItemId, String description, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal taxRate) {
    }

    public record PricedLine(UUID serviceItemId, String description, BigDecimal quantity, BigDecimal unitPrice,
                             BigDecimal taxRate, BigDecimal lineSubtotal, BigDecimal lineTax,
                             BigDecimal lineTotal) {
    }

    public record Totals(List<PricedLine> lines, BigDecimal subtotal, BigDecimal discountAmount,
                         BigDecimal taxAmount, BigDecimal total) {
    }

    public static Totals calculate(List<Line> lines, BigDecimal requestedDiscount) {
        List<PricedLine> priced = new ArrayList<>(lines.size());
        BigDecimal subtotal = ZERO;
        BigDecimal taxBeforeDiscount = ZERO;

        for (Line line : lines) {
            BigDecimal lineSubtotal = line.quantity().multiply(line.unitPrice())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal lineTax = lineSubtotal.multiply(line.taxRate())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal lineTotal = lineSubtotal.add(lineTax);
            priced.add(new PricedLine(line.serviceItemId(), line.description(), line.quantity(),
                    line.unitPrice(), line.taxRate(), lineSubtotal, lineTax, lineTotal));
            subtotal = subtotal.add(lineSubtotal);
            taxBeforeDiscount = taxBeforeDiscount.add(lineTax);
        }
        subtotal = subtotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal discount = requestedDiscount == null ? ZERO : requestedDiscount.max(ZERO).min(subtotal)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal factor = subtotal.signum() == 0
                ? BigDecimal.ZERO
                : subtotal.subtract(discount).divide(subtotal, 6, RoundingMode.HALF_UP);
        BigDecimal tax = taxBeforeDiscount.multiply(factor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.subtract(discount).add(tax).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return new Totals(List.copyOf(priced), subtotal, discount, tax, total);
    }

    public static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
