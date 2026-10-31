package com.operonix.reporting;

import com.operonix.billing.InvoiceRepository;
import com.operonix.billing.InvoiceStatus;
import com.operonix.billing.Payment;
import com.operonix.billing.PaymentRepository;
import com.operonix.common.error.ApiException;
import com.operonix.customer.CustomerRepository;
import com.operonix.customer.CustomerStatus;
import com.operonix.identity.AppUser;
import com.operonix.identity.AppUserRepository;
import com.operonix.identity.Role;
import com.operonix.quoting.QuoteRepository;
import com.operonix.quoting.QuoteStatus;
import com.operonix.reporting.dto.ReportDtos;
import com.operonix.scheduling.Job;
import com.operonix.scheduling.JobAssignment;
import com.operonix.scheduling.JobRepository;
import com.operonix.scheduling.JobStatus;
import com.operonix.scheduling.JobService;
import com.operonix.security.CurrentUser;
import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class ReportService {

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final Set<QuoteStatus> ACCEPTED_QUOTE_STATUSES = Set.of(QuoteStatus.ACCEPTED,
            QuoteStatus.CONVERTED);
    private static final Set<QuoteStatus> DECIDED_QUOTE_STATUSES = Set.of(QuoteStatus.ACCEPTED,
            QuoteStatus.CONVERTED, QuoteStatus.DECLINED);
    private static final Set<InvoiceStatus> OPEN_INVOICE_STATUSES = Set.of(InvoiceStatus.SENT,
            InvoiceStatus.PARTIALLY_PAID);

    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final QuoteRepository quotes;
    private final JobRepository jobs;
    private final CustomerRepository customers;
    private final AppUserRepository users;
    private final JobService jobService;
    private final TenantService tenants;
    private final CurrentUser currentUser;

    public ReportService(PaymentRepository payments, InvoiceRepository invoices, QuoteRepository quotes,
                         JobRepository jobs, CustomerRepository customers, AppUserRepository users,
                         JobService jobService, TenantService tenants, CurrentUser currentUser) {
        this.payments = payments;
        this.invoices = invoices;
        this.quotes = quotes;
        this.jobs = jobs;
        this.customers = customers;
        this.users = users;
        this.jobService = jobService;
        this.tenants = tenants;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public ReportDtos.Dashboard dashboard() {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        LocalDate today = LocalDate.now(zone);
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone).toInstant();
        Instant weekEnd = weekStart.plus(Duration.ofDays(7));

        long jobsToday = jobService.countScheduledBetween(tenantId, dayStart, dayEnd);
        long jobsThisWeek = jobService.countScheduledBetween(tenantId, weekStart, weekEnd);
        List<com.operonix.scheduling.dto.JobDtos.Response> upcoming =
                jobService.upcoming(tenantId, Instant.now());

        if (currentUser.role() == Role.TECHNICIAN) {
            // Field staff get operational counters only; no revenue, receivables or rates.
            return new ReportDtos.Dashboard(null, null, null, null, null, jobsToday, jobsThisWeek,
                    null, null, null, List.of(), List.of(), upcoming);
        }

        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        Instant nextMonthStart = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant();
        Instant previousMonthStart = today.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant();

        BigDecimal thisMonth = money(payments.sumBetween(tenantId, monthStart, nextMonthStart));
        BigDecimal previousMonth = money(payments.sumBetween(tenantId, previousMonthStart, monthStart));
        BigDecimal delta = previousMonth.signum() == 0 ? null
                : thisMonth.subtract(previousMonth).multiply(BigDecimal.valueOf(100))
                .divide(previousMonth, 1, RoundingMode.HALF_UP);

        long pendingQuotes = quotes.countByStatuses(tenantId, Set.of(QuoteStatus.SENT));
        long accepted = quotes.countByTenantIdAndStatusIn(tenantId, ACCEPTED_QUOTE_STATUSES);
        long decided = quotes.countByTenantIdAndStatusIn(tenantId, DECIDED_QUOTE_STATUSES);
        BigDecimal acceptanceRate = decided == 0 ? null
                : BigDecimal.valueOf(accepted).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(decided), 1, RoundingMode.HALF_UP);

        return new ReportDtos.Dashboard(thisMonth, previousMonth, delta,
                money(invoices.sumOutstanding(tenantId, OPEN_INVOICE_STATUSES)),
                money(invoices.sumOverdue(tenantId, OPEN_INVOICE_STATUSES, today)),
                jobsToday, jobsThisWeek, pendingQuotes, acceptanceRate,
                customers.countByTenantIdAndStatus(tenantId, CustomerStatus.ACTIVE),
                revenueByDay(tenantId, zone, today.minusDays(13), today),
                topServices(tenantId, today.minusDays(30), today.plusDays(1), 5),
                upcoming);
    }

    @Transactional(readOnly = true)
    public ReportDtos.RevenueReport revenue(LocalDate from, LocalDate to, String groupBy) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        if (to.isBefore(from)) {
            throw ApiException.validation("to", "must not be before from");
        }
        String grouping = groupBy == null || groupBy.isBlank() ? "day" : groupBy.trim().toLowerCase(Locale.ROOT);
        return switch (grouping) {
            case "day" -> {
                if (Duration.between(from.atStartOfDay(zone), to.plusDays(1).atStartOfDay(zone)).toDays() > 366) {
                    throw ApiException.validation("to", "day grouping is limited to a 366-day range");
                }
                yield new ReportDtos.RevenueReport("day", revenueByDay(tenantId, zone, from, to),
                        totalOfDayBuckets(tenantId, zone, from, to));
            }
            case "month" -> {
                if (from.plusMonths(60).isBefore(to)) {
                    throw ApiException.validation("to", "month grouping is limited to a 60-month range");
                }
                yield monthBuckets(tenantId, zone, from, to);
            }
            case "service" -> {
                List<ReportDtos.Bucket> buckets = topServices(tenantId, from, to.plusDays(1), 50).stream()
                        .map(service -> new ReportDtos.Bucket(service.label(), service.label(),
                                service.revenue(), 0))
                        .toList();
                BigDecimal total = buckets.stream().map(ReportDtos.Bucket::revenue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                yield new ReportDtos.RevenueReport("service", buckets, money(total));
            }
            default -> throw ApiException.validation("groupBy", "must be day, month or service");
        };
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.TechnicianPerformance> technicianPerformance(LocalDate from, LocalDate to) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        if (to.isBefore(from)) {
            throw ApiException.validation("to", "must not be before from");
        }
        List<Job> window = jobs.findAllInRange(tenantId, from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(), JobStatus.CANCELLED);

        Map<UUID, long[]> stats = new HashMap<>();
        for (Job job : window) {
            long minutes = Duration.between(job.getScheduledStart(), job.getScheduledEnd()).toMinutes();
            for (JobAssignment assignment : job.getAssignments()) {
                long[] values = stats.computeIfAbsent(assignment.getUserId(), key -> new long[2]);
                if (job.getStatus() == JobStatus.COMPLETED) {
                    values[0]++;
                }
                values[1] += minutes;
            }
        }

        List<ReportDtos.TechnicianPerformance> result = new ArrayList<>();
        for (AppUser user : users.findAllByTenantIdOrderByFullNameAsc(tenantId)) {
            if (user.getRole() != Role.TECHNICIAN || !user.isActive()) {
                continue;
            }
            long[] values = stats.getOrDefault(user.getId(), new long[2]);
            result.add(new ReportDtos.TechnicianPerformance(user.getId(), user.getFullName(),
                    values[0], values[1]));
        }
        return result;
    }

    // -------------------------------------------------------------------- internals

    private List<ReportDtos.Bucket> revenueByDay(UUID tenantId, ZoneId zone, LocalDate from, LocalDate to) {
        Map<LocalDate, List<Payment>> byDay = new TreeMap<>();
        for (Payment payment : payments.findAllBetween(tenantId, from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant())) {
            byDay.computeIfAbsent(payment.getPaidAt().atZone(zone).toLocalDate(), key -> new ArrayList<>())
                    .add(payment);
        }
        List<ReportDtos.Bucket> buckets = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            List<Payment> dayPayments = byDay.getOrDefault(day, List.of());
            BigDecimal revenue = dayPayments.stream().map(Payment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long invoiceCount = dayPayments.stream().map(payment -> payment.getInvoice().getId())
                    .distinct().count();
            buckets.add(new ReportDtos.Bucket(day.toString(), day.format(DAY_LABEL), money(revenue),
                    invoiceCount));
        }
        return buckets;
    }

    private ReportDtos.RevenueReport monthBuckets(UUID tenantId, ZoneId zone, LocalDate from, LocalDate to) {
        Map<YearMonth, List<Payment>> byMonth = new TreeMap<>();
        for (Payment payment : payments.findAllBetween(tenantId, from.withDayOfMonth(1).atStartOfDay(zone).toInstant(),
                to.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant())) {
            byMonth.computeIfAbsent(YearMonth.from(payment.getPaidAt().atZone(zone)), key -> new ArrayList<>())
                    .add(payment);
        }
        List<ReportDtos.Bucket> buckets = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            List<Payment> monthPayments = byMonth.getOrDefault(month, List.of());
            BigDecimal revenue = monthPayments.stream().map(Payment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long invoiceCount = monthPayments.stream().map(payment -> payment.getInvoice().getId())
                    .distinct().count();
            buckets.add(new ReportDtos.Bucket(month.toString(), month.format(MONTH_LABEL), money(revenue),
                    invoiceCount));
        }
        BigDecimal total = buckets.stream().map(ReportDtos.Bucket::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReportDtos.RevenueReport("month", buckets, money(total));
    }

    private BigDecimal totalOfDayBuckets(UUID tenantId, ZoneId zone, LocalDate from, LocalDate to) {
        return money(payments.sumBetween(tenantId, from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant()));
    }

    private List<ReportDtos.ServiceRevenue> topServices(UUID tenantId, LocalDate from, LocalDate to,
                                                        int limit) {
        List<Object[]> rows = invoices.revenueByService(tenantId, from, to, InvoiceStatus.VOID,
                PageRequest.of(0, limit));
        List<ReportDtos.ServiceRevenue> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new ReportDtos.ServiceRevenue((String) row[0], money((BigDecimal) row[1])));
        }
        return result;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }
}
