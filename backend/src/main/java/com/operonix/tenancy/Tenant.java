package com.operonix.tenancy;

import com.operonix.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
public class Tenant extends BaseEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, length = 63, unique = true)
    private String slug;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    /** ISO 3166-1 alpha-2 market the workspace is registered in. Drives defaults, never behaviour. */
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    /** BCP-47 language tag used for formatting and, later, translated copy. */
    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    @Column(name = "industry_template", nullable = false, length = 40)
    private String industryTemplate;

    /** Data-residency region (§5 of the security impact analysis). Single active region in P0. */
    @Column(name = "home_region", nullable = false, length = 32)
    private String homeRegion;

    @Column(name = "legal_name", length = 160)
    private String legalName;

    /** Optional: not every business has a tax identifier, so onboarding never requires it. */
    @Column(name = "tax_number", length = 32)
    private String taxNumber;

    @Column(name = "onboarded_at")
    private Instant onboardedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
