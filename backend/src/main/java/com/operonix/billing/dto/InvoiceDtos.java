package com.operonix.billing.dto;

import com.operonix.billing.Invoice;
import com.operonix.billing.InvoiceLine;
import com.operonix.billing.Payment;
import com.operonix.customer.dto.CustomerDtos;
import com.operonix.finance.FinanceDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    public record LineRequest(
            UUID serviceItemId,
            @Size(max = 300) String description,
            @NotNull @DecimalMin(value = "0.001") @Digits(integer = 6, fraction = 3) BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
            @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal taxRate
    ) {
    }

    public record CreateRequest(
            @NotNull UUID customerId,
            UUID jobId,
            LocalDate issueDate,
            LocalDate dueDate,
            @Size(max = 3) String currency,
            @Size(max = 2000) String notes,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount,
            @Valid List<LineRequest> lines
    ) {
    }

    public record UpdateRequest(
            @NotNull UUID customerId,
            LocalDate issueDate,
            LocalDate dueDate,
            @Size(max = 2000) String notes,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount,
            @Valid List<LineRequest> lines
    ) {
    }

    public record JobInvoiceRequest(
            LocalDate dueDate,
            @Size(max = 2000) String notes,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount,
            @Valid List<LineRequest> lines
    ) {
    }

    public record PaymentRequest(
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank String method,
            @Size(max = 120) String reference,
            Instant paidAt
    ) {
    }

    public record VoidRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    public record LineResponse(UUID id, UUID serviceItemId, int position, String description,
                               BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxRate,
                               BigDecimal lineSubtotal, BigDecimal lineTax, BigDecimal lineTotal,
                               List<FinanceDtos.TaxAmountResponse> taxComponents) {

        public static LineResponse from(InvoiceLine line) {
            return from(line, List.of());
        }

        public static LineResponse from(InvoiceLine line, List<FinanceDtos.TaxAmountResponse> taxComponents) {
            return new LineResponse(line.getId(), line.getServiceItemId(), line.getPosition(),
                    line.getDescription(), line.getQuantity(), line.getUnitPrice(), line.getTaxRate(),
                    line.getLineSubtotal(), line.getLineTax(), line.getLineTotal(), taxComponents);
        }
    }

    /**
     * @param reversedAt set once the payment has been reversed; the payment stays on the invoice
     *                   timeline and is excluded from the balance and from money reports.
     */
    public record PaymentResponse(UUID id, BigDecimal amount, String method, String reference,
                                  Instant paidAt, UUID recordedBy, Instant createdAt,
                                  Instant reversedAt, UUID reversedBy) {

        public static PaymentResponse from(Payment payment) {
            return new PaymentResponse(payment.getId(), payment.getAmount(), payment.getMethod().name(),
                    payment.getReference(), payment.getPaidAt(), payment.getRecordedBy(),
                    payment.getCreatedAt(), payment.getReversedAt(), payment.getReversedBy());
        }
    }

    public record Response(UUID id, String number, String status, boolean overdue,
                           CustomerDtos.Summary customer, UUID jobId, LocalDate issueDate, LocalDate dueDate,
                           String currency, String notes, BigDecimal subtotal, BigDecimal discountAmount,
                           BigDecimal taxAmount, BigDecimal total, BigDecimal amountPaid, BigDecimal balance,
                           Instant paidAt, String voidReason,
                           UUID legalEntityId, String taxProfileName, String taxMode, String taxNumber,
                           boolean reverseCharge, BigDecimal fxRate, String baseCurrency, BigDecimal baseTotal,
                           BigDecimal baseAmountPaid,
                           List<FinanceDtos.TaxAmountResponse> taxComponents,
                           List<LineResponse> lines, List<PaymentResponse> payments, long version,
                           Instant createdAt, Instant updatedAt) {

        public static Response from(Invoice invoice, CustomerDtos.Summary customer, List<Payment> payments,
                                    boolean overdue) {
            return from(invoice, customer, payments, overdue, Map.of(), List.of());
        }

        public static Response from(Invoice invoice, CustomerDtos.Summary customer, List<Payment> payments,
                                    boolean overdue,
                                    Map<UUID, List<FinanceDtos.TaxAmountResponse>> lineComponents,
                                    List<FinanceDtos.TaxAmountResponse> documentComponents) {
            List<LineResponse> lines = invoice.getLines().stream()
                    .map(line -> LineResponse.from(line, lineComponents.getOrDefault(line.getId(), List.of())))
                    .toList();
            return new Response(invoice.getId(), invoice.getNumber(), invoice.getStatus().name(), overdue,
                    customer, invoice.getJobId(), invoice.getIssueDate(), invoice.getDueDate(),
                    invoice.getCurrency(), invoice.getNotes(), invoice.getSubtotal(),
                    invoice.getDiscountAmount(), invoice.getTaxAmount(), invoice.getTotal(),
                    invoice.getAmountPaid(), invoice.balance(), invoice.getPaidAt(), invoice.getVoidReason(),
                    invoice.getLegalEntityId(), invoice.getTaxProfileName(), invoice.getTaxMode(),
                    invoice.getTaxNumber(), invoice.isReverseCharge(), invoice.getFxRate(),
                    invoice.getBaseCurrency(), invoice.getBaseTotal(), invoice.getBaseAmountPaid(),
                    documentComponents, lines,
                    payments.stream().map(PaymentResponse::from).toList(),
                    invoice.getVersion(), invoice.getCreatedAt(), invoice.getUpdatedAt());
        }
    }
}
