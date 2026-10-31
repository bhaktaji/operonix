package com.operonix.global;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Platform reference data: an activated market. Configuration only, never tenant-owned.
 * See docs/product-evolution/GLOBALIZATION_AND_LOCALIZATION.md §8.
 */
@Entity
@Table(name = "country")
@Getter
@NoArgsConstructor
public class Country {

    @Id
    @Column(name = "code", nullable = false, length = 2)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Column(name = "default_locale", nullable = false, length = 16)
    private String defaultLocale;

    @Column(name = "calling_code", nullable = false, length = 8)
    private String callingCode;

    @Column(name = "postal_code_regex", length = 160)
    private String postalCodeRegex;

    @Column(name = "tax_label", length = 24)
    private String taxLabel;

    @Column(name = "active", nullable = false)
    private boolean active;
}