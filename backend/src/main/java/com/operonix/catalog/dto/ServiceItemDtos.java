package com.operonix.catalog.dto;

import com.operonix.catalog.ServiceItem;
import com.operonix.catalog.ServiceUnit;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ServiceItemDtos {

    private ServiceItemDtos() {
    }

    public record Request(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull ServiceUnit unit,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal basePrice,
            @Min(15) @Max(1440) int durationMinutes,
            @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal taxRate,
            UUID taxProfileId,
            boolean active
    ) {
    }

    public record Response(UUID id, String name, String description, String unit, BigDecimal basePrice,
                           int durationMinutes, BigDecimal taxRate, UUID taxProfileId, boolean active,
                           long version, Instant createdAt, Instant updatedAt) {

        public static Response from(ServiceItem item) {
            return new Response(item.getId(), item.getName(), item.getDescription(), item.getUnit().name(),
                    item.getBasePrice(), item.getDurationMinutes(), item.getTaxRate(),
                    item.getTaxProfileId(), item.isActive(), item.getVersion(), item.getCreatedAt(),
                    item.getUpdatedAt());
        }
    }
}
