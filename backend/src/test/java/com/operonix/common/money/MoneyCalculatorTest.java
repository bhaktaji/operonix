package com.operonix.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyCalculatorTest {

    private static MoneyCalculator.Line line(String qty, String price, String tax) {
        return new MoneyCalculator.Line(null, "line", new BigDecimal(qty), new BigDecimal(price),
                new BigDecimal(tax));
    }

    @Test
    void sumsLineSubtotalsAndTax() {
        MoneyCalculator.Totals totals = MoneyCalculator.calculate(
                List.of(line("2", "100.00", "0.10"), line("1", "50.00", "0.10")), null);

        assertThat(totals.subtotal()).isEqualByComparingTo("250.00");
        assertThat(totals.taxAmount()).isEqualByComparingTo("25.00");
        assertThat(totals.total()).isEqualByComparingTo("275.00");
        assertThat(totals.discountAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void discountReducesTheTaxableBaseProportionally() {
        MoneyCalculator.Totals totals = MoneyCalculator.calculate(
                List.of(line("2", "100.00", "0.10"), line("1", "50.00", "0.10")),
                new BigDecimal("20.00"));

        assertThat(totals.subtotal()).isEqualByComparingTo("250.00");
        assertThat(totals.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(totals.taxAmount()).isEqualByComparingTo("23.00");
        assertThat(totals.total()).isEqualByComparingTo("253.00");
    }

    @Test
    void clampsDiscountToSubtotalAndZeroesTax() {
        MoneyCalculator.Totals totals = MoneyCalculator.calculate(
                List.of(line("1", "80.00", "0.20")), new BigDecimal("500.00"));

        assertThat(totals.discountAmount()).isEqualByComparingTo("80.00");
        assertThat(totals.taxAmount()).isEqualByComparingTo("0.00");
        assertThat(totals.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void roundsHalfUpAtTwoDecimalPlaces() {
        MoneyCalculator.Totals totals = MoneyCalculator.calculate(
                List.of(line("1.5", "33.33", "0.0825")), null);

        assertThat(totals.lines().getFirst().lineSubtotal()).isEqualByComparingTo("50.00");
        assertThat(totals.lines().getFirst().lineTax()).isEqualByComparingTo("4.13");
        assertThat(totals.total()).isEqualByComparingTo("54.13");
    }

    @Test
    void emptyDocumentIsZero() {
        MoneyCalculator.Totals totals = MoneyCalculator.calculate(List.of(), null);

        assertThat(totals.subtotal()).isEqualByComparingTo("0.00");
        assertThat(totals.taxAmount()).isEqualByComparingTo("0.00");
        assertThat(totals.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void keepsThreeDecimalQuantitiesInTheSnapshot() {
        MoneyCalculator.Totals totals = MoneyCalculator.calculate(
                List.of(line("2.375", "19.99", "0.05")), null);

        assertThat(totals.lines().getFirst().quantity()).isEqualByComparingTo("2.375");
        assertThat(totals.lines().getFirst().lineSubtotal()).isEqualByComparingTo("47.48");
    }
}
