package com.operonix.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** One row per workspace (ERD §3.18): formatting and fiscal preferences that are not tenant identity. */
@Entity
@Table(name = "tenant_settings")
@Getter
@Setter
@NoArgsConstructor
public class TenantFinancialSettings {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    @Column(name = "week_start", nullable = false)
    private short weekStart = 1;

    @Column(name = "fiscal_year_start_month", nullable = false)
    private short fiscalYearStartMonth = 1;

    @Column(name = "default_tax_profile_id")
    private UUID defaultTaxProfileId;

    @Column(name = "default_legal_entity_id")
    private UUID defaultLegalEntityId;

    @Column(name = "date_format", nullable = false, length = 20)
    private String dateFormat = "MEDIUM";

    @Column(name = "number_format", nullable = false, length = 16)
    private String numberFormat = "STANDARD";

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
