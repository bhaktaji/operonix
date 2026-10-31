package com.operonix.global;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public reference data: onboarding needs countries, currencies and industry templates *before* the
 * caller has a session. Nothing here is tenant-specific, so it is safe to serve unauthenticated.
 */
@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceDataController {

    private final ReferenceDataService referenceDataService;

    public ReferenceDataController(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GetMapping
    public ReferenceDtos.ReferenceData all() {
        return referenceDataService.all();
    }
}