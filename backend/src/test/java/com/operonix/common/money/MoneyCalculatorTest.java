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

    // ------------------------------------------------------------------ P1a precision (JPY)

    @Test
    void zeroDecimalCurrencyRoundsToWholeUnits() {
        MoneyCalculator.Totals totals = MoneyCalculator.calculate(
                List.of(line("3", "1200", "0.10")), null, 0);

        assertThat(totals.subtotal()).isEqualByComparingTo("3600");
        assertThat(totals.taxAmount()).isEqualByComparingTo("360");
        assertThat(totals.total()).isEqualByComparingTo("3960");
        assertThat(totals.total().scale()).isZero();
    }

    @Test
    void zeroDecimalCurrencyRoundsHalfUpNotHalfEven() {
        // 1 x 10.5 at 0 dp has no fractional part after rounding, but 10.5*1.1 = 11.55 -> 12.
        MoneyCalculator.Totals totals = MoneyCalculator.calculate(
                List.of(line("1", "10.5", "0.10")), null, 0);

        assertThat(totals.subtotal()).isEqualByComparingTo("11");
        assertThat(totals.taxAmount()).isEqualByComparingTo("1");
        assertThat(totals.total()).isEqualByComparingTo("12");
    }

    // -------------------------------------------------------- P1a inclusive vs exclusive tax

    @Test
    void inclusiveTaxKeepsTheEnteredGrossAsTheTotal() {
        MoneyCalculator.Line inclusive = new MoneyCalculator.Line(null, "line", new BigDecimal("1"),
                new BigDecimal("1200"), new BigDecimal("0.10"), List.of(), MoneyCalculator.TaxMode.INCLUSIVE);

        MoneyCalculator.Totals totals = MoneyCalculator.calculate(List.of(inclusive), null, 0);

        assertThat(totals.total()).isEqualByComparingTo("1200");
        assertThat(totals.subtotal()).isEqualByComparingTo("1091");
        assertThat(totals.taxAmount()).isEqualByComparingTo("109");
    }

    @Test
    void inclusiveAndExclusiveDifferOnlyInWhereTheTaxSits() {
        MoneyCalculator.Line exclusive = new MoneyCalculator.Line(null, "line", new BigDecimal("1"),
                new BigDecimal("100.00"), new BigDecimal("0.20"));
        MoneyCalculator.Line inclusive = new MoneyCalculator.Line(null, "line", new BigDecimal("1"),
                new BigDecimal("100.00"), new BigDecimal("0.20"), List.of(), MoneyCalculator.TaxMode.INCLUSIVE);

        MoneyCalculator.Totals exclusiveTotals = MoneyCalculator.calculate(List.of(exclusive), null);
        MoneyCalculator.Totals inclusiveTotals = MoneyCalculator.calculate(List.of(inclusive), null);

        assertThat(exclusiveTotals.total()).isEqualByComparingTo("120.00");
        assertThat(inclusiveTotals.total()).isEqualByComparingTo("100.00");
        assertThat(inclusiveTotals.taxAmount()).isEqualByComparingTo("16.67");
        assertThat(inclusiveTotals.subtotal()).isEqualByComparingTo("83.33");
    }

    @Test
    void inclusiveDiscountNeverProducesNegativeTax() {
        MoneyCalculator.Line inclusive = new MoneyCalculator.Line(null, "line", new BigDecimal("1"),
                new BigDecimal("100.00"), new BigDecimal("0.20"), List.of(), MoneyCalculator.TaxMode.INCLUSIVE);

        MoneyCalculator.Totals totals = MoneyCalculator.calculate(List.of(inclusive), new BigDecimal("100.00"));

        assertThat(totals.discountAmount()).isEqualByComparingTo("83.33");
        assertThat(totals.taxAmount()).isEqualByComparingTo("0.00");
        assertThat(totals.total()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------- P1a component breakdown

    @Test
    void splitsGstIntoPrintedComponentsThatSumToTheDocumentTax() {
        List<MoneyCalculator.Component> gst = List.of(
                new MoneyCalculator.Component("CGST", "Central GST", new BigDecimal("0.09")),
                new MoneyCalculator.Component("SGST", "State GST", new BigDecimal("0.09")));
        MoneyCalculator.Line line = new MoneyCalculator.Line(null, "line", new BigDecimal("2"),
                new BigDecimal("500.00"), new BigDecimal("0.18"), gst, MoneyCalculator.TaxMode.EXCLUSIVE);

        MoneyCalculator.Totals totals = MoneyCalculator.calculate(List.of(line), null);

        assertThat(totals.taxAmount()).isEqualByComparingTo("180.00");
        assertThat(totals.taxComponents()).hasSize(2);
        assertThat(totals.taxComponents().get(0).code()).isEqualTo("CGST");
        assertThat(totals.taxComponents().get(0).amount()).isEqualByComparingTo("90.00");
        assertThat(totals.taxComponents().get(1).amount()).isEqualByComparingTo("90.00");
        assertThat(totals.taxComponents().stream().map(MoneyCalculator.TaxComponentAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(totals.taxAmount());
    }

    @Test
    void singleRateLineStillProducesOnePrintedComponent() {
        MoneyCalculator.Totals totals = MoneyCalculator.calculate(
                List.of(line("1", "100.00", "0.0825")), null);

        assertThat(totals.taxComponents()).hasSize(1);
        assertThat(totals.taxComponents().getFirst().amount()).isEqualByComparingTo("8.25");
    }

    @Test
    void componentBreakdownReconcilesRoundingDriftToTheDocumentTax() {
        List<MoneyCalculator.Component> thirds = List.of(
                new MoneyCalculator.Component("A", "A", new BigDecimal("0.03333")),
                new MoneyCalculator.Component("B", "B", new BigDecimal("0.03333")),
                new MoneyCalculator.Component("C", "C", new BigDecimal("0.03334")));
        MoneyCalculator.Line line = new MoneyCalculator.Line(null, "line", new BigDecimal("3"),
                new BigDecimal("33.33"), new BigDecimal("0.10"), thirds, MoneyCalculator.TaxMode.EXCLUSIVE);

        MoneyCalculator.Totals totals = MoneyCalculator.calculate(List.of(line), null);

        assertThat(totals.taxComponents().stream().map(MoneyCalculator.TaxComponentAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(totals.taxAmount());
    }
}
