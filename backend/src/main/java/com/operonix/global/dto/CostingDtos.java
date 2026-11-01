package com.operonix.global.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CostingDtos {

    private CostingDtos() {
    }

    // ------------------------------------------------------------------ vendors

    public record VendorRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 120) String contactName,
            @Email @Size(max = 254) String email,
            @Size(max = 64) String phone,
            @Size(max = 32) String taxNumber,
            @DecimalMin("0.00") BigDecimal defaultRate,
            @Size(max = 30) String paymentTerms,
            Boolean active
    ) {
    }

    public record VendorResponse(UUID id, String name, String contactName, String email, String phone,
                                 String taxNumber, BigDecimal defaultRate, String paymentTerms,
                                 boolean active, long version) {
    }

    // ------------------------------------------------------------------ work logs

    public record WorkLogRequest(
            /** Required for managers logging time on someone else's behalf; ignored for self-logging. */
            UUID userId,
            @NotNull Instant startAt,
            Instant endAt,
            @Size(max = 2000) String notes
    ) {
    }

    public record WorkLogResponse(UUID id, UUID jobId, UUID userId, String userName, Instant startAt,
                                  Instant endAt, Long minutes, boolean manual, String notes,
                                  BigDecimal cost, long version) {
    }

    // ------------------------------------------------------------------ expenses

    public record ExpenseRequest(
            @NotBlank @Size(max = 360) String description,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @Size(min = 3, max = 3) String currency,
            @NotNull LocalDate expenseDate,
            @NotBlank String category,
            UUID vendorId,
            UUID jobId,
            @Size(max = 120) String receiptReference,
            @Size(max = 2000) String notes
    ) {
    }

    public record ExpenseResponse(UUID id, UUID jobId, String jobNumber, UUID vendorId, String vendorName,
                                  String description, BigDecimal amount, String currency,
                                  BigDecimal fxRate, BigDecimal workspaceAmount,
                                  String receiptReference, LocalDate expenseDate, String category,
                                  String notes, long version) {
    }

    // ------------------------------------------------------------------ profitability

    /**
     * Job-level profitability. {@code status} is {@code INCOMPLETE} whenever a required input is
     * missing (unpriced labour, an open time log, a job with no invoice, or revenue that cannot be
     * expressed in the workspace currency). In that case the affected amounts are null rather than
     * zero, so "unknown" is never rendered as "no profit".
     */
    public record ProfitabilityResponse(
            UUID jobId,
            String currency,
            String status,
            List<String> reasons,
            BigDecimal revenue,
            BigDecimal laborCost,
            BigDecimal materialCost,
            BigDecimal equipmentCost,
            BigDecimal subcontractorCost,
            BigDecimal otherCost,
            BigDecimal totalCost,
            BigDecimal directProfit,
            BigDecimal marginPercent,
            BigDecimal plannedRevenue,
            BigDecimal plannedCost,
            BigDecimal plannedMargin,
            Long actualMinutes,
            Long scheduledMinutes,
            Instant computedAt,
            String scopeNote
    ) {
    }

    public record JobCostResponse(
            UUID jobId,
            String currency,
            BigDecimal laborCost,
            BigDecimal materialCost,
            BigDecimal equipmentCost,
            BigDecimal subcontractorCost,
            BigDecimal otherCost,
            BigDecimal totalCost,
            Instant computedAt,
            long version
    ) {
    }

    /** Everything the job cost panel needs in one round trip. */
    public record JobCostingView(
            JobCostResponse cost,
            ProfitabilityResponse profitability,
            List<WorkLogResponse> workLogs,
            List<ExpenseResponse> expenses,
            List<VendorResponse> vendors
    ) {
    }

    // ------------------------------------------------------------------ reporting inputs

    public record StaffCostRequest(@NotNull @DecimalMin("0.00") BigDecimal hourlyCost) {
    }

    public record ServiceCostRequest(@NotNull @DecimalMin("0.00") BigDecimal cost) {
    }
}
