package com.operonix.finance;

import com.operonix.common.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * The tenant's own named tax setup. Catalog items point at a profile, documents snapshot it, and the
 * calculator receives its components — so changing a rate here never rewrites an issued document.
 */
@Entity
@Table(name = "tax_profile")
@Getter
@Setter
@NoArgsConstructor
public class TaxProfile extends TenantEntity {

    @Column(name = "legal_entity_id")
    private UUID legalEntityId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "tax_definition_id")
    private UUID taxDefinitionId;

    /** Null means "inherit the definition's mode". */
    @Column(name = "mode", length = 10)
    private String mode;

    @Column(name = "is_default", nullable = false)
    private boolean defaultProfile;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
