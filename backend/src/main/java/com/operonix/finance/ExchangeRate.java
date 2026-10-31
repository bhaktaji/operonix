package com.operonix.finance;

import com.operonix.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A dated conversion rate. Documents snapshot the rate they used, so reporting never re-converts
 * history when a newer rate is inserted.
 */
@Entity
@Table(name = "exchange_rate")
@Getter
@Setter
@NoArgsConstructor
public class ExchangeRate extends BaseEntity {

    @Column(name = "base", nullable = false, length = 3)
    private String base;

    @Column(name = "quote", nullable = false, length = 3)
    private String quote;

    /** Quote units per one base unit. */
    @Column(name = "rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Column(name = "source", nullable = false, length = 32)
    private String source = "MANUAL";
}
