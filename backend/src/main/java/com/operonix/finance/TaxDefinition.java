package com.operonix.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A jurisdictional tax rule ("India GST 18%", "UK VAT 20%"). Platform-owned reference data: business
 * logic never reads it directly, only through a tenant's {@link TaxProfile}.
 */
@Entity
@Table(name = "tax_definition")
@Getter
@Setter
@NoArgsConstructor
public class TaxDefinition {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode;

    @Column(name = "rounding", nullable = false, length = 10)
    private String rounding;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
