package com.operonix.identity.dto;

import com.operonix.identity.Role;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param hourlyCost labour cost per hour in the workspace currency. Optional; leaving it at zero
 *                   makes any job the person worked on report profitability as incomplete rather
 *                   than assuming a free technician.
 */
public record UpdateUserRequest(
        @Size(min = 2, max = 120) String fullName,
        Role role,
        Boolean active,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal hourlyCost
) {
}
