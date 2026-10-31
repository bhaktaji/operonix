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

/**
 * One printed tax row for one document line (ERD §3.17). Rows are written when a document is priced
 * and never updated afterwards, so a later rate change cannot rewrite an issued document.
 */
@Entity
@Table(name = "line_tax_component")
@Getter
@Setter
@NoArgsConstructor
public class LineTaxComponent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "line_type", nullable = false, length = 10)
    private String lineType;

    @Column(name = "line_id", nullable = false, updatable = false)
    private UUID lineId;

    @Column(name = "tax_component_id")
    private UUID taxComponentId;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "base", nullable = false, precision = 12, scale = 2)
    private BigDecimal base;

    @Column(name = "rate", nullable = false, precision = 7, scale = 5)
    private BigDecimal rate;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "position", nullable = false)
    private int position;
}
