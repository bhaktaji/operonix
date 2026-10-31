package com.operonix.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateWorkspaceRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotBlank @Size(max = 3) String currency,
        @NotBlank @Size(max = 64) String timezone
) {
}
