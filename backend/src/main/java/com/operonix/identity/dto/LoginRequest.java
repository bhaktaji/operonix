package com.operonix.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code workspace} is the tenant slug. It is optional for the common case (one membership per email)
 * and required only when the same email belongs to several workspaces.
 */
public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 72) String password,
        @Size(max = 63) String workspace
) {
}