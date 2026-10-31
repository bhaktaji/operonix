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

/**
 * The party that legally sells (ERD §3.16). One workspace can have several — a group with two
 * registered companies — and every invoice names the entity that issued it.
 */
@Entity
@Table(name = "legal_entity")
@Getter
@Setter
@NoArgsConstructor
public class LegalEntity extends TenantEntity {

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "tax_number", length = 32)
    private String taxNumber;

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    /** Default tax mode for documents issued by this entity. */
    @Column(name = "tax_mode", nullable = false, length = 10)
    private String taxMode = "EXCLUSIVE";

    /** Country-specific label for {@code taxNumber}, e.g. GSTIN, EIN, VAT number. */
    @Column(name = "tax_number_label", length = 40)
    private String taxNumberLabel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address")
    private String address;

    @Column(name = "is_default", nullable = false)
    private boolean defaultEntity;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Convenience for services that only need to stamp documents. */
    public UUID id() {
        return getId();
    }
}
