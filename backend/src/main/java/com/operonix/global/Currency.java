package com.operonix.global;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ISO 4217 currency with its real precision. {@code minorUnits} is what lets JPY render 0 decimals
 * without branching on the currency code anywhere in the application.
 */
@Entity
@Table(name = "currency")
@Getter
@NoArgsConstructor
public class Currency {

    @Id
    @Column(name = "code", nullable = false, length = 3)
    private String code;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "symbol", nullable = false, length = 8)
    private String symbol;

    @Column(name = "minor_units", nullable = false)
    private short minorUnits;

    @Column(name = "active", nullable = false)
    private boolean active;
}