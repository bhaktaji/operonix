package com.operonix.finance;

import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.common.money.MoneyCalculator;
import com.operonix.global.ReferenceDataService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Dated FX rates (LLD §17.5).
 *
 * <p>Documents never call this twice for the same amount: the rate used at issue is stored on the
 * document, so inserting a newer rate cannot change an old invoice's base-currency value.
 */
@Service
public class ExchangeRateService {

    private static final int RATE_SCALE = 8;

    private final ExchangeRateRepository rates;
    private final ReferenceDataService referenceData;

    public ExchangeRateService(ExchangeRateRepository rates, ReferenceDataService referenceData) {
        this.rates = rates;
        this.referenceData = referenceData;
    }

    @Transactional(readOnly = true)
    public List<FinanceDtos.ExchangeRateResponse> list() {
        return rates.findAllByOrderByAsOfDescBaseAscQuoteAsc().stream()
                .map(FinanceDtos.ExchangeRateResponse::from)
                .toList();
    }

    @Transactional
    public FinanceDtos.ExchangeRateResponse create(FinanceDtos.ExchangeRateRequest request) {
        String base = normalise(request.base(), "base");
        String quote = normalise(request.quote(), "quote");
        if (base.equals(quote)) {
            throw ApiException.validation("quote", "must differ from the base currency");
        }
        referenceData.requireCurrency(base);
        referenceData.requireCurrency(quote);
        LocalDate asOf = request.asOf() == null ? LocalDate.now() : request.asOf();
        if (rates.existsByBaseAndQuoteAndAsOf(base, quote, asOf)) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "a rate for " + base + "/" + quote + " already exists on " + asOf);
        }
        ExchangeRate rate = new ExchangeRate();
        rate.setBase(base);
        rate.setQuote(quote);
        rate.setRate(request.rate());
        rate.setAsOf(asOf);
        rates.save(rate);
        return FinanceDtos.ExchangeRateResponse.from(rate);
    }

    /**
     * Quote units per one base unit, using the most recent rate on or before {@code asOf}.
     * Identity conversions return 1 without touching the table.
     */
    @Transactional(readOnly = true)
    public BigDecimal rate(String from, String to, LocalDate asOf) {
        String base = normalise(from, "currency");
        String quote = normalise(to, "currency");
        if (base.equals(quote)) {
            return BigDecimal.ONE;
        }
        return rates.findTopByBaseAndQuoteAndAsOfLessThanEqualOrderByAsOfDesc(base, quote, asOf)
                .map(ExchangeRate::getRate)
                .or(() -> rates.findTopByBaseAndQuoteAndAsOfLessThanEqualOrderByAsOfDesc(quote, base, asOf)
                        .map(ExchangeRate::getRate)
                        .map(rate -> BigDecimal.ONE.divide(rate, RATE_SCALE, RoundingMode.HALF_UP)))
                .orElseThrow(() -> ApiException.of(ErrorCode.FX_RATE_MISSING,
                        "no exchange rate for " + base + "/" + quote + " on or before " + asOf)
                        .withProperty("base", base)
                        .withProperty("quote", quote));
    }

    /** Converts an amount into the target currency at the currency's own precision. */
    @Transactional(readOnly = true)
    public BigDecimal convert(BigDecimal amount, String from, String to, LocalDate asOf, int targetScale) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return MoneyCalculator.money(value.multiply(rate(from, to, asOf)), targetScale);
    }

    private String normalise(String code, String field) {
        if (code == null || code.isBlank()) {
            throw ApiException.validation(field, "is required");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
