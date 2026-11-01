package com.operonix.billing;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.billing.dto.InvoiceDtos;
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
import com.operonix.customer.dto.CustomerDtos;
import com.operonix.document.DocumentNumberService;
import com.operonix.document.DocumentType;
import com.operonix.finance.FinanceDtos;
import com.operonix.finance.FinanceService;
import com.operonix.finance.TaxService;
import com.operonix.finance.TaxSnapshotWriter;
import com.operonix.quoting.QuoteService;
import com.operonix.scheduling.JobService;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InvoiceService {

    private static final Set<InvoiceStatus> OPEN_STATUSES = Set.of(InvoiceStatus.SENT,
            InvoiceStatus.PARTIALLY_PAID);
    private static final Duration PAID_AT_TOLERANCE = Duration.ofMinutes(5);
    private static final Map<String, String> SORTS = Map.of(
            "issueDate", "issueDate",
            "dueDate", "dueDate",
            "number", "number",
            "total", "total");

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final QuoteService quotes;
    private final JobService jobs;
    private final TenantService tenants;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final OperonixProperties properties;
    private final FinanceService finance;
    private final TaxSnapshotWriter taxSnapshots;

    public InvoiceService(InvoiceRepository invoices, PaymentRepository payments, CustomerService customers,
                          CatalogService catalog, QuoteService quotes, JobService jobs, TenantService tenants,
                          DocumentNumberService numbers, AuditService audit, CurrentUser currentUser,
                          OperonixProperties properties, FinanceService finance, TaxSnapshotWriter taxSnapshots) {
        this.invoices = invoices;
        this.payments = payments;
        this.customers = customers;
        this.catalog = catalog;
        this.quotes = quotes;
        this.jobs = jobs;
        this.tenants = tenants;
        this.numbers = numbers;
        this.audit = audit;
        this.currentUser = currentUser;
        this.properties = properties;
        this.finance = finance;
        this.taxSnapshots = taxSnapshots;
    }

    // ------------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResponse<InvoiceDtos.Response> list(String status, UUID customerId, Boolean overdue, String q,
                                                   int page, int size, String sort) {
        UUID tenantId = currentUser.tenantId();
        InvoiceStatus statusFilter = parseStatus(status);
        String pattern = PageRequests.containsPattern(q);
        LocalDate today = today(tenantId);
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, "issueDate,desc");

        Specification<Invoice> specification = (root, query, cb) -> {
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
            if (overdue != null) {
                Predicate late = cb.and(
                        root.get("status").in(OPEN_STATUSES),
                        cb.lessThan(root.get("dueDate"), today),
                        cb.greaterThan(root.<BigDecimal>get("total"), root.<BigDecimal>get("amountPaid")));
                predicates.add(overdue ? late : cb.not(late));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<Invoice> result = invoices.findAll(specification, pageable);
        List<InvoiceDtos.Response> content = toResponses(result.getContent());
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public InvoiceDtos.Response get(UUID invoiceId) {
        Invoice invoice = requireDetailed(invoiceId);
        return toResponses(List.of(invoice)).getFirst();
    }

    // ----------------------------------------------------------------------- writes

    @Transactional
    public InvoiceDtos.Response create(InvoiceDtos.CreateRequest request) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        Customer customer = customers.require(request.customerId());

        JobService.InvoiceContext context = null;
        if (request.jobId() != null) {
            context = jobs.requireInvoiceable(request.jobId());
            if (!context.customerId().equals(customer.getId())) {
                throw ApiException.validation("jobId", "does not belong to the selected customer");
            }
            requireNoActiveInvoice(tenantId, request.jobId());
        }
        ResolvedLines resolved = resolveLines(tenantId, context, request.lines());

        Invoice invoice = persist(tenant, customer, request.jobId(), request.issueDate(), request.dueDate(),
                request.currency(), request.notes(), resolved.lines(),
                discountOrDefault(request.discountAmount(), resolved));
        audit.record(AuditAction.INVOICE_CREATED, "Invoice", invoice.getId(),
                Map.of("number", invoice.getNumber(), "total", invoice.getTotal(),
                        "customerId", customer.getId().toString()));
        return toResponses(List.of(invoice)).getFirst();
    }

    @Transactional
    public InvoiceDtos.Response createFromJob(UUID jobId, InvoiceDtos.JobInvoiceRequest request) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        JobService.InvoiceContext context = jobs.requireInvoiceable(jobId);
        Customer customer = customers.require(context.customerId());
        requireNoActiveInvoice(tenantId, jobId);

        ResolvedLines resolved = resolveLines(tenantId, context, request.lines());
        String notes = request.notes() == null || request.notes().isBlank() ? context.notes() : request.notes();

        Invoice invoice = persist(tenant, customer, jobId, null, request.dueDate(), null, notes, resolved.lines(),
                discountOrDefault(request.discountAmount(), resolved));
        audit.record(AuditAction.INVOICE_CREATED, "Invoice", invoice.getId(),
                Map.of("number", invoice.getNumber(), "jobId", jobId.toString(),
                        "total", invoice.getTotal()));
        return toResponses(List.of(invoice)).getFirst();
    }

    @Transactional
    public InvoiceDtos.Response update(UUID invoiceId, InvoiceDtos.UpdateRequest request) {
        UUID tenantId = currentUser.tenantId();
        Invoice invoice = requireDetailed(invoiceId);
        requireDraft(invoice);
        Customer customer = customers.require(request.customerId());
        invoice.setCustomerId(customer.getId());

        LocalDate issueDate = request.issueDate() == null ? invoice.getIssueDate() : request.issueDate();
        LocalDate dueDate = request.dueDate() == null ? invoice.getDueDate() : request.dueDate();
        if (dueDate.isBefore(issueDate)) {
            throw ApiException.validation("dueDate", "must not be before issueDate");
        }
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(dueDate);
        invoice.setNotes(trimToNull(request.notes()));

        List<InvoiceDtos.LineRequest> lines = request.lines() == null ? List.of() : request.lines();
        if (lines.isEmpty()) {
            throw ApiException.validation("lines", "an invoice needs at least one line");
        }
        FinanceService.DocumentSetup setup = finance.prepare(tenantId, tenants.require(tenantId),
                invoice.getCurrency(), issueDate);
        applyPricing(tenantId, invoice, lines, request.discountAmount(), setup);
        writeTaxSnapshots(invoice);
        audit.record(AuditAction.INVOICE_CREATED, "Invoice", invoice.getId(),
                Map.of("number", invoice.getNumber(), "updated", true));
        return toResponses(List.of(invoice)).getFirst();
    }

    @Transactional
    public InvoiceDtos.Response send(UUID invoiceId) {
        Invoice invoice = requireDetailed(invoiceId);
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw ApiException.of(ErrorCode.INVALID_STATE_TRANSITION,
                    "cannot send an invoice in status " + invoice.getStatus());
        }
        if (invoice.getLines().isEmpty() || invoice.getTotal().signum() <= 0) {
            throw ApiException.validation("lines", "an invoice needs at least one priced line before it can be sent");
        }
        invoice.setStatus(InvoiceStatus.SENT);
        audit.record(AuditAction.INVOICE_SENT, "Invoice", invoice.getId(),
                Map.of("number", invoice.getNumber(), "total", invoice.getTotal()));
        return toResponses(List.of(invoice)).getFirst();
    }

    @Transactional
    public InvoiceDtos.Response voidInvoice(UUID invoiceId, InvoiceDtos.VoidRequest request) {
        Invoice invoice = require(invoiceId);
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw ApiException.of(ErrorCode.INVALID_STATE_TRANSITION, "this invoice is already void");
        }
        if (invoice.getAmountPaid().signum() != 0) {
            throw ApiException.of(ErrorCode.INVOICE_HAS_PAYMENTS,
                    "reverse the recorded payments before voiding this invoice");
        }
        invoice.setStatus(InvoiceStatus.VOID);
        invoice.setVoidReason(request.reason().trim());
        invoice.setPaidAt(null);
        audit.record(AuditAction.INVOICE_VOIDED, "Invoice", invoice.getId(),
                Map.of("number", invoice.getNumber(), "reason", invoice.getVoidReason()));
        return toResponses(List.of(invoice)).getFirst();
    }

    @Transactional
    public InvoiceDtos.PaymentResponse addPayment(UUID invoiceId, InvoiceDtos.PaymentRequest request) {
        Invoice invoice = require(invoiceId);
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw ApiException.immutable("a void invoice cannot receive payments");
        }
        BigDecimal amount = MoneyCalculator.money(request.amount());
        BigDecimal balance = invoice.balance();
        if (amount.compareTo(balance) > 0) {
            throw ApiException.of(ErrorCode.PAYMENT_EXCEEDS_BALANCE,
                    "payment exceeds the remaining balance of " + balance.toPlainString())
                    .withProperty("balance", balance);
        }
        Instant paidAt = request.paidAt() == null ? Instant.now() : request.paidAt();
        if (paidAt.isAfter(Instant.now().plus(PAID_AT_TOLERANCE))) {
            throw ApiException.validation("paidAt", "cannot be in the future");
        }

        InvoiceStatus priorStatus = invoice.getStatus();
        Payment payment = new Payment();
        payment.setAmount(amount);
        payment.setMethod(parseMethod(request.method()));
        payment.setReference(trimToNull(request.reference()));
        payment.setPaidAt(paidAt);
        payment.setRecordedBy(currentUser.id());
        invoice.addPayment(payment);
        invoice.setAmountPaid(MoneyCalculator.money(invoice.getAmountPaid().add(amount)));
        int baseScale = finance.precision(invoice.getBaseCurrency());
        invoice.setBaseAmountPaid(MoneyCalculator.money(
                invoice.getBaseAmountPaid().add(amount.multiply(invoice.getFxRate())), baseScale));
        recomputeStatus(invoice, priorStatus);

        audit.record(AuditAction.PAYMENT_RECORDED, "Payment", payment.getId(),
                Map.of("invoiceId", invoiceId.toString(), "amount", amount,
                        "method", payment.getMethod().name()));
        return InvoiceDtos.PaymentResponse.from(payment);
    }

    /**
     * Reverses a recorded payment. The payment is marked, never deleted, so the invoice timeline and
     * the money reports keep an honest record of the money that was taken and given back; the balance
     * is recomputed from the payments that are still active.
     */
    @Transactional
    public InvoiceDtos.Response reversePayment(UUID invoiceId, UUID paymentId) {
        Invoice invoice = require(invoiceId);
        Payment payment = payments.findByIdAndTenantIdAndInvoiceId(paymentId, invoice.getTenantId(), invoiceId)
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (payment.isReversed()) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "this payment has already been reversed");
        }
        InvoiceStatus priorStatus = invoice.getStatus();
        payment.setReversedAt(Instant.now());
        payment.setReversedBy(currentUser.id());

        // Recompute instead of subtracting, so a repeated reversal or an adjusted payment can never
        // leave the stored amount paid out of step with the rows behind it.
        BigDecimal activePaid = invoice.getPayments().stream()
                .filter(active -> !active.isReversed())
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        invoice.setAmountPaid(MoneyCalculator.money(activePaid));
        int baseScale = finance.precision(invoice.getBaseCurrency());
        invoice.setBaseAmountPaid(MoneyCalculator.money(
                activePaid.multiply(invoice.getFxRate()), baseScale));
        recomputeStatus(invoice, priorStatus);
        audit.record(AuditAction.PAYMENT_REVERSED, "Payment", paymentId,
                Map.of("invoiceId", invoiceId.toString(), "amount", payment.getAmount(),
                        "reversedAt", payment.getReversedAt().toString()));
        return toResponses(List.of(invoice)).getFirst();
    }

    // -------------------------------------------------------------------- internals

    private Invoice persist(Tenant tenant, Customer customer, UUID jobId, LocalDate requestedIssue,
                            LocalDate requestedDue, String requestedCurrency, String notes,
                            List<InvoiceDtos.LineRequest> lines, BigDecimal discount) {
        UUID tenantId = tenant.getId();
        Invoice invoice = new Invoice();
        invoice.setTenantId(tenantId);
        invoice.setCustomerId(customer.getId());
        invoice.setJobId(jobId);
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setNotes(trimToNull(notes));
        invoice.setCreatedBy(currentUser.id());

        LocalDate issueDate = requestedIssue == null ? today(tenantId) : requestedIssue;
        LocalDate dueDate = requestedDue == null ? issueDate.plusDays(properties.defaultDueDays()) : requestedDue;
        if (dueDate.isBefore(issueDate)) {
            throw ApiException.validation("dueDate", "must not be before issueDate");
        }
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(dueDate);

        FinanceService.DocumentSetup setup = finance.prepare(tenantId, tenant, requestedCurrency, issueDate);
        invoice.setCurrency(setup.currency());
        invoice.setLegalEntityId(setup.legalEntityId());
        invoice.setTaxProfileId(setup.taxProfileId());
        invoice.setTaxProfileName(setup.taxProfileName());
        invoice.setTaxMode(setup.taxMode().name());
        invoice.setTaxNumber(setup.legalEntityTaxNumber());
        invoice.setBaseCurrency(setup.baseCurrency());
        invoice.setFxRate(setup.fxRate());

        applyPricing(tenantId, invoice, lines, discount, setup);
        invoice.setNumber(numbers.next(tenantId, DocumentType.INVOICE, issueDate.getYear(),
                setup.legalEntityId()));
        Invoice saved = invoices.save(invoice);
        writeTaxSnapshots(invoice);
        return saved;
    }

    private record ResolvedLines(List<InvoiceDtos.LineRequest> lines, BigDecimal discountAmount) {
    }

    /**
     * Lines the invoice should bill: the caller's lines when supplied, otherwise the accepted
     * quote's line snapshots (with its header discount) so a converted quote is billed exactly
     * as agreed.
     */
    private ResolvedLines resolveLines(UUID tenantId, JobService.InvoiceContext context,
                                       List<InvoiceDtos.LineRequest> supplied) {
        if (supplied != null && !supplied.isEmpty()) {
            return new ResolvedLines(supplied, null);
        }
        if (context != null && context.quoteId() != null) {
            QuoteService.QuoteSnapshot snapshot = quotes.snapshot(tenantId, context.quoteId());
            if (!snapshot.lines().isEmpty()) {
                List<InvoiceDtos.LineRequest> lines = snapshot.lines().stream()
                        .map(line -> new InvoiceDtos.LineRequest(line.serviceItemId(), line.description(),
                                line.quantity(), line.unitPrice(), line.taxRate()))
                        .toList();
                return new ResolvedLines(lines, snapshot.discountAmount());
            }
        }
        throw ApiException.of(ErrorCode.NO_INVOICEABLE_LINES,
                "supply at least one invoice line; this job has no quoted lines to copy");
    }

    private BigDecimal discountOrDefault(BigDecimal requested, ResolvedLines resolved) {
        return requested != null ? requested : resolved.discountAmount();
    }

    private void applyPricing(UUID tenantId, Invoice invoice, List<InvoiceDtos.LineRequest> requested,
                              BigDecimal requestedDiscount, FinanceService.DocumentSetup setup) {
        List<InvoiceDtos.LineRequest> lines = requested == null ? List.of() : requested;
        List<MoneyCalculator.Line> input = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            InvoiceDtos.LineRequest line = lines.get(i);
            ServiceItem item = line.serviceItemId() == null ? null : catalog.require(tenantId, line.serviceItemId());
            String description = hasText(line.description()) ? line.description().trim()
                    : item == null ? null : item.getName();
            if (description == null) {
                throw ApiException.validation("lines[" + i + "].description",
                        "is required when no catalog service is selected");
            }
            BigDecimal unitPrice = line.unitPrice() != null ? line.unitPrice()
                    : item == null ? null : item.getBasePrice();
            if (unitPrice == null) {
                throw ApiException.validation("lines[" + i + "].unitPrice",
                        "is required when no catalog service is selected");
            }
            BigDecimal fallbackRate = line.taxRate() != null ? line.taxRate()
                    : item == null ? null : item.getTaxRate();
            UUID profileId = item == null ? null : item.getTaxProfileId();
            if (fallbackRate == null && profileId == null) {
                throw ApiException.validation("lines[" + i + "].taxRate",
                        "is required when no catalog service is selected");
            }
            TaxService.LineTax tax = finance.lineTax(tenantId, profileId, fallbackRate, setup.taxMode());
            input.add(new MoneyCalculator.Line(line.serviceItemId(), description, line.quantity(),
                    unitPrice, tax.rate(), tax.components(), tax.mode()));
        }

        MoneyCalculator.Totals totals = MoneyCalculator.calculate(input, requestedDiscount, setup.scale());
        if (requestedDiscount != null && requestedDiscount.compareTo(totals.subtotal()) > 0) {
            throw ApiException.validation("discountAmount", "cannot exceed the invoice subtotal");
        }
        invoice.setSubtotal(totals.subtotal());
        invoice.setDiscountAmount(totals.discountAmount());
        invoice.setTaxAmount(totals.taxAmount());
        invoice.setTotal(totals.total());
        invoice.setTaxMode(setup.taxMode().name());
        invoice.setBaseTotal(finance.toBase(totals.total(), setup));

        List<InvoiceLine> existing = invoice.getLines();
        List<MoneyCalculator.PricedLine> priced = totals.lines();
        for (int i = 0; i < priced.size(); i++) {
            MoneyCalculator.PricedLine source = priced.get(i);
            InvoiceLine line = i < existing.size() ? existing.get(i) : null;
            if (line == null) {
                line = new InvoiceLine();
                invoice.addLine(line);
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
            line.setTaxComponents(source.components());
        }
        while (existing.size() > priced.size()) {
            existing.removeLast();
        }
    }

    private void recomputeStatus(Invoice invoice, InvoiceStatus priorStatus) {
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            return;
        }
        BigDecimal paid = invoice.getAmountPaid();
        if (paid.signum() == 0) {
            invoice.setStatus(priorStatus == InvoiceStatus.DRAFT ? InvoiceStatus.DRAFT : InvoiceStatus.SENT);
            invoice.setPaidAt(null);
        } else if (paid.compareTo(invoice.getTotal()) >= 0) {
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setPaidAt(Instant.now());
        } else {
            invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
            invoice.setPaidAt(null);
        }
    }

    private void requireNoActiveInvoice(UUID tenantId, UUID jobId) {
        invoices.findActiveByJobId(jobId, tenantId, InvoiceStatus.VOID).ifPresent(existing -> {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "job is already invoiced by " + existing.getNumber());
        });
    }

    private Invoice require(UUID invoiceId) {
        return invoices.findByIdAndTenantId(invoiceId, currentUser.tenantId())
                .orElseThrow(() -> ApiException.notFound("Invoice"));
    }

    private Invoice requireDetailed(UUID invoiceId) {
        return invoices.findDetailed(invoiceId, currentUser.tenantId())
                .orElseThrow(() -> ApiException.notFound("Invoice"));
    }

    private void requireDraft(Invoice invoice) {
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw ApiException.immutable("invoice " + invoice.getNumber() + " is " + invoice.getStatus()
                    + " and can no longer be edited");
        }
    }

    private List<InvoiceDtos.Response> toResponses(List<Invoice> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        // Flush pending writes so generated createdAt/updatedAt values are present in the response.
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            invoices.flush();
        }
        UUID tenantId = currentUser.tenantId();
        LocalDate today = today(tenantId);
        Map<UUID, CustomerDtos.Summary> summaries = customers.summaries(tenantId,
                list.stream().map(Invoice::getCustomerId).collect(Collectors.toSet()));
        List<UUID> ids = list.stream().map(Invoice::getId).toList();
        Map<UUID, List<Payment>> paymentsByInvoice = payments
                .findAllByTenantIdAndInvoiceIdInOrderByPaidAtAsc(tenantId, ids).stream()
                .collect(Collectors.groupingBy(payment -> payment.getInvoice().getId()));
        List<UUID> lineIds = list.stream().flatMap(invoice -> invoice.getLines().stream())
                .map(InvoiceLine::getId).toList();
        Map<UUID, List<FinanceDtos.TaxAmountResponse>> rows = taxSnapshots.read(tenantId,
                TaxSnapshotWriter.INVOICE, lineIds);

        List<InvoiceDtos.Response> responses = new ArrayList<>(list.size());
        for (Invoice invoice : list) {
            Map<UUID, List<FinanceDtos.TaxAmountResponse>> forInvoice = new LinkedHashMap<>();
            invoice.getLines().forEach(line -> {
                List<FinanceDtos.TaxAmountResponse> values = rows.get(line.getId());
                if (values != null) {
                    forInvoice.put(line.getId(), values);
                }
            });
            responses.add(InvoiceDtos.Response.from(invoice, summaries.get(invoice.getCustomerId()),
                    paymentsByInvoice.getOrDefault(invoice.getId(), List.of()), isOverdue(invoice, today),
                    forInvoice, aggregate(forInvoice, invoice.getTotal(), invoice.getTaxAmount())));
        }
        return responses;
    }

    /** Persists printed tax rows once the lines have identifiers, inside the document transaction. */
    private void writeTaxSnapshots(Invoice invoice) {
        invoices.flush();
        Map<UUID, List<MoneyCalculator.TaxComponentAmount>> byLine = new LinkedHashMap<>();
        for (InvoiceLine line : invoice.getLines()) {
            if (line.getTaxComponents() != null && !line.getTaxComponents().isEmpty()) {
                byLine.put(line.getId(), line.getTaxComponents());
            }
        }
        taxSnapshots.replace(invoice.getTenantId(), TaxSnapshotWriter.INVOICE, byLine);
    }

    private static List<FinanceDtos.TaxAmountResponse> aggregate(
            Map<UUID, List<FinanceDtos.TaxAmountResponse>> lineComponents, BigDecimal total,
            BigDecimal taxAmount) {
        Map<String, FinanceDtos.TaxAmountResponse> byCode = new LinkedHashMap<>();
        lineComponents.values().forEach(rows -> rows.forEach(row -> {
            FinanceDtos.TaxAmountResponse existing = byCode.get(row.code());
            byCode.put(row.code(), existing == null ? row
                    : new FinanceDtos.TaxAmountResponse(existing.code(), existing.name(), existing.rate(),
                    existing.base().add(row.base()), existing.amount().add(row.amount())));
        }));
        if (!byCode.isEmpty()) {
            return List.copyOf(byCode.values());
        }
        if (taxAmount == null || taxAmount.signum() == 0) {
            return List.of();
        }
        BigDecimal base = total == null ? BigDecimal.ZERO : total.subtract(taxAmount);
        BigDecimal rate = base.signum() == 0 ? BigDecimal.ZERO
                : taxAmount.divide(base, 4, java.math.RoundingMode.HALF_UP);
        return List.of(new FinanceDtos.TaxAmountResponse("TAX", "Tax", rate, base, taxAmount));
    }

    private boolean isOverdue(Invoice invoice, LocalDate today) {
        return OPEN_STATUSES.contains(invoice.getStatus())
                && invoice.getDueDate().isBefore(today)
                && invoice.getAmountPaid().compareTo(invoice.getTotal()) < 0;
    }

    private LocalDate today(UUID tenantId) {
        return LocalDate.now(ZoneId.of(tenants.require(tenantId).getTimezone()));
    }

    private PaymentMethod parseMethod(String value) {
        try {
            return PaymentMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("method", "must be CASH, CARD, BANK_TRANSFER, CHECK or OTHER");
        }
    }

    private InvoiceStatus parseStatus(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("ALL")) {
            return null;
        }
        try {
            return InvoiceStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("status",
                    "must be DRAFT, SENT, PARTIALLY_PAID, PAID or VOID");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
