package com.operonix.quoting;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.catalog.CatalogService;
import com.operonix.catalog.ServiceItem;
import com.operonix.common.api.PageRequests;
import com.operonix.common.api.PageResponse;
import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.common.money.MoneyCalculator;
import com.operonix.config.OperonixProperties;
import com.operonix.customer.Customer;
import com.operonix.customer.CustomerService;
import com.operonix.customer.ServiceLocation;
import com.operonix.customer.dto.CustomerDtos;
import com.operonix.document.DocumentNumberService;
import com.operonix.document.DocumentType;
import com.operonix.quoting.dto.QuoteDtos;
import com.operonix.security.CurrentUser;
import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QuoteService {

    private static final Map<String, String> SORTS = Map.of(
            "issueDate", "issueDate",
            "number", "number",
            "total", "total");

    private final QuoteRepository quotes;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final TenantService tenants;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final OperonixProperties properties;

    public QuoteService(QuoteRepository quotes, CustomerService customers, CatalogService catalog,
                        TenantService tenants, DocumentNumberService numbers, AuditService audit,
                        CurrentUser currentUser, OperonixProperties properties) {
        this.quotes = quotes;
        this.customers = customers;
        this.catalog = catalog;
        this.tenants = tenants;
        this.numbers = numbers;
        this.audit = audit;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @Transactional
    public PageResponse<QuoteDtos.Response> list(String status, UUID customerId, String q, int page, int size,
                                                 String sort) {
        UUID tenantId = currentUser.tenantId();
        QuoteStatus statusFilter = parseStatus(status);
        String pattern = PageRequests.containsPattern(q);
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, "issueDate,desc");
        Specification<Quote> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (statusFilter != null) {
                predicates.add(cb.equal(root.get("status"), statusFilter));
            }
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (pattern != null) {
                predicates.add(cb.like(cb.lower(root.get("number")), pattern));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<Quote> result = quotes.findAll(specification, pageable);
        String timezone = tenants.require(tenantId).getTimezone();
        Map<UUID, CustomerDtos.Summary> summaries = customers.summaries(tenantId,
                result.getContent().stream().map(Quote::getCustomerId).toList());
        result.getContent().forEach(quote -> materialiseExpiry(quote, timezone));
        return PageResponse.of(result).map(quote -> QuoteDtos.Response.from(quote,
                summaries.get(quote.getCustomerId()), null));
    }

    @Transactional
    public QuoteDtos.Response get(UUID quoteId) {
        UUID tenantId = currentUser.tenantId();
        Quote quote = requireDetailed(tenantId, quoteId);
        materialiseExpiry(quote, tenants.require(tenantId).getTimezone());
        return detail(quote);
    }

    @Transactional
    public QuoteDtos.Response create(QuoteDtos.CreateRequest request) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        Customer customer = customers.requireActive(tenantId, request.customerId());
        ServiceLocation location = resolveLocation(tenantId, customer, request.locationId());

        Quote quote = new Quote();
        quote.setTenantId(tenantId);
        quote.setCustomerId(customer.getId());
        quote.setLocationId(location == null ? null : location.getId());
        quote.setStatus(QuoteStatus.DRAFT);
        quote.setCurrency(tenant.getCurrency());
        quote.setNotes(trimToNull(request.notes()));
        quote.setCreatedBy(currentUser.id());

        LocalDate issueDate = request.issueDate() == null ? today(tenant) : request.issueDate();
        LocalDate validUntil = request.validUntil() == null
                ? issueDate.plusDays(properties.quoteValidityDays())
                : request.validUntil();
        if (validUntil.isBefore(issueDate)) {
            throw ApiException.validation("validUntil", "must not be before issueDate");
        }
        quote.setIssueDate(issueDate);
        quote.setValidUntil(validUntil);

        applyPricing(tenantId, quote, request.lines(), request.discountAmount());
        quote.setNumber(numbers.next(tenantId, DocumentType.QUOTE, issueDate.getYear()));
        quotes.save(quote);

        audit.record(AuditAction.QUOTE_CREATED, "Quote", quote.getId(),
                Map.of("number", quote.getNumber(), "total", quote.getTotal(), "customerId", customer.getId()));
        return detail(quote);
    }

    @Transactional
    public QuoteDtos.Response update(UUID quoteId, QuoteDtos.UpdateRequest request) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        Quote quote = requireDetailed(tenantId, quoteId);
        requireDraft(quote);

        Customer customer = customers.requireActive(tenantId, request.customerId());
        ServiceLocation location = resolveLocation(tenantId, customer, request.locationId());
        quote.setCustomerId(customer.getId());
        quote.setLocationId(location == null ? null : location.getId());
        quote.setNotes(trimToNull(request.notes()));

        LocalDate issueDate = request.issueDate() == null ? quote.getIssueDate() : request.issueDate();
        LocalDate validUntil = request.validUntil() == null ? quote.getValidUntil() : request.validUntil();
        if (validUntil.isBefore(issueDate)) {
            throw ApiException.validation("validUntil", "must not be before issueDate");
        }
        quote.setIssueDate(issueDate);
        quote.setValidUntil(validUntil);

        applyPricing(tenantId, quote, request.lines(), request.discountAmount());
        audit.record(AuditAction.QUOTE_UPDATED, "Quote", quote.getId(),
                Map.of("number", quote.getNumber(), "total", quote.getTotal()));
        return detail(quote);
    }

    @Transactional
    public void delete(UUID quoteId) {
        UUID tenantId = currentUser.tenantId();
        Quote quote = requireDetailed(tenantId, quoteId);
        requireDraft(quote);
        String number = quote.getNumber();
        quotes.delete(quote);
        audit.record(AuditAction.QUOTE_DELETED, "Quote", quoteId, Map.of("number", number));
    }

    @Transactional
    public QuoteDtos.Response send(UUID quoteId) {
        UUID tenantId = currentUser.tenantId();
        Quote quote = requireDetailed(tenantId, quoteId);
        if (quote.getStatus() != QuoteStatus.DRAFT) {
            throw invalidTransition(quote, "send");
        }
        if (quote.getLines().isEmpty() || quote.getTotal().signum() <= 0) {
            throw ApiException.validation("lines", "a quote needs at least one priced line before it can be sent");
        }
        quote.setStatus(QuoteStatus.SENT);
        audit.record(AuditAction.QUOTE_SENT, "Quote", quote.getId(),
                Map.of("number", quote.getNumber(), "total", quote.getTotal()));
        return detail(quote);
    }

    @Transactional
    public QuoteDtos.Response accept(UUID quoteId) {
        UUID tenantId = currentUser.tenantId();
        Quote quote = requireDetailed(tenantId, quoteId);
        if (quote.getStatus() == QuoteStatus.SENT
                && quote.getValidUntil().isBefore(today(tenants.require(tenantId)))) {
            quote.setStatus(QuoteStatus.EXPIRED);
            throw ApiException.of(ErrorCode.INVALID_STATE_TRANSITION, "quote " + quote.getNumber()
                    + " expired on " + quote.getValidUntil());
        }
        if (quote.getStatus() != QuoteStatus.SENT) {
            throw invalidTransition(quote, "accept");
        }
        quote.setStatus(QuoteStatus.ACCEPTED);
        audit.record(AuditAction.QUOTE_ACCEPTED, "Quote", quote.getId(),
                Map.of("number", quote.getNumber(), "total", quote.getTotal()));
        return detail(quote);
    }

    @Transactional
    public QuoteDtos.Response decline(UUID quoteId) {
        UUID tenantId = currentUser.tenantId();
        Quote quote = requireDetailed(tenantId, quoteId);
        if (quote.getStatus() != QuoteStatus.SENT) {
            throw invalidTransition(quote, "decline");
        }
        quote.setStatus(QuoteStatus.DECLINED);
        audit.record(AuditAction.QUOTE_DECLINED, "Quote", quote.getId(), Map.of("number", quote.getNumber()));
        return detail(quote);
    }

    /** Loads a quote that is allowed to become a job, rejecting expired or non-accepted quotes. */
    @Transactional
    public Quote requireConvertible(UUID quoteId) {
        UUID tenantId = currentUser.tenantId();
        Quote quote = requireDetailed(tenantId, quoteId);
        if (quote.getStatus() == QuoteStatus.SENT
                && quote.getValidUntil().isBefore(today(tenants.require(tenantId)))) {
            quote.setStatus(QuoteStatus.EXPIRED);
        }
        if (quote.getStatus() != QuoteStatus.ACCEPTED) {
            throw ApiException.of(ErrorCode.INVALID_STATE_TRANSITION,
                    "only an accepted quote can be converted to a job");
        }
        return quote;
    }

    @Transactional
    public void markConverted(UUID quoteId, UUID jobId) {
        Quote quote = requireDetailed(currentUser.tenantId(), quoteId);
        quote.setStatus(QuoteStatus.CONVERTED);
        quote.setConvertedJobId(jobId);
        audit.record(AuditAction.QUOTE_CONVERTED, "Quote", quote.getId(),
                Map.of("number", quote.getNumber(), "jobId", jobId));
    }

    /**
     * Immutable prices captured when a quote was accepted, used to invoice a converted quote at
     * its original values. The header discount travels with the lines so downstream documents
     * reproduce the total the customer agreed to.
     */
    @Transactional(readOnly = true)
    public QuoteSnapshot snapshot(UUID tenantId, UUID quoteId) {
        Quote quote = requireDetailed(tenantId, quoteId);
        List<LineSnapshot> lines = quote.getLines().stream()
                .map(line -> new LineSnapshot(line.getServiceItemId(), line.getDescription(),
                        line.getQuantity(), line.getUnitPrice(), line.getTaxRate()))
                .toList();
        return new QuoteSnapshot(lines, quote.getDiscountAmount());
    }

    @Transactional(readOnly = true)
    public Quote requireDetailed(UUID tenantId, UUID quoteId) {
        return quotes.findDetailed(quoteId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Quote"));
    }

    public record LineSnapshot(UUID serviceItemId, String description, BigDecimal quantity,
                               BigDecimal unitPrice, BigDecimal taxRate) {
    }

    public record QuoteSnapshot(List<LineSnapshot> lines, BigDecimal discountAmount) {
    }

    // ------------------------------------------------------------------ internals

    private void applyPricing(UUID tenantId, Quote quote, List<QuoteDtos.LineRequest> requested,
                              BigDecimal requestedDiscount) {
        List<QuoteDtos.LineRequest> lines = requested == null ? List.of() : requested;
        List<MoneyCalculator.Line> input = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            QuoteDtos.LineRequest line = lines.get(i);
            ServiceItem item = line.serviceItemId() == null
                    ? null
                    : catalog.require(tenantId, line.serviceItemId());

            String description = hasText(line.description())
                    ? line.description().trim()
                    : item == null ? null : item.getName();
            if (description == null) {
                throw ApiException.validation("lines[" + i + "].description",
                        "is required when no catalog service is selected");
            }
            BigDecimal unitPrice = line.unitPrice() != null
                    ? line.unitPrice()
                    : item == null ? null : item.getBasePrice();
            if (unitPrice == null) {
                throw ApiException.validation("lines[" + i + "].unitPrice",
                        "is required when no catalog service is selected");
            }
            BigDecimal taxRate = line.taxRate() != null
                    ? line.taxRate()
                    : item == null ? null : item.getTaxRate();
            if (taxRate == null) {
                throw ApiException.validation("lines[" + i + "].taxRate",
                        "is required when no catalog service is selected");
            }
            input.add(new MoneyCalculator.Line(line.serviceItemId(), description, line.quantity(),
                    unitPrice, taxRate));
        }

        MoneyCalculator.Totals totals = MoneyCalculator.calculate(input, requestedDiscount);
        if (requestedDiscount != null && requestedDiscount.compareTo(totals.subtotal()) > 0) {
            throw ApiException.validation("discountAmount", "cannot exceed the quote subtotal");
        }
        quote.setSubtotal(totals.subtotal());
        quote.setDiscountAmount(totals.discountAmount());
        quote.setTaxAmount(totals.taxAmount());
        quote.setTotal(totals.total());

        List<QuoteLine> existing = quote.getLines();
        List<MoneyCalculator.PricedLine> priced = totals.lines();
        for (int i = 0; i < priced.size(); i++) {
            MoneyCalculator.PricedLine source = priced.get(i);
            QuoteLine line = i < existing.size() ? existing.get(i) : null;
            if (line == null) {
                line = new QuoteLine();
                quote.addLine(line);
            }
            line.setServiceItemId(source.serviceItemId());
            line.setPosition(i);
            line.setDescription(source.description());
            line.setQuantity(source.quantity());
            line.setUnitPrice(source.unitPrice());
            line.setTaxRate(source.taxRate());
            line.setLineSubtotal(source.lineSubtotal());
            line.setLineTax(source.lineTax());
            line.setLineTotal(source.lineTotal());
        }
        while (existing.size() > priced.size()) {
            existing.remove(existing.size() - 1);
        }
    }

    private ServiceLocation resolveLocation(UUID tenantId, Customer customer, UUID locationId) {
        if (locationId != null) {
            return customers.requireLocation(tenantId, customer.getId(), locationId);
        }
        return customers.defaultLocation(tenantId, customer.getId());
    }

    private QuoteDtos.Response detail(Quote quote) {
        // Flush pending writes so generated createdAt/updatedAt values are present in the response.
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            quotes.flush();
        }
        UUID tenantId = quote.getTenantId();
        CustomerDtos.Summary customer = customers.summaries(tenantId, List.of(quote.getCustomerId()))
                .get(quote.getCustomerId());
        CustomerDtos.LocationResponse location = quote.getLocationId() == null ? null
                : customers.locationResponse(customers.requireLocation(tenantId, quote.getCustomerId(),
                quote.getLocationId()));
        return QuoteDtos.Response.from(quote, customer, location);
    }

    private void materialiseExpiry(Quote quote, String timezone) {
        if (quote.getStatus() == QuoteStatus.SENT
                && quote.getValidUntil().isBefore(LocalDate.now(ZoneId.of(timezone)))) {
            quote.setStatus(QuoteStatus.EXPIRED);
        }
    }

    private void requireDraft(Quote quote) {
        if (quote.getStatus() != QuoteStatus.DRAFT) {
            throw ApiException.immutable("quote " + quote.getNumber() + " is " + quote.getStatus()
                    + " and can no longer be edited");
        }
    }

    private ApiException invalidTransition(Quote quote, String action) {
        return ApiException.of(ErrorCode.INVALID_STATE_TRANSITION,
                "cannot " + action + " a quote in status " + quote.getStatus());
    }

    private LocalDate today(Tenant tenant) {
        return LocalDate.now(ZoneId.of(tenant.getTimezone()));
    }

    private static QuoteStatus parseStatus(String status) {
        String normalised = PageRequests.normaliseStatus(status);
        if (normalised == null || normalised.isBlank()) {
            return null;
        }
        try {
            return QuoteStatus.valueOf(normalised);
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("status", "unsupported quote status: " + status);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
