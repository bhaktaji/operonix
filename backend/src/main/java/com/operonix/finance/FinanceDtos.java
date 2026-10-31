package com.operonix.finance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the finance module (LLD §17). */
public final class FinanceDtos {

    private FinanceDtos() {
    }

    // ---------------------------------------------------------------- legal entities

    public record LegalEntityRequest(
            @NotBlank @Size(min = 2, max = 160) String name,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a 2-letter country code") String countryCode,
            @Size(max = 32) String taxNumber,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter currency code") String baseCurrency,
            @Pattern(regexp = "^(EXCLUSIVE|INCLUSIVE)$", message = "must be EXCLUSIVE or INCLUSIVE") String taxMode,
            @Size(max = 40) String taxNumberLabel,
            @Size(max = 4000) String address,
            Boolean makeDefault
    ) {
    }

    public record LegalEntityResponse(UUID id, String name, String countryCode, String taxNumber,
                                      String taxNumberLabel, String baseCurrency, String taxMode,
                                      String address, boolean defaultEntity, boolean active, long version) {

        public static LegalEntityResponse from(LegalEntity entity) {
            return new LegalEntityResponse(entity.getId(), entity.getName(), entity.getCountryCode(),
                    entity.getTaxNumber(), entity.getTaxNumberLabel(), entity.getBaseCurrency(),
                    entity.getTaxMode(), entity.getAddress(), entity.isDefaultEntity(), entity.isActive(),
                    entity.getVersion());
        }
    }

    // ------------------------------------------------------------------- locations

    public record LocationRequest(
            UUID legalEntityId,
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a 2-letter country code") String countryCode,
            @NotBlank @Size(max = 64) String timezone,
            @Size(max = 4000) String address,
            Boolean makeDefault,
            Boolean active
    ) {
    }

    public record LocationResponse(UUID id, UUID legalEntityId, String name, String countryCode,
                                   String timezone, String address, boolean defaultLocation,
                                   boolean active, long version) {

        public static LocationResponse from(BusinessLocation entity) {
            return new LocationResponse(entity.getId(), entity.getLegalEntityId(), entity.getName(),
                    entity.getCountryCode(), entity.getTimezone(), entity.getAddress(),
                    entity.isDefaultLocation(), entity.isActive(), entity.getVersion());
        }
    }

    // ----------------------------------------------------------------------- tax

    public record TaxComponentResponse(String code, String name, BigDecimal rate, boolean compound,
                                       int position) {
    }

    public record TaxDefinitionResponse(UUID id, String code, String name, String countryCode,
                                        String mode, String rounding, List<TaxComponentResponse> components) {
    }

    public record TaxProfileResponse(UUID id, String name, UUID legalEntityId, UUID taxDefinitionId,
                                     String taxDefinitionCode, String mode, String effectiveMode,
                                     BigDecimal totalRate, List<TaxComponentResponse> components,
                                     boolean defaultProfile, boolean active, long version) {
    }

    public record TaxProfileRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            UUID legalEntityId,
            @NotNull UUID taxDefinitionId,
            @Pattern(regexp = "^(EXCLUSIVE|INCLUSIVE)$", message = "must be EXCLUSIVE or INCLUSIVE") String mode,
            Boolean makeDefault,
            Boolean active
    ) {
    }

    // ------------------------------------------------------------------ numbering

    public record NumberRuleResponse(String docType, String prefix, boolean includeYear, int padding,
                                     String resetPolicy, String sample) {
    }

    public record NumberRuleRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9/_-]{1,12}$",
                    message = "letters, digits, / _ and - only") String prefix,
            Boolean includeYear,
            @Min(1) @Max(10) Integer padding,
            @Pattern(regexp = "^(YEARLY|NEVER)$", message = "must be YEARLY or NEVER") String resetPolicy
    ) {
    }

    // ---------------------------------------------------------------- exchange rates

    public record ExchangeRateRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter currency code") String base,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter currency code") String quote,
            @NotNull @DecimalMin("0.00000001") @Digits(integer = 10, fraction = 8) BigDecimal rate,
            LocalDate asOf
    ) {
    }

    public record ExchangeRateResponse(String base, String quote, BigDecimal rate, LocalDate asOf,
                                       String source) {

        public static ExchangeRateResponse from(ExchangeRate entity) {
            return new ExchangeRateResponse(entity.getBase(), entity.getQuote(), entity.getRate(),
                    entity.getAsOf(), entity.getSource());
        }
    }

    // -------------------------------------------------------------------- settings

    public record SettingsResponse(UUID tenantId, String locale, String baseCurrency, int currencyPrecision,
                                   String defaultTaxMode, UUID defaultTaxProfileId, UUID defaultLegalEntityId,
                                   short fiscalYearStartMonth, short weekStart, String dateFormat,
                                   String numberFormat) {
    }

    public record SettingsRequest(
            @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "must be a BCP-47 tag such as en-GB") String locale,
            UUID defaultTaxProfileId,
            UUID defaultLegalEntityId,
            @Min(1) @Max(12) Integer fiscalYearStartMonth,
            @Min(0) @Max(6) Integer weekStart,
            @Size(max = 20) String dateFormat,
            @Size(max = 16) String numberFormat
    ) {
    }

    /** Currency metadata the frontend needs to format money without guessing the precision. */
    public record CurrencyResponse(String code, String name, String symbol, int minorUnits) {
    }

    public record TaxAmountResponse(String code, String name, BigDecimal rate, BigDecimal base,
                                    BigDecimal amount) {
    }

    public record FxResponse(String baseCurrency, String documentCurrency, BigDecimal rate,
                             BigDecimal baseTotal) {
    }

}
