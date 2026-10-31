package com.operonix.finance;

import com.operonix.common.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** An operational site of the business (depot, branch, workshop) — not a customer address. */
@Entity
@Table(name = "business_location")
@Getter
@Setter
@NoArgsConstructor
public class BusinessLocation extends TenantEntity {

    @Column(name = "legal_entity_id", nullable = false)
    private UUID legalEntityId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address")
    private String address;

    @Column(name = "is_default", nullable = false)
    private boolean defaultLocation;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
