package com.operonix.finance;

import com.operonix.global.Country;
import com.operonix.global.CountryRepository;
import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantRepository;
import com.operonix.tenancy.WorkspaceProvisionedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Gives every new workspace the same financial scaffolding the V3 migration backfilled for existing
 * ones: a default legal entity, the country's standard tax profile, a settings row and one location.
 *
 * <p>A workspace with no defaults would leave the settings screen empty and documents without a
 * seller, so this is part of creating a workspace rather than an optional setup step.
 */
@Component
public class WorkspaceFinanceBootstrap {

    private static final String MAIN_LOCATION = "Main location";

    private final TenantRepository tenants;
    private final CountryRepository countries;
    private final TaxDefinitionRepository definitions;
    private final LegalEntityRepository legalEntities;
    private final TaxProfileRepository taxProfiles;
    private final BusinessLocationRepository locations;
    private final TenantFinancialSettingsRepository settings;

    public WorkspaceFinanceBootstrap(TenantRepository tenants, CountryRepository countries,
                                     TaxDefinitionRepository definitions, LegalEntityRepository legalEntities,
                                     TaxProfileRepository taxProfiles, BusinessLocationRepository locations,
                                     TenantFinancialSettingsRepository settings) {
        this.tenants = tenants;
        this.countries = countries;
        this.definitions = definitions;
        this.legalEntities = legalEntities;
        this.taxProfiles = taxProfiles;
        this.locations = locations;
        this.settings = settings;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onWorkspaceProvisioned(WorkspaceProvisionedEvent event) {
        UUID tenantId = event.tenantId();
        Tenant tenant = tenants.findById(tenantId).orElse(null);
        if (tenant == null) {
            return;
        }

        Country country = countries.findById(tenant.getCountryCode()).orElse(null);
        Instant now = Instant.now();

        LegalEntity entity = new LegalEntity();
        entity.setTenantId(tenantId);
        entity.setName(tenant.getLegalName() == null ? tenant.getName() : tenant.getLegalName());
        entity.setCountryCode(tenant.getCountryCode());
        entity.setTaxNumber(tenant.getTaxNumber());
        entity.setBaseCurrency(tenant.getCurrency());
        entity.setTaxMode("EXCLUSIVE");
        entity.setTaxNumberLabel(country == null ? null : country.getTaxLabel());
        entity.setDefaultEntity(true);
        legalEntities.save(entity);

        TaxDefinition definition = defaultDefinition(tenant.getCountryCode());
        if (definition != null) {
            TaxProfile profile = new TaxProfile();
            profile.setTenantId(tenantId);
            profile.setLegalEntityId(entity.getId());
            profile.setName(definition.getName());
            profile.setTaxDefinitionId(definition.getId());
            profile.setMode(definition.getMode());
            profile.setDefaultProfile(true);
            taxProfiles.save(profile);

            TenantFinancialSettings row = new TenantFinancialSettings();
            row.setTenantId(tenantId);
            row.setLocale(tenant.getLocale());
            row.setDefaultTaxProfileId(profile.getId());
            row.setDefaultLegalEntityId(entity.getId());
            row.setCreatedAt(now);
            settings.save(row);
        }

        BusinessLocation location = new BusinessLocation();
        location.setTenantId(tenantId);
        location.setLegalEntityId(entity.getId());
        location.setName(MAIN_LOCATION);
        location.setCountryCode(tenant.getCountryCode());
        location.setTimezone(tenant.getTimezone());
        location.setDefaultLocation(true);
        locations.save(location);
    }

    private TaxDefinition defaultDefinition(String countryCode) {
        String code = switch (countryCode) {
            case "IN" -> "IN-GST-18";
            case "US" -> "US-CA-SALES-825";
            case "GB" -> "GB-VAT-20";
            case "JP" -> "JP-CT-10";
            case "AU" -> "AU-GST-10";
            case "DE" -> "DE-VAT-19";
            default -> "GLOBAL-EXEMPT";
        };
        return definitions.findByCode(code).orElse(null);
    }
}
