package com.operonix.global;

import com.operonix.common.persistence.TenantEntity;
import com.operonix.scheduling.Job;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Materialized cost roll-up for one job, in the workspace currency.
 *
 * <p>It is always rebuilt from source rows (work logs and expenses), never incremented, so repeated
 * recomputation is idempotent and a duplicated domain event cannot double-count a cost.
 */
@Entity
@Table(name = "job_cost")
@Getter
@Setter
@NoArgsConstructor
public class JobCost extends TenantEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(name = "labor_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal laborCost = BigDecimal.ZERO;

    @Column(name = "material_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal materialCost = BigDecimal.ZERO;

    @Column(name = "equipment_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal equipmentCost = BigDecimal.ZERO;

    @Column(name = "subcontractor_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal subcontractorCost = BigDecimal.ZERO;

    @Column(name = "other_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal otherCost = BigDecimal.ZERO;

    @Column(name = "total_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Instant.now();

    @Column(name = "computed_by")
    private UUID computedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public JobCost(UUID tenantId, Job job, String currency) {
        setTenantId(tenantId);
        this.job = job;
        this.currency = currency;
    }

    /** Recomputes {@link #totalCost} from the category columns. */
    public void recomputeTotal() {
        this.totalCost = value(laborCost).add(value(materialCost)).add(value(equipmentCost))
                .add(value(subcontractorCost)).add(value(otherCost));
    }

    private static BigDecimal value(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
