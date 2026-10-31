package com.operonix.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One printed rate inside a definition: CGST 9%, SGST 9%, VAT 20%, state sales tax 8.25%. */
@Entity
@Table(name = "tax_component")
@Getter
@Setter
@NoArgsConstructor
public class TaxComponent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tax_definition_id", nullable = false)
    private UUID taxDefinitionId;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "rate", nullable = false, precision = 7, scale = 5)
    private BigDecimal rate;

    @Column(name = "compound", nullable = false)
    private boolean compound;

    @Column(name = "position", nullable = false)
    private int position;
}
