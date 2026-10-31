package com.operonix.quoting.dto;

import com.operonix.customer.dto.CustomerDtos;
import com.operonix.finance.FinanceDtos;
import com.operonix.quoting.Quote;
import com.operonix.quoting.QuoteLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuoteDtos {

    private QuoteDtos() {
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
            UUID locationId,
            LocalDate issueDate,
            LocalDate validUntil,
            @Size(max = 3) String currency,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount,
            @Size(max = 2000) String notes,
            @Valid List<LineRequest> lines
    ) {
    }

    public record UpdateRequest(
            @NotNull UUID customerId,
            UUID locationId,
            LocalDate issueDate,
            LocalDate validUntil,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount,
            @Size(max = 2000) String notes,
            @Valid List<LineRequest> lines
    ) {
    }

    public record ConvertRequest(
            @NotNull Instant scheduledStart,
            @NotNull Instant scheduledEnd,
            @NotEmpty List<UUID> technicianIds,
            @Size(max = 160) String title
    ) {
    }

    public record LineResponse(UUID id, UUID serviceItemId, int position, String description,
                               BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxRate,
                               BigDecimal lineSubtotal, BigDecimal lineTax, BigDecimal lineTotal,
                               List<FinanceDtos.TaxAmountResponse> taxComponents) {

        public static LineResponse from(QuoteLine line) {
            return from(line, List.of());
        }

        public static LineResponse from(QuoteLine line, List<FinanceDtos.TaxAmountResponse> taxComponents) {
            return new LineResponse(line.getId(), line.getServiceItemId(), line.getPosition(),
                    line.getDescription(), line.getQuantity(), line.getUnitPrice(), line.getTaxRate(),
                    line.getLineSubtotal(), line.getLineTax(), line.getLineTotal(), taxComponents);
        }
    }

    public record Response(UUID id, String number, String status, CustomerDtos.Summary customer,
                           CustomerDtos.LocationResponse location, LocalDate issueDate, LocalDate validUntil,
                           String currency, String notes, BigDecimal subtotal, BigDecimal discountAmount,
                           BigDecimal taxAmount, BigDecimal total, UUID convertedJobId,
                           UUID legalEntityId, String taxProfileName, String taxMode, BigDecimal fxRate,
                           String baseCurrency, BigDecimal baseTotal,
                           List<FinanceDtos.TaxAmountResponse> taxComponents,
                           List<LineResponse> lines, long version, Instant createdAt, Instant updatedAt) {

        public static Response from(Quote quote, CustomerDtos.Summary customer,
                                    CustomerDtos.LocationResponse location) {
            return from(quote, customer, location, Map.of(), List.of());
        }

        public static Response from(Quote quote, CustomerDtos.Summary customer,
                                    CustomerDtos.LocationResponse location,
                                    Map<UUID, List<FinanceDtos.TaxAmountResponse>> lineComponents,
                                    List<FinanceDtos.TaxAmountResponse> documentComponents) {
            List<LineResponse> lines = quote.getLines().stream()
                    .map(line -> LineResponse.from(line,
                            lineComponents.getOrDefault(line.getId(), List.of())))
                    .toList();
            return new Response(quote.getId(), quote.getNumber(), quote.getStatus().name(), customer, location,
                    quote.getIssueDate(), quote.getValidUntil(), quote.getCurrency(), quote.getNotes(),
                    quote.getSubtotal(), quote.getDiscountAmount(), quote.getTaxAmount(), quote.getTotal(),
                    quote.getConvertedJobId(), quote.getLegalEntityId(), quote.getTaxProfileName(),
                    quote.getTaxMode(), quote.getFxRate(), quote.getBaseCurrency(), quote.getBaseTotal(),
                    documentComponents, lines, quote.getVersion(), quote.getCreatedAt(), quote.getUpdatedAt());
        }
    }
}
