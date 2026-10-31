package com.operonix.finance;

import com.operonix.common.error.ApiException;
import com.operonix.common.money.MoneyCalculator;
import com.operonix.global.Currency;
import com.operonix.global.ReferenceDataService;
import com.operonix.tenancy.Tenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Prepares everything a quote or invoice needs before a single number is calculated: which entity
 * sells, which currency and precision apply, which tax configuration to snapshot, and the FX rate
 * that freezes the converted value (LLD §17.4).
 *
 * <p>Keeping this in one place is what stops country-specific branches leaking into the quoting and
 * billing services.
 */
@Service
public class FinanceService {

    private final OrganisationService organisation;
    private final TaxService tax;
    private final ExchangeRateService exchangeRates;
    private final ReferenceDataService referenceData;

    public FinanceService(OrganisationService organisation, TaxService tax,
                          ExchangeRateService exchangeRates, ReferenceDataService referenceData) {
        this.organisation = organisation;
        this.tax = tax;
        this.exchangeRates = exchangeRates;
        this.referenceData = referenceData;
    }

    /** Immutable snapshot of the financial context a document is created in. */
    public record DocumentSetup(UUID legalEntityId, String legalEntityName, String legalEntityTaxNumber,
                                String taxNumberLabel, UUID taxProfileId, String taxProfileName,
                                MoneyCalculator.TaxMode taxMode, String currency, int scale,
                                String baseCurrency, int baseScale, BigDecimal fxRate) {
    }

    @Transactional(readOnly = true)
    public DocumentSetup prepare(UUID tenantId, Tenant tenant, String requestedCurrency, LocalDate asOf) {
        LegalEntity entity = organisation.defaultEntity(tenantId);
        String baseCurrency = entity == null ? tenant.getCurrency() : entity.getBaseCurrency();
        Currency currency = resolveCurrency(requestedCurrency == null ? tenant.getCurrency() : requestedCurrency);
        Currency base = referenceData.requireCurrency(baseCurrency);

        TaxProfile profile = tax.defaultProfile(tenantId);
        MoneyCalculator.TaxMode mode = entity != null && profile == null
                ? MoneyCalculator.TaxMode.parse(entity.getTaxMode())
                : tax.documentMode(tenantId);

        BigDecimal rate = currency.getCode().equals(base.getCode())
                ? BigDecimal.ONE
                : exchangeRates.rate(currency.getCode(), base.getCode(), asOf == null ? LocalDate.now() : asOf);

        return new DocumentSetup(entity == null ? null : entity.getId(),
                entity == null ? tenant.getName() : entity.getName(),
                entity == null ? tenant.getTaxNumber() : entity.getTaxNumber(),
                entity == null ? null : entity.getTaxNumberLabel(),
                profile == null ? null : profile.getId(),
                profile == null ? null : profile.getName(),
                mode, currency.getCode(), currency.getMinorUnits(), base.getCode(),
                base.getMinorUnits(), rate);
    }

    /** Converts a document total into the workspace base currency at the snapshotted rate. */
    public BigDecimal toBase(BigDecimal total, DocumentSetup setup) {
        BigDecimal amount = total == null ? BigDecimal.ZERO : total;
        return MoneyCalculator.money(amount.multiply(setup.fxRate()), setup.baseScale());
    }

    public int precision(String currencyCode) {
        return tax.precision(currencyCode);
    }

    /** Tax configuration for one document line, resolved through the catalog item's profile. */
    public TaxService.LineTax lineTax(UUID tenantId, UUID taxProfileId, BigDecimal fallbackRate,
                                     MoneyCalculator.TaxMode documentMode) {
        return tax.lineTax(tenantId, taxProfileId, fallbackRate, documentMode);
    }

    private Currency resolveCurrency(String code) {
        return referenceData.requireCurrency(code.trim().toUpperCase(Locale.ROOT));
    }
}
