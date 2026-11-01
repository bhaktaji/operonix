package com.operonix.global;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A supplier, subcontractor or materials vendor. Expenses reference a vendor so spend can be
 * attributed back to who it was paid to.
 */
@Entity
@Table(name = "vendor")
@Getter
@Setter
@NoArgsConstructor
public class Vendor extends TenantEntity {

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "contact_name", length = 120)
    private String contactName;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "phone", length = 64)
    private String phone;

    /** Free-form address kept as JSON so international address shapes need no schema change. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address")
    private String address;

    @Column(name = "tax_number", length = 32)
    private String taxNumber;

    /** Default charge rate in the workspace currency (informational until applied to an expense). */
    @Column(name = "default_rate", nullable = false, precision = 12, scale = 2)
    private BigDecimal defaultRate = BigDecimal.ZERO;

    @Column(name = "payment_terms", nullable = false, length = 30)
    private String paymentTerms = "NET_30";

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Vendor(UUID tenantId, String name) {
        setTenantId(tenantId);
        this.name = name;
    }
}
