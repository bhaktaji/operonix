package com.operonix.identity.dto;

import com.operonix.identity.Role;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(min = 2, max = 120) String fullName,
        Role role,
        Boolean active
) {
}
