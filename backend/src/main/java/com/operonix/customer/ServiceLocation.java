package com.operonix.customer;

import com.operonix.common.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service_location")
@Getter
@Setter
@NoArgsConstructor
public class ServiceLocation extends TenantEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(name = "line1", nullable = false, length = 160)
    private String line1;

    @Column(name = "line2", length = 160)
    private String line2;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "region", length = 80)
    private String region;

    @Column(name = "postal_code", nullable = false, length = 16)
    private String postalCode;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "access_notes", length = 1000)
    private String accessNotes;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
