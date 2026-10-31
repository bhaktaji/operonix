package com.operonix.identity.dto;

import com.operonix.common.validation.StrongPassword;
import com.operonix.identity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @StrongPassword String password,
        @NotNull Role role
) {
}
