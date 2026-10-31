package com.operonix.customer;

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

import java.time.Instant;

@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
public class Customer extends TenantEntity {

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "contact_name", length = 120)
    private String contactName;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    @Column(name = "billing_line1", length = 160)
    private String billingLine1;

    @Column(name = "billing_line2", length = 160)
    private String billingLine2;

    @Column(name = "billing_city", length = 80)
    private String billingCity;

    @Column(name = "billing_region", length = 80)
    private String billingRegion;

    @Column(name = "billing_postal_code", length = 16)
    private String billingPostalCode;

    @Column(name = "billing_country", length = 2)
    private String billingCountry;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
