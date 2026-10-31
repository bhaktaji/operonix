package com.operonix.finance;

import com.operonix.common.error.ApiException;
import com.operonix.common.money.MoneyCalculator;
import com.operonix.global.Currency;
import com.operonix.global.ReferenceDataService;
import com.operonix.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the tax rule a document line must use (LLD §17.3).
 *
 * <p>Business logic never touches a {@link TaxDefinition}: a catalog item points at a tenant
 * {@link TaxProfile}, the profile names a definition and (optionally) overrides its mode, and the
 * definition supplies the printed components. That indirection is what makes a rate change a row
 * change instead of a code change.
 */
@Service
public class TaxService {

    private final TaxDefinitionRepository definitions;
    private final TaxComponentRepository components;
    private final TaxProfileRepository profiles;
    private final ReferenceDataService referenceData;
    private final CurrentUser currentUser;

    public TaxService(TaxDefinitionRepository definitions, TaxComponentRepository components,
                      TaxProfileRepository profiles, ReferenceDataService referenceData,
                      CurrentUser currentUser) {
        this.definitions = definitions;
        this.components = components;
        this.profiles = profiles;
        this.referenceData = referenceData;
        this.currentUser = currentUser;
    }

    /** The tax configuration applied to one line, already in calculator form. */
    public record LineTax(BigDecimal rate, List<MoneyCalculator.Component> components,
                          MoneyCalculator.TaxMode mode, UUID profileId, String profileName) {

        public String snapshotMode() {
            return mode.name();
        }
    }

    @Transactional(readOnly = true)
    public List<FinanceDtos.TaxDefinitionResponse> listDefinitions() {
        Map<UUID, List<TaxComponent>> byDefinition = componentsByDefinition();
        return definitions.findAllByActiveTrueOrderByCountryCodeAscNameAsc().stream()
                .map(definition -> new FinanceDtos.TaxDefinitionResponse(definition.getId(),
                        definition.getCode(), definition.getName(), definition.getCountryCode(),
                        definition.getMode(), definition.getRounding(),
                        toResponses(byDefinition.getOrDefault(definition.getId(), List.of()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FinanceDtos.TaxProfileResponse> listProfiles() {
        UUID tenantId = currentUser.tenantId();
        Map<UUID, TaxDefinition> definitionById = new LinkedHashMap<>();
        definitions.findAll().forEach(definition -> definitionById.put(definition.getId(), definition));
        Map<UUID, List<TaxComponent>> byDefinition = componentsByDefinition();
        return profiles.findAllByTenantIdOrderByDefaultProfileDescNameAsc(tenantId).stream()
                .map(profile -> toResponse(profile, definitionById.get(profile.getTaxDefinitionId()),
                        byDefinition.getOrDefault(profile.getTaxDefinitionId(), List.of())))
                .toList();
    }

    @Transactional
    public FinanceDtos.TaxProfileResponse create(FinanceDtos.TaxProfileRequest request) {
        UUID tenantId = currentUser.tenantId();
        String name = request.name().trim();
        if (profiles.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw ApiException.validation("name", "a tax profile with this name already exists");
        }
        TaxDefinition definition = requireDefinition(request.taxDefinitionId());
        TaxProfile profile = new TaxProfile();
        profile.setTenantId(tenantId);
        profile.setName(name);
        profile.setLegalEntityId(request.legalEntityId());
        profile.setTaxDefinitionId(definition.getId());
        profile.setMode(request.mode());
        profile.setActive(request.active() == null || request.active());
        boolean first = profiles.count() == 0L;
        profile.setDefaultProfile(Boolean.TRUE.equals(request.makeDefault()) || first);
        if (profile.isDefaultProfile()) {
            clearDefault(tenantId);
        }
        profiles.save(profile);
        return toResponse(profile, definition, componentsOf(definition.getId()));
    }

    @Transactional
    public FinanceDtos.TaxProfileResponse update(UUID profileId, FinanceDtos.TaxProfileRequest request) {
        UUID tenantId = currentUser.tenantId();
        TaxProfile profile = require(tenantId, profileId);
        String name = request.name().trim();
        if (profiles.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, name, profileId)) {
            throw ApiException.validation("name", "a tax profile with this name already exists");
        }
        TaxDefinition definition = requireDefinition(request.taxDefinitionId());
        profile.setName(name);
        profile.setLegalEntityId(request.legalEntityId());
        profile.setTaxDefinitionId(definition.getId());
        profile.setMode(request.mode());
        if (request.active() != null) {
            profile.setActive(request.active());
        }
        if (Boolean.TRUE.equals(request.makeDefault())) {
            clearDefault(tenantId);
            profile.setDefaultProfile(true);
        }
        profiles.save(profile);
        return toResponse(profile, definition, componentsOf(definition.getId()));
    }

    @Transactional
    public void delete(UUID profileId) {
        UUID tenantId = currentUser.tenantId();
        TaxProfile profile = require(tenantId, profileId);
        if (profile.isDefaultProfile()) {
            throw ApiException.conflict(com.operonix.common.error.ErrorCode.CONFLICT,
                    "choose another default tax profile before deleting this one");
        }
        profile.setActive(false);
    }

    /** The profile a document should default to, or null when the workspace has none yet. */
    @Transactional(readOnly = true)
    public TaxProfile defaultProfile(UUID tenantId) {
        return profiles.findFirstByTenantIdAndDefaultProfileTrue(tenantId).orElse(null);
    }

    @Transactional(readOnly = true)
    public TaxProfile require(UUID tenantId, UUID profileId) {
        return profiles.findByIdAndTenantId(profileId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Tax profile"));
    }

    /**
     * Tax configuration for one line.
     *
     * @param taxProfileId the catalog item's profile, or null when the item predates P1a
     * @param fallbackRate the legacy single rate stored on the item
     * @param documentMode the document's default mode, used when no profile is assigned
     */
    @Transactional(readOnly = true)
    public LineTax lineTax(UUID tenantId, UUID taxProfileId, BigDecimal fallbackRate,
                           MoneyCalculator.TaxMode documentMode) {
        if (taxProfileId == null) {
            BigDecimal rate = fallbackRate == null ? BigDecimal.ZERO : fallbackRate;
            return new LineTax(rate, List.of(), documentMode == null ? MoneyCalculator.TaxMode.EXCLUSIVE : documentMode,
                    null, null);
        }
        TaxProfile profile = require(tenantId, taxProfileId);
        if (!profile.isActive()) {
            throw ApiException.validation("taxProfileId", "tax profile " + profile.getName() + " is inactive");
        }
        TaxDefinition definition = profile.getTaxDefinitionId() == null ? null
                : definitions.findById(profile.getTaxDefinitionId()).orElse(null);
        List<TaxComponent> rows = profile.getTaxDefinitionId() == null ? List.of()
                : componentsOf(profile.getTaxDefinitionId());
        List<MoneyCalculator.Component> mapped = new ArrayList<>(rows.size());
        BigDecimal total = BigDecimal.ZERO;
        for (TaxComponent row : rows) {
            mapped.add(new MoneyCalculator.Component(row.getCode(), row.getName(), row.getRate(),
                    row.isCompound()));
            total = total.add(row.getRate());
        }
        MoneyCalculator.TaxMode mode = MoneyCalculator.TaxMode.parse(
                profile.getMode() != null ? profile.getMode()
                        : definition == null ? null : definition.getMode());
        return new LineTax(total, mapped, mode, profile.getId(), profile.getName());
    }

    /** Mode a document should record when it has no explicit per-line override. */
    @Transactional(readOnly = true)
    public MoneyCalculator.TaxMode documentMode(UUID tenantId) {
        TaxProfile profile = defaultProfile(tenantId);
        if (profile == null) {
            return MoneyCalculator.TaxMode.EXCLUSIVE;
        }
        if (profile.getMode() != null) {
            return MoneyCalculator.TaxMode.parse(profile.getMode());
        }
        return profile.getTaxDefinitionId() == null ? MoneyCalculator.TaxMode.EXCLUSIVE
                : definitions.findById(profile.getTaxDefinitionId())
                .map(definition -> MoneyCalculator.TaxMode.parse(definition.getMode()))
                .orElse(MoneyCalculator.TaxMode.EXCLUSIVE);
    }

    /** Currency precision, so a caller never assumes two decimal places. */
    @Transactional(readOnly = true)
    public int precision(String currencyCode) {
        Currency currency = referenceData.requireCurrency(currencyCode);
        return currency.getMinorUnits();
    }

    // -------------------------------------------------------------------- internals

    private void clearDefault(UUID tenantId) {
        for (TaxProfile existing : profiles.findAllByTenantIdOrderByDefaultProfileDescNameAsc(tenantId)) {
            if (existing.isDefaultProfile()) {
                existing.setDefaultProfile(false);
                profiles.save(existing);
            }
        }
    }

    private TaxDefinition requireDefinition(UUID id) {
        return definitions.findById(id)
                .orElseThrow(() -> ApiException.validation("taxDefinitionId", "unknown tax definition"));
    }

    private List<TaxComponent> componentsOf(UUID definitionId) {
        return components.findAllByTaxDefinitionIdOrderByPositionAsc(definitionId);
    }

    private Map<UUID, List<TaxComponent>> componentsByDefinition() {
        Map<UUID, List<TaxComponent>> byDefinition = new LinkedHashMap<>();
        for (TaxComponent component : components.findAll()) {
            byDefinition.computeIfAbsent(component.getTaxDefinitionId(), key -> new ArrayList<>())
                    .add(component);
        }
        byDefinition.values().forEach(list -> list.sort(
                java.util.Comparator.comparingInt(TaxComponent::getPosition)));
        return byDefinition;
    }

    private List<FinanceDtos.TaxComponentResponse> toResponses(List<TaxComponent> rows) {
        return rows.stream()
                .map(row -> new FinanceDtos.TaxComponentResponse(row.getCode(), row.getName(),
                        row.getRate(), row.isCompound(), row.getPosition()))
                .toList();
    }

    private FinanceDtos.TaxProfileResponse toResponse(TaxProfile profile, TaxDefinition definition,
                                                      List<TaxComponent> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (TaxComponent row : rows) {
            total = total.add(row.getRate());
        }
        String effectiveMode = profile.getMode() != null ? profile.getMode()
                : definition == null ? "EXCLUSIVE" : definition.getMode();
        return new FinanceDtos.TaxProfileResponse(profile.getId(), profile.getName(),
                profile.getLegalEntityId(), profile.getTaxDefinitionId(),
                definition == null ? null : definition.getCode(), profile.getMode(), effectiveMode,
                total, toResponses(rows), profile.isDefaultProfile(), profile.isActive(),
                profile.getVersion());
    }
}
