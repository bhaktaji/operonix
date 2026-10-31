package com.operonix.identity.dto;

import com.operonix.common.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Workspace creation and owner sign-up in one call. Only the country is needed to derive sensible
 * defaults; every other regional field is optional so onboarding never demands legal information
 * before the workspace can be explored.
 */
public record RegisterRequest(
        @NotBlank @Size(min = 2, max = 120) String orgName,
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @StrongPassword String password,
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO 3166-1 alpha-2 code") String countryCode,
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be an ISO 4217 code") String currency,
        @Size(max = 64) String timezone,
        @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "must be a BCP-47 tag such as en-GB") String locale,
        @Size(max = 40) String industryTemplate,
        @Size(max = 160) String legalName
) {
}