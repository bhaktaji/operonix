package com.operonix.global;

import java.util.List;

/** Wire shapes for platform reference data, consumed by onboarding and settings. */
public final class ReferenceDtos {

    private ReferenceDtos() {
    }

    public record CountryDto(String code, String name, String defaultCurrency, String defaultLocale,
                             String callingCode, String taxLabel) {

        public static CountryDto from(Country country) {
            return new CountryDto(country.getCode(), country.getName(), country.getDefaultCurrency(),
                    country.getDefaultLocale(), country.getCallingCode(), country.getTaxLabel());
        }
    }

    public record CurrencyDto(String code, String name, String symbol, int minorUnits) {

        public static CurrencyDto from(Currency currency) {
            return new CurrencyDto(currency.getCode(), currency.getName(), currency.getSymbol(),
                    currency.getMinorUnits());
        }
    }

    public record IndustryTemplateDto(String code, String label, String description) {

        public static IndustryTemplateDto from(IndustryTemplate template) {
            return new IndustryTemplateDto(template.name(), template.label(), template.description());
        }
    }

    public record ReferenceData(List<CountryDto> countries, List<CurrencyDto> currencies,
                                List<IndustryTemplateDto> industryTemplates) {
    }
}