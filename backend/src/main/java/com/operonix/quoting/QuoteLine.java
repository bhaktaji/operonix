package com.operonix.quoting;

import com.operonix.common.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import com.operonix.common.money.MoneyCalculator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "quote_line")
@Getter
@Setter
@NoArgsConstructor
public class QuoteLine extends TenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    @Column(name = "service_item_id")
    private UUID serviceItemId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "line_subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineSubtotal;

    @Column(name = "line_tax", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTax;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    /**
     * Printed tax rows produced by the pricing pass. Persisted to {@code line_tax_component} once the
     * line has an identifier, so the entity itself needs no extra column.
     */
    @Transient
    private List<MoneyCalculator.TaxComponentAmount> taxComponents = new java.util.ArrayList<>();
}
