package com.operonix.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The workspace settings form is whole-object: every field is required so a partial save cannot
 * silently reset a value the user never saw.
 */
public record UpdateWorkspaceRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO 3166-1 alpha-2 code") String countryCode,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotBlank @Size(max = 64) String timezone,
        @NotBlank @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "must be a BCP-47 tag such as en-GB") String locale,
        @NotBlank @Size(max = 40) String industryTemplate,
        @Size(max = 160) String legalName,
        @Size(max = 32) String taxNumber
) {
}