package com.operonix.global;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.billing.Invoice;
import com.operonix.billing.InvoiceRepository;
import com.operonix.billing.InvoiceStatus;
import com.operonix.catalog.ServiceItem;
import com.operonix.catalog.ServiceItemRepository;
import com.operonix.common.error.ApiException;
import com.operonix.common.money.MoneyCalculator;
import com.operonix.finance.ExchangeRateService;
import com.operonix.global.dto.CostingDtos;
import com.operonix.identity.AppUser;
import com.operonix.identity.AppUserRepository;
import com.operonix.quoting.Quote;
import com.operonix.quoting.QuoteLine;
import com.operonix.quoting.QuoteRepository;
import com.operonix.scheduling.Job;
import com.operonix.scheduling.JobRepository;
import com.operonix.scheduling.WorkLog;
import com.operonix.security.CurrentUser;
import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Costing engine (P1b).
 *
 * <p>All money is expressed in the workspace currency. Source rows keep what the user entered
 * (including a foreign currency and the rate used), and the roll-up stores the converted value, so a
 * later exchange-rate change cannot alter a recorded cost.
 *
 * <p>Two rules keep the numbers honest:
 *
 * <ol>
 *   <li>Profitability is <b>incomplete</b>, not zero, whenever an input is missing — an open time
 *       log, a technician with no configured hourly cost, a job with no invoice, or revenue in a
 *       currency that cannot be converted to the workspace currency.</li>
 *   <li>The roll-up is rebuilt from source rows on every change, so recomputation is idempotent and
 *       a repeated event cannot double-count a cost.</li>
 * </ol>
 */
@Service
public class CostingService {

    /** Elapsed minutes are converted to hours at this scale before multiplying by a rate. */
    private static final int TIME_SCALE = 6;

    private final WorkLogRepository workLogs;
    private final VendorRepository vendors;
    private final ExpenseRepository expenses;
    private final JobCostRepository jobCosts;
    private final JobRepository jobs;
    private final AppUserRepository users;
    private final InvoiceRepository invoices;
    private final QuoteRepository quotes;
    private final ServiceItemRepository serviceItems;
    private final ExchangeRateService exchangeRates;
    private final ReferenceDataService referenceData;
    private final TenantService tenants;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final Clock clock;

    public CostingService(WorkLogRepository workLogs, VendorRepository vendors, ExpenseRepository expenses,
                          JobCostRepository jobCosts, JobRepository jobs, AppUserRepository users,
                          InvoiceRepository invoices, QuoteRepository quotes,
                          ServiceItemRepository serviceItems, ExchangeRateService exchangeRates,
                          ReferenceDataService referenceData, TenantService tenants,
                          CurrentUser currentUser, AuditService audit, Clock clock) {
        this.workLogs = workLogs;
        this.vendors = vendors;
        this.expenses = expenses;
        this.jobCosts = jobCosts;
        this.jobs = jobs;
        this.users = users;
        this.invoices = invoices;
        this.quotes = quotes;
        this.serviceItems = serviceItems;
        this.exchangeRates = exchangeRates;
        this.referenceData = referenceData;
        this.tenants = tenants;
        this.currentUser = currentUser;
        this.audit = audit;
        this.clock = clock;
    }

    // ================================================================ context

    /** Workspace currency and its precision, resolved once per request. */
    public record Money(String currency, int scale) {

        BigDecimal round(BigDecimal value) {
            return MoneyCalculator.money(value, scale);
        }

        BigDecimal zero() {
            return MoneyCalculator.zero(scale);
        }
    }

    @Transactional(readOnly = true)
    public Money money() {
        Tenant tenant = tenants.require(currentUser.tenantId());
        Currency currency = referenceData.requireCurrency(tenant.getCurrency());
        return new Money(currency.getCode(), currency.getMinorUnits());
    }

    // ================================================================ vendors

    @Transactional(readOnly = true)
    public List<CostingDtos.VendorResponse> listVendors(boolean activeOnly) {
        UUID tenantId = currentUser.tenantId();
        List<Vendor> found = activeOnly
                ? vendors.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)
                : vendors.findByTenantIdOrderByNameAsc(tenantId);
        return found.stream().map(CostingService::toVendor).toList();
    }

    @Transactional
    public CostingDtos.VendorResponse createVendor(CostingDtos.VendorRequest request) {
        UUID tenantId = currentUser.tenantId();
        String name = request.name().trim();
        if (vendors.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw ApiException.conflict(com.operonix.common.error.ErrorCode.CONFLICT,
                    "A vendor called \"" + name + "\" already exists");
        }
        Vendor vendor = new Vendor(tenantId, name);
        apply(vendor, request);
        vendors.save(vendor);
        audit.record(AuditAction.VENDOR_CREATED, "vendor", vendor.getId(), Map.of("name", name));
        return toVendor(vendor);
    }

    @Transactional
    public CostingDtos.VendorResponse updateVendor(UUID vendorId, CostingDtos.VendorRequest request) {
        UUID tenantId = currentUser.tenantId();
        Vendor vendor = vendors.findByIdAndTenantId(vendorId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Vendor"));
        String name = request.name().trim();
        if (vendors.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, name, vendorId)) {
            throw ApiException.conflict(com.operonix.common.error.ErrorCode.CONFLICT,
                    "A vendor called \"" + name + "\" already exists");
        }
        vendor.setName(name);
        apply(vendor, request);
        audit.record(AuditAction.VENDOR_UPDATED, "vendor", vendor.getId(), Map.of("name", name));
        return toVendor(vendor);
    }

    private void apply(Vendor vendor, CostingDtos.VendorRequest request) {
        vendor.setContactName(trimToNull(request.contactName()));
        vendor.setEmail(trimToNull(request.email()));
        vendor.setPhone(trimToNull(request.phone()));
        vendor.setTaxNumber(trimToNull(request.taxNumber()));
        vendor.setDefaultRate(MoneyCalculator.money(
                request.defaultRate() == null ? BigDecimal.ZERO : request.defaultRate(), 2));
        vendor.setPaymentTerms(request.paymentTerms() == null || request.paymentTerms().isBlank()
                ? "NET_30" : request.paymentTerms().trim().toUpperCase(java.util.Locale.ROOT));
        if (request.active() != null) {
            vendor.setActive(request.active());
        }
    }

    // ================================================================ work logs

    @Transactional
    public CostingDtos.WorkLogResponse addWorkLog(UUID jobId, CostingDtos.WorkLogRequest request) {
        UUID tenantId = currentUser.tenantId();
        Job job = requireJob(jobId);
        UUID actorId = currentUser.id();
        boolean manager = currentUser.role().isManagerOrAbove();
        UUID userId = request.userId() != null && manager ? request.userId() : actorId;
        if (!manager && !userId.equals(actorId)) {
            throw ApiException.of(com.operonix.common.error.ErrorCode.FORBIDDEN,
                    "Only a manager can log time for another person");
        }
        AppUser user = users.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Team member"));
        if (!user.isActive()) {
            throw ApiException.of(com.operonix.common.error.ErrorCode.VALIDATION_FAILED,
                    "That team member is inactive");
        }
        if (request.endAt() != null && request.endAt().isBefore(request.startAt())) {
            throw ApiException.validation("endAt", "must not be before the start time");
        }
        if (workLogs.findOpenForUser(tenantId, jobId, userId).isPresent()) {
            throw ApiException.conflict(com.operonix.common.error.ErrorCode.CONFLICT,
                    user.getFullName() + " already has an open time entry on this job. Stop it first.");
        }
        WorkLog log = new WorkLog(job, user, request.startAt());
        log.setEndAt(request.endAt());
        log.setManual(request.endAt() != null);
        log.setNotes(trimToNull(request.notes()));
        workLogs.save(log);
        audit.record(AuditAction.WORK_LOG_RECORDED, "job", jobId,
                Map.of("workLogId", log.getId().toString(), "userId", userId.toString()));
        recompute(jobId);
        return toWorkLog(log, moneyFor(job));
    }

    @Transactional
    public CostingDtos.WorkLogResponse stopWorkLog(UUID workLogId, Instant endAt) {
        UUID tenantId = currentUser.tenantId();
        WorkLog log = workLogs.findByIdAndTenantId(workLogId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Time entry"));
        requireOwnership(log);
        if (!log.isOpen()) {
            throw ApiException.of(com.operonix.common.error.ErrorCode.IMMUTABLE_RESOURCE,
                    "This time entry has already been stopped");
        }
        Instant stop = endAt == null ? clock.instant() : endAt;
        if (stop.isBefore(log.getStartAt())) {
            throw ApiException.validation("endAt", "must not be before the start time");
        }
        log.setEndAt(stop);
        log.setManual(false);
        audit.record(AuditAction.WORK_LOG_RECORDED, "job", log.getJob().getId(),
                Map.of("workLogId", log.getId().toString(), "stopped", true));
        recompute(log.getJob().getId());
        return toWorkLog(log, moneyFor(log.getJob()));
    }

    @Transactional
    public void deleteWorkLog(UUID workLogId) {
        UUID tenantId = currentUser.tenantId();
        WorkLog log = workLogs.findByIdAndTenantId(workLogId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Time entry"));
        requireOwnership(log);
        UUID jobId = log.getJob().getId();
        workLogs.delete(log);
        audit.record(AuditAction.WORK_LOG_DELETED, "job", jobId,
                Map.of("workLogId", workLogId.toString()));
        recompute(jobId);
    }

    private void requireOwnership(WorkLog log) {
        if (currentUser.role().isManagerOrAbove()) {
            return;
        }
        if (!log.getAppUser().getId().equals(currentUser.id())) {
            throw ApiException.of(com.operonix.common.error.ErrorCode.FORBIDDEN,
                    "You can only change your own time entries");
        }
    }

    // ================================================================ expenses

    @Transactional
    public CostingDtos.ExpenseResponse addExpense(UUID jobId, CostingDtos.ExpenseRequest request) {
        UUID tenantId = currentUser.tenantId();
        Money money = money();
        Job job = jobId == null ? null : requireJob(jobId);
        UUID vendorId = request.vendorId();
        Vendor vendor = vendorId == null ? null : vendors.findByIdAndTenantId(vendorId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Vendor"));

        String currency = request.currency() == null || request.currency().isBlank()
                ? money.currency() : request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        Currency resolved = referenceData.requireCurrency(currency);
        BigDecimal amount = MoneyCalculator.money(request.amount(), resolved.getMinorUnits());
        if (amount.signum() <= 0) {
            throw ApiException.validation("amount", "must be greater than zero");
        }

        BigDecimal rate;
        try {
            rate = exchangeRates.rate(currency, money.currency(), request.expenseDate());
        } catch (ApiException ex) {
            throw ApiException.of(com.operonix.common.error.ErrorCode.FX_RATE_MISSING,
                    "No exchange rate from " + currency + " to " + money.currency()
                            + " on or before " + request.expenseDate()
                            + ". Record a rate before booking this cost.");
        }

        Expense expense = new Expense();
        expense.setTenantId(tenantId);
        expense.setJob(job);
        expense.setVendor(vendor);
        expense.setDescription(request.description().trim());
        expense.setAmount(amount);
        expense.setCurrency(currency);
        expense.setFxRate(rate);
        expense.setWorkspaceAmount(money.round(amount.multiply(rate)));
        expense.setReceiptReference(trimToNull(request.receiptReference()));
        expense.setExpenseDate(request.expenseDate());
        expense.setCategory(ExpenseCategory.parse(request.category()));
        expense.setNotes(trimToNull(request.notes()));
        expense.setCreatedBy(currentUser.id());
        expenses.save(expense);

        audit.record(AuditAction.EXPENSE_RECORDED, "expense", expense.getId(),
                Map.of("amount", amount.toPlainString(), "currency", currency,
                        "category", expense.getCategory().name()));
        if (job != null) {
            recompute(job.getId());
        }
        return toExpense(expense);
    }

    @Transactional
    public void deleteExpense(UUID expenseId) {
        UUID tenantId = currentUser.tenantId();
        Expense expense = expenses.findByIdAndTenantId(expenseId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Expense"));
        UUID jobId = expense.getJob() == null ? null : expense.getJob().getId();
        expenses.delete(expense);
        audit.record(AuditAction.EXPENSE_DELETED, "expense", expenseId,
                Map.of("category", expense.getCategory().name()));
        if (jobId != null) {
            recompute(jobId);
        }
    }

    @Transactional(readOnly = true)
    public List<CostingDtos.ExpenseResponse> listExpenses(UUID jobId) {
        UUID tenantId = currentUser.tenantId();
        return expenses.findByTenantIdAndJobIdOrderByExpenseDateDescCreatedAtDesc(tenantId, jobId)
                .stream().map(CostingService::toExpense).toList();
    }

    // ================================================================ view / profitability

    @Transactional(readOnly = true)
    public CostingDtos.JobCostingView view(UUID jobId) {
        Job job = requireJob(jobId);
        Money money = moneyFor(job);
        List<WorkLog> logs = workLogs.findByTenantIdAndJobIdOrderByStartAtAsc(job.getTenantId(), jobId);
        List<Expense> jobExpenses = expenses
                .findByTenantIdAndJobIdOrderByExpenseDateDescCreatedAtDesc(job.getTenantId(), jobId);

        CostingDtos.JobCostResponse cost = jobCosts.findByTenantIdAndJobId(job.getTenantId(), jobId)
                .map(CostingService::toCost)
                .orElse(new CostingDtos.JobCostResponse(jobId, money.currency(), money.zero(), money.zero(),
                        money.zero(), money.zero(), money.zero(), money.zero(), null, 0));

        return new CostingDtos.JobCostingView(cost, profitabilityOf(job, money, logs, jobExpenses),
                logs.stream().map(log -> toWorkLog(log, money)).toList(),
                jobExpenses.stream().map(CostingService::toExpense).toList(),
                listVendors(false));
    }

    @Transactional(readOnly = true)
    public CostingDtos.ProfitabilityResponse profitability(UUID jobId) {
        Job job = requireJob(jobId);
        Money money = moneyFor(job);
        return profitabilityOf(job, money,
                workLogs.findByTenantIdAndJobIdOrderByStartAtAsc(job.getTenantId(), jobId),
                expenses.findByTenantIdAndJobIdOrderByExpenseDateDescCreatedAtDesc(job.getTenantId(), jobId));
    }

    /**
     * Rebuilds the stored roll-up for one job from its source rows and refreshes its actual start/end.
     * Safe to call repeatedly: the row is replaced, not incremented.
     */
    @Transactional
    public CostingDtos.JobCostResponse recompute(UUID jobId) {
        Job job = requireJob(jobId);
        Money money = moneyFor(job);
        List<WorkLog> logs = workLogs.findByTenantIdAndJobIdOrderByStartAtAsc(job.getTenantId(), jobId);
        List<Expense> jobExpenses = expenses
                .findByTenantIdAndJobIdOrderByExpenseDateDescCreatedAtDesc(job.getTenantId(), jobId);
        RollUp rollUp = rollUp(money, logs, jobExpenses);

        JobCost cost = jobCosts.findByTenantIdAndJobId(job.getTenantId(), jobId)
                .orElseGet(() -> new JobCost(job.getTenantId(), job, money.currency()));
        cost.setCurrency(money.currency());
        cost.setLaborCost(rollUp.labor());
        cost.setMaterialCost(rollUp.material());
        cost.setEquipmentCost(rollUp.equipment());
        cost.setSubcontractorCost(rollUp.subcontractor());
        cost.setOtherCost(rollUp.other());
        cost.recomputeTotal();
        cost.setComputedAt(clock.instant());
        cost.setComputedBy(currentUser.optional().map(u -> u.id()).orElse(null));
        jobCosts.save(cost);

        job.setActualStart(logs.stream().map(WorkLog::getStartAt).min(Comparator.naturalOrder()).orElse(null));
        job.setActualEnd(logs.stream().filter(log -> log.getEndAt() != null).map(WorkLog::getEndAt)
                .max(Comparator.naturalOrder()).orElse(null));
        return toCost(cost);
    }

    private CostingDtos.ProfitabilityResponse profitabilityOf(Job job, Money money, List<WorkLog> logs,
                                                             List<Expense> jobExpenses) {
        RollUp rollUp = rollUp(money, logs, jobExpenses);
        List<String> reasons = new ArrayList<>(rollUp.reasons());

        // Revenue: invoices raised against this job, expressed in the workspace currency.
        BigDecimal revenue = money.zero();
        boolean revenueKnown = true;
        for (Invoice invoice : invoices.findByTenantIdAndJobId(job.getTenantId(), job.getId())) {
            if (invoice.getStatus() == InvoiceStatus.VOID) {
                continue;
            }
            BigDecimal inWorkspace = null;
            if (money.currency().equals(invoice.getCurrency())) {
                inWorkspace = invoice.getTotal();
            } else if (money.currency().equals(invoice.getBaseCurrency())) {
                inWorkspace = invoice.getBaseTotal();
            }
            if (inWorkspace == null) {
                revenueKnown = false;
                reasons.add("Invoice " + invoice.getNumber() + " is in " + invoice.getCurrency()
                        + " and has no " + money.currency() + " value recorded");
            } else {
                revenue = revenue.add(inWorkspace);
            }
        }
        boolean invoiced = invoices.findByTenantIdAndJobId(job.getTenantId(), job.getId()).stream()
                .anyMatch(invoice -> invoice.getStatus() != InvoiceStatus.VOID);
        if (!invoiced) {
            revenueKnown = false;
            reasons.add("No invoice has been raised for this job yet");
        }
        revenue = money.round(revenue);

        // Planned figures come from the quote this job was converted from.
        BigDecimal plannedRevenue = null;
        BigDecimal plannedCost = null;
        if (job.getQuoteId() != null) {
            Quote quote = quotes.findDetailed(job.getQuoteId(), job.getTenantId()).orElse(null);
            if (quote != null) {
                plannedRevenue = inWorkspace(quote.getTotal(), quote.getCurrency(),
                        quote.getBaseTotal(), quote.getBaseCurrency(), money);
                plannedCost = plannedCost(quote, money);
                if (plannedCost == null) {
                    reasons.add("Some quoted items have no planned cost in the catalogue");
                }
            }
        }
        BigDecimal plannedMargin = plannedRevenue == null || plannedCost == null
                ? null : money.round(plannedRevenue.subtract(plannedCost));

        boolean complete = reasons.isEmpty() && revenueKnown;
        BigDecimal directProfit = complete ? money.round(revenue.subtract(rollUp.total())) : null;
        BigDecimal marginPercent = complete && revenue.signum() > 0 && directProfit != null
                ? directProfit.multiply(BigDecimal.valueOf(100))
                        .divide(revenue, 2, RoundingMode.HALF_UP)
                : null;

        long actualMinutes = logs.stream().filter(log -> log.getEndAt() != null)
                .mapToLong(log -> Duration.between(log.getStartAt(), log.getEndAt()).toMinutes()).sum();
        long scheduledMinutes = Duration.between(job.getScheduledStart(), job.getScheduledEnd()).toMinutes();

        return new CostingDtos.ProfitabilityResponse(job.getId(), money.currency(),
                complete ? "COMPLETE" : "INCOMPLETE", List.copyOf(new java.util.LinkedHashSet<>(reasons)),
                complete ? revenue : null,
                rollUp.labor(), rollUp.material(), rollUp.equipment(), rollUp.subcontractor(), rollUp.other(),
                rollUp.total(), directProfit, marginPercent,
                plannedRevenue, plannedCost, plannedMargin,
                actualMinutes, scheduledMinutes, clock.instant(),
                "Direct job profit only. Overheads and other unallocated business costs are excluded;"
                        + " business net profit is reported separately in analytics.");
    }

    /** Maps a document total into the workspace currency, or null when no usable snapshot exists. */
    private BigDecimal inWorkspace(BigDecimal total, String currency, BigDecimal baseTotal,
                                   String baseCurrency, Money money) {
        if (money.currency().equals(currency)) {
            return money.round(total);
        }
        if (money.currency().equals(baseCurrency)) {
            return money.round(baseTotal);
        }
        return null;
    }

    private BigDecimal plannedCost(Quote quote, Money money) {
        BigDecimal total = money.zero();
        boolean complete = true;
        for (QuoteLine line : quote.getLines()) {
            if (line.getServiceItemId() == null) {
                continue;
            }
            ServiceItem item = serviceItems.findById(line.getServiceItemId()).orElse(null);
            if (item == null || item.getCost() == null) {
                complete = false;
                continue;
            }
            total = total.add(item.getCost().multiply(line.getQuantity()));
        }
        return complete ? money.round(total) : null;
    }

    // ================================================================ roll-up

    private record RollUp(BigDecimal labor, BigDecimal material, BigDecimal equipment,
                          BigDecimal subcontractor, BigDecimal other, BigDecimal total,
                          List<String> reasons) {
    }

    private RollUp rollUp(Money money, List<WorkLog> logs, List<Expense> jobExpenses) {
        BigDecimal labor = money.zero();
        List<String> reasons = new ArrayList<>();
        Map<UUID, String> unpriced = new LinkedHashMap<>();

        for (WorkLog log : logs) {
            if (log.isOpen()) {
                reasons.add(log.getAppUser().getFullName() + " has a time entry that has not been stopped");
                continue;
            }
            BigDecimal rate = log.getAppUser().getHourlyCost();
            if (rate == null || rate.signum() <= 0) {
                unpriced.putIfAbsent(log.getAppUser().getId(), log.getAppUser().getFullName());
                continue;
            }
            BigDecimal hours = BigDecimal.valueOf(Duration.between(log.getStartAt(), log.getEndAt()).toMinutes())
                    .divide(BigDecimal.valueOf(60), TIME_SCALE, RoundingMode.HALF_UP);
            labor = labor.add(rate.multiply(hours));
        }
        unpriced.values().forEach(name ->
                reasons.add("No hourly cost configured for " + name));

        BigDecimal material = money.zero();
        BigDecimal equipment = money.zero();
        BigDecimal subcontractor = money.zero();
        BigDecimal other = money.zero();
        for (Expense expense : jobExpenses) {
            BigDecimal amount = expense.getWorkspaceAmount();
            switch (expense.getCategory()) {
                case LABOR -> labor = labor.add(amount);
                case MATERIAL -> material = material.add(amount);
                case EQUIPMENT -> equipment = equipment.add(amount);
                case SUBCONTRACTOR -> subcontractor = subcontractor.add(amount);
                case OTHER -> other = other.add(amount);
            }
        }

        labor = money.round(labor);
        material = money.round(material);
        equipment = money.round(equipment);
        subcontractor = money.round(subcontractor);
        other = money.round(other);
        BigDecimal total = money.round(labor.add(material).add(equipment).add(subcontractor).add(other));
        return new RollUp(labor, material, equipment, subcontractor, other, total, reasons);
    }

    // ================================================================ helpers

    private Job requireJob(UUID jobId) {
        return jobs.findDetailed(jobId, currentUser.tenantId())
                .orElseThrow(() -> ApiException.notFound("Job"));
    }

    private Money moneyFor(Job job) {
        // The workspace currency, not the job's: a job has no currency of its own.
        return money();
    }

    private static CostingDtos.VendorResponse toVendor(Vendor vendor) {
        return new CostingDtos.VendorResponse(vendor.getId(), vendor.getName(), vendor.getContactName(),
                vendor.getEmail(), vendor.getPhone(), vendor.getTaxNumber(), vendor.getDefaultRate(),
                vendor.getPaymentTerms(), vendor.isActive(), vendor.getVersion());
    }

    private CostingDtos.WorkLogResponse toWorkLog(WorkLog log, Money money) {
        BigDecimal cost = null;
        if (!log.isOpen()) {
            BigDecimal rate = log.getAppUser().getHourlyCost();
            if (rate != null && rate.signum() > 0) {
                BigDecimal hours = BigDecimal.valueOf(Duration.between(log.getStartAt(), log.getEndAt()).toMinutes())
                        .divide(BigDecimal.valueOf(60), TIME_SCALE, RoundingMode.HALF_UP);
                cost = money.round(rate.multiply(hours));
            }
        }
        return new CostingDtos.WorkLogResponse(log.getId(), log.getJob().getId(),
                log.getAppUser().getId(), log.getAppUser().getFullName(), log.getStartAt(), log.getEndAt(),
                log.minutes(), log.isManual(), log.getNotes(), cost, log.getVersion());
    }

    private static CostingDtos.ExpenseResponse toExpense(Expense expense) {
        return new CostingDtos.ExpenseResponse(expense.getId(),
                expense.getJob() == null ? null : expense.getJob().getId(),
                expense.getJob() == null ? null : expense.getJob().getNumber(),
                expense.getVendor() == null ? null : expense.getVendor().getId(),
                expense.getVendor() == null ? null : expense.getVendor().getName(),
                expense.getDescription(), expense.getAmount(), expense.getCurrency(), expense.getFxRate(),
                expense.getWorkspaceAmount(), expense.getReceiptReference(), expense.getExpenseDate(),
                expense.getCategory().name(), expense.getNotes(), expense.getVersion());
    }

    private static CostingDtos.JobCostResponse toCost(JobCost cost) {
        return new CostingDtos.JobCostResponse(cost.getJob().getId(), cost.getCurrency(),
                cost.getLaborCost(), cost.getMaterialCost(), cost.getEquipmentCost(),
                cost.getSubcontractorCost(), cost.getOtherCost(), cost.getTotalCost(),
                cost.getComputedAt(), cost.getVersion());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Exposed for the billing module: refresh a job's roll-up after an invoice changes. */
    @Transactional
    public void refreshForJob(UUID jobId) {
        if (jobId != null) {
            recompute(jobId);
        }
    }
}
