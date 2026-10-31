package com.operonix.billing;

import com.operonix.common.persistence.TenantEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoice")
@Getter
@Setter
@NoArgsConstructor
public class Invoice extends TenantEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "number", nullable = false, length = 20)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Seller and tax snapshot (P1a): everything needed to reprint this invoice as issued. */
    @Column(name = "legal_entity_id")
    private UUID legalEntityId;

    @Column(name = "tax_profile_id")
    private UUID taxProfileId;

    @Column(name = "tax_profile_name", length = 120)
    private String taxProfileName;

    @Column(name = "tax_mode", nullable = false, length = 10)
    private String taxMode = "EXCLUSIVE";

    @Column(name = "tax_number", length = 32)
    private String taxNumber;

    @Column(name = "reverse_charge", nullable = false)
    private boolean reverseCharge;

    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal fxRate = BigDecimal.ONE;

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    @Column(name = "base_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseTotal = BigDecimal.ZERO;

    @Column(name = "base_amount_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseAmountPaid = BigDecimal.ZERO;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "amount_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<InvoiceLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("paidAt ASC")
    private List<Payment> payments = new ArrayList<>();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public void addLine(InvoiceLine line) {
        line.setInvoice(this);
        line.setTenantId(getTenantId());
        lines.add(line);
    }

    public void addPayment(Payment payment) {
        payment.setInvoice(this);
        payment.setTenantId(getTenantId());
        payments.add(payment);
    }

    public BigDecimal balance() {
        return total.subtract(amountPaid);
    }
}
