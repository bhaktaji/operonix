package com.operonix.global;

import com.operonix.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Single place that answers "is this country/currency real and active?". Registration, onboarding and
 * settings all validate through here so a typo cannot become a tenant's permanent configuration.
 */
@Service
public class ReferenceDataService {

    private final CountryRepository countries;
    private final CurrencyRepository currencies;

    public ReferenceDataService(CountryRepository countries, CurrencyRepository currencies) {
        this.countries = countries;
        this.currencies = currencies;
    }

    @Transactional(readOnly = true)
    public ReferenceDtos.ReferenceData all() {
        List<ReferenceDtos.CountryDto> countryDtos = countries.findAllByActiveTrueOrderByNameAsc().stream()
                .map(ReferenceDtos.CountryDto::from).toList();
        List<ReferenceDtos.CurrencyDto> currencyDtos = currencies.findAllByActiveTrueOrderByCodeAsc().stream()
                .map(ReferenceDtos.CurrencyDto::from).toList();
        List<ReferenceDtos.IndustryTemplateDto> templates = IndustryTemplate.all().stream()
                .map(ReferenceDtos.IndustryTemplateDto::from).toList();
        return new ReferenceDtos.ReferenceData(countryDtos, currencyDtos, templates);
    }

    @Transactional(readOnly = true)
    public Country requireCountry(String code) {
        String normalised = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return countries.findByCodeAndActiveTrue(normalised)
                .orElseThrow(() -> ApiException.validation("countryCode", "is not a supported country yet"));
    }

    @Transactional(readOnly = true)
    public Currency requireCurrency(String code) {
        String normalised = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return currencies.findByCodeAndActiveTrue(normalised)
                .orElseThrow(() -> ApiException.validation("currency", "is not a supported ISO currency"));
    }

    public IndustryTemplate resolveIndustry(String value) {
        IndustryTemplate template = IndustryTemplate.from(value);
        if (template == null) {
            throw ApiException.validation("industryTemplate", "is not a known industry template");
        }
        return template;
    }
}