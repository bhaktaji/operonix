package com.operonix.finance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class OrganisationController {

    private final OrganisationService organisation;

    public OrganisationController(OrganisationService organisation) {
        this.organisation = organisation;
    }

    @GetMapping("/legal-entities")
    public List<FinanceDtos.LegalEntityResponse> entities() {
        return organisation.listEntities();
    }

    @PostMapping("/legal-entities")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public FinanceDtos.LegalEntityResponse createEntity(
            @Valid @RequestBody FinanceDtos.LegalEntityRequest request) {
        return organisation.createEntity(request);
    }

    @PutMapping("/legal-entities/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public FinanceDtos.LegalEntityResponse updateEntity(@PathVariable UUID id,
                                                        @Valid @RequestBody FinanceDtos.LegalEntityRequest request) {
        return organisation.updateEntity(id, request);
    }

    @DeleteMapping("/legal-entities/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntity(@PathVariable UUID id) {
        organisation.deleteEntity(id);
    }

    @GetMapping("/business-locations")
    public List<FinanceDtos.LocationResponse> locations() {
        return organisation.listLocations();
    }

    @PostMapping("/business-locations")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public FinanceDtos.LocationResponse createLocation(@Valid @RequestBody FinanceDtos.LocationRequest request) {
        return organisation.createLocation(request);
    }

    @PutMapping("/business-locations/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public FinanceDtos.LocationResponse updateLocation(@PathVariable UUID id,
                                                       @Valid @RequestBody FinanceDtos.LocationRequest request) {
        return organisation.updateLocation(id, request);
    }

    @DeleteMapping("/business-locations/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLocation(@PathVariable UUID id) {
        organisation.deleteLocation(id);
    }

    /** Locale, fiscal year and defaults for new documents. Readable by every member. */
    @GetMapping("/workspace/financial-settings")
    public FinanceDtos.SettingsResponse settings() {
        return organisation.getSettings();
    }

    @PutMapping("/workspace/financial-settings")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public FinanceDtos.SettingsResponse updateSettings(
            @Valid @RequestBody FinanceDtos.SettingsRequest request) {
        return organisation.updateSettings(request);
    }
}
