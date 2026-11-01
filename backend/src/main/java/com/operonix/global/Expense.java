package com.operonix.global;

import com.operonix.common.persistence.TenantEntity;
import com.operonix.scheduling.Job;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A recorded cost. It may be attached to a job (direct job cost) or left unattached, in which case it
 * is a business overhead and is excluded from job profitability.
 *
 * <p>{@link #amount} stays exactly as entered, in the currency it was entered in. The converted
 * {@link #workspaceAmount} is frozen at {@link #fxRate} on the expense date, so later exchange-rate
 * corrections never rewrite a historical cost.
 */
@Entity
@Table(name = "expense")
@Getter
@Setter
@NoArgsConstructor
public class Expense extends TenantEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    @Column(name = "description", nullable = false, length = 360)
    private String description;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal fxRate = BigDecimal.ONE;

    @Column(name = "workspace_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal workspaceAmount = BigDecimal.ZERO;

    @Column(name = "receipt_reference", length = 120)
    private String receiptReference;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private ExpenseCategory category = ExpenseCategory.OTHER;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
