package com.operonix.catalog;

import com.operonix.common.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service_item")
@Getter
@Setter
@NoArgsConstructor
public class ServiceItem extends TenantEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 16)
    private ServiceUnit unit;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /** Deprecated single rate: kept so every pre-P1a item and API response stays valid. */
    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    /** Preferred path: a tenant tax profile, whose components replace {@code taxRate} when set. */
    @Column(name = "tax_profile_id")
    private UUID taxProfileId;

    /** Planned cost for this service item; used for margin calculations in quotes. */
    @Column(name = "cost", precision = 12, scale = 2, nullable = false, columnDefinition = "numeric default 0")
    private BigDecimal cost = BigDecimal.ZERO;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
