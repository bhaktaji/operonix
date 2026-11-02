package com.operonix.analytics;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.common.error.ApiException;
import com.operonix.common.money.MoneyCalculator;
import com.operonix.global.Currency;
import com.operonix.global.ReferenceDataService;
import com.operonix.security.CurrentUser;
import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Business intelligence (P2a).
 *
 * <p>Two guarantees shape every number here:
 *
 * <ol>
 *   <li><b>Reconciliation.</b> Aggregates come from {@code metric_daily}, which is always rebuilt
 *       from source records; drill-downs read those same source records. A dashboard figure and the
 *       rows behind it therefore agree by construction.</li>
 *   <li><b>No invented values.</b> When a metric needs an input the workspace has not supplied
 *       (labour rates, invoices, a currency conversion) it is reported as unavailable with a reason,
 *       never as zero.</li>
 * </ol>
 */
@Service
public class AnalyticsService {

    /** Rollups are rebuilt on read when older than this, so a dashboard is never badly stale. */
    private static final Duration FRESH_FOR = Duration.ofMinutes(5);
    private static final int MAX_RANGE_DAYS = 731;

    private final AnalyticsRollupRepository rollups;
    private final AnalyticsQueryRepository queries;
    private final TenantService tenants;
    private final ReferenceDataService referenceData;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final Clock clock;

    public AnalyticsService(AnalyticsRollupRepository rollups, AnalyticsQueryRepository queries,
                            TenantService tenants, ReferenceDataService referenceData,
                            CurrentUser currentUser, AuditService audit, Clock clock) {
        this.rollups = rollups;
        this.queries = queries;
        this.tenants = tenants;
        this.referenceData = referenceData;
        this.currentUser = currentUser;
        this.audit = audit;
        this.clock = clock;
    }

    public record Scope(UUID tenantId, String currency, int scale, LocalDate today) {

        BigDecimal round(BigDecimal value) {
            return MoneyCalculator.money(value, scale);
        }

        BigDecimal zero() {
            return MoneyCalculator.zero(scale);
        }
    }

    public Scope scope() {
        Tenant tenant = tenants.require(currentUser.tenantId());
        Currency currency = referenceData.requireCurrency(tenant.getCurrency());
        return new Scope(tenant.getId(), currency.getCode(), currency.getMinorUnits(),
                LocalDate.now(clock));
    }

    // ================================================================ catalogue

    public List<AnalyticsDtos.MetricInfo> catalogue() {
        return MetricDefinition.all().stream().map(AnalyticsDtos.MetricInfo::from).toList();
    }

    // ================================================================ overview

    @Transactional
    public AnalyticsDtos.Overview overview(LocalDate from, LocalDate to, UUID locationId) {
        Scope scope = scope();
        Range range = range(from, to, scope);
        ensureFresh(scope, range);
        String locationKey = locationId == null ? null : locationId.toString();

        Map<String, BigDecimal> current = rollups.totals(scope.tenantId(),
                MetricDefinition.ROLLED_UP, range.from(), range.to(), locationKey);
        Map<String, BigDecimal> previous = rollups.totals(scope.tenantId(),
                MetricDefinition.ROLLED_UP, range.previousFrom(), range.previousTo(), locationKey);

        List<AnalyticsDtos.JobRow> jobs = jobRows(scope, range, locationId);
        long incompleteJobs = jobs.stream().filter(job -> !job.complete()).count();
        List<AnalyticsDtos.JobRow> completeJobs = jobs.stream().filter(AnalyticsDtos.JobRow::complete).toList();
        BigDecimal jobRevenue = sum(completeJobs, AnalyticsDtos.JobRow::revenue);
        BigDecimal jobCost = sum(completeJobs, AnalyticsDtos.JobRow::cost);
        BigDecimal jobProfit = jobRevenue.subtract(jobCost);

        BigDecimal[] balances = queries.outstanding(scope.tenantId(), scope.currency(), range.to());

        List<AnalyticsDtos.MetricValue> metrics = new ArrayList<>();
        for (String key : MetricDefinition.ROLLED_UP) {
            metrics.add(value(key, scope.round(current.getOrDefault(key, scope.zero())),
                    scope.round(previous.getOrDefault(key, scope.zero())), true, null));
        }

        boolean profitAvailable = incompleteJobs == 0 && !completeJobs.isEmpty();
        metrics.add(value(MetricDefinition.JOB_PROFIT, profitAvailable ? scope.round(jobProfit) : null,
                null, profitAvailable,
                profitAvailable ? null
                        : incompleteJobs > 0
                        ? incompleteJobs + " delivered job(s) have incomplete cost inputs"
                        : "No delivered jobs with both revenue and cost in this period"));
        metrics.add(value(MetricDefinition.OUTSTANDING_RECEIVABLE, scope.round(balances[0]), null, true, null));
        metrics.add(value(MetricDefinition.OVERDUE_AMOUNT, scope.round(balances[1]), null, true, null));

        BigDecimal margin = null;
        if (profitAvailable) {
            BigDecimal withRevenue = completeJobs.stream().filter(job -> job.revenue().signum() > 0)
                    .map(job -> job.revenue())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (withRevenue.signum() > 0) {
                margin = jobProfit.multiply(BigDecimal.valueOf(100))
                        .divide(withRevenue, 1, RoundingMode.HALF_UP);
            }
        }
        boolean marginAvailable = profitAvailable && margin != null;
        metrics.add(value(MetricDefinition.AVG_JOB_MARGIN_PERCENT, margin, null, marginAvailable,
                marginAvailable ? null : "Needs delivered jobs with invoiced revenue and complete cost"));

        BigDecimal repeat = queries.repeatCustomerRevenue(scope.tenantId(), scope.currency(),
                range.from(), range.to());
        metrics.add(value(MetricDefinition.REPEAT_CUSTOMER_REVENUE, scope.round(repeat), null, true, null));

        List<String> notes = new ArrayList<>();
        notes.add("All amounts are in " + scope.currency()
                + ", converted at the exchange rate frozen on each document.");
        notes.add("Direct job profit excludes business overheads; overhead spend is included in Expenses.");
        if (incompleteJobs > 0) {
            notes.add(incompleteJobs + " delivered job(s) are excluded from job profit because a cost"
                    + " input is missing.");
        }
        notes.add("Outstanding and overdue are point-in-time balances at " + range.to() + ".");

        return new AnalyticsDtos.Overview(range.from(), range.to(), range.previousFrom(),
                range.previousTo(), scope.currency(), true, metrics, notes,
                rollups.status(scope.tenantId()).map(AnalyticsDtos.RefreshStatus::refreshedAt).orElse(null));
    }

    // ================================================================ trend

    @Transactional
    public AnalyticsDtos.Trend trend(String metricKey, LocalDate from, LocalDate to, String granularity,
                                     UUID locationId) {
        MetricDefinition.Metric metric = MetricDefinition.require(metricKey);
        Scope scope = scope();
        Range range = range(from, to, scope);
        String bucket = granularity == null || granularity.isBlank() ? "day" : granularity.toLowerCase();
        if (!List.of("day", "week", "month").contains(bucket)) {
            throw ApiException.validation("granularity", "must be day, week or month");
        }
        if (!metric.rolledUp()) {
            return new AnalyticsDtos.Trend(metric.key(), metric.label(), metric.unit().name(),
                    scope.currency(), bucket, List.of(), false,
                    "This metric is not stored as a daily series. Use the overview or a drill-down.");
        }
        ensureFresh(scope, range);
        List<AnalyticsDtos.TrendPoint> daily = rollups.dailySeries(scope.tenantId(), metric.key(),
                range.from(), range.to(), locationId == null ? null : locationId.toString());

        Map<LocalDate, BigDecimal> grouped = new LinkedHashMap<>();
        for (AnalyticsDtos.TrendPoint point : daily) {
            LocalDate key = switch (bucket) {
                case "week" -> point.bucket().with(java.time.DayOfWeek.MONDAY);
                case "month" -> point.bucket().withDayOfMonth(1);
                default -> point.bucket();
            };
            grouped.merge(key, point.value() == null ? BigDecimal.ZERO : point.value(), BigDecimal::add);
        }
        List<AnalyticsDtos.TrendPoint> points = grouped.entrySet().stream()
                .map(entry -> new AnalyticsDtos.TrendPoint(entry.getKey(),
                        metric.unit() == MetricDefinition.Unit.COUNT
                                || metric.unit() == MetricDefinition.Unit.MINUTES
                                ? entry.getValue().setScale(0, RoundingMode.HALF_UP)
                                : scope.round(entry.getValue())))
                .toList();
        return new AnalyticsDtos.Trend(metric.key(), metric.label(), metric.unit().name(),
                scope.currency(), bucket, points, true, null);
    }

    // ================================================================ breakdowns

    @Transactional
    public AnalyticsDtos.Breakdown breakdown(String dimension, LocalDate from, LocalDate to) {
        Scope scope = scope();
        Range range = range(from, to, scope);
        ensureFresh(scope, range);
        String dim = dimension == null ? "LOCATION" : dimension.toUpperCase(java.util.Locale.ROOT);
        List<AnalyticsDtos.BreakdownRow> rows;
        List<String> notes = new ArrayList<>();
        switch (dim) {
            case "SERVICE" -> {
                rows = queries.byService(scope.tenantId(), scope.currency(), range.from(), range.to());
                notes.add("Direct cost is captured per job, not per line, so profit is not attributed"
                        + " to individual services.");
            }
            case "CUSTOMER" -> {
                rows = queries.byCustomer(scope.tenantId(), scope.currency(), range.from(), range.to());
                notes.add("Job counts include only invoiced jobs; unpaid work is not revenue.");
            }
            case "TECHNICIAN" -> {
                rows = queries.byTechnician(scope.tenantId(), range.from(), range.to());
                notes.add("Labour cost uses each person's configured hourly cost. Zero means the rate"
                        + " has not been set, so their cost is understated.");
            }
            case "LOCATION" -> {
                List<AnalyticsDtos.JobRow> jobs = jobRows(scope, range, null);
                boolean profitAvailable = jobs.stream().allMatch(AnalyticsDtos.JobRow::complete);
                rows = queries.byLocation(scope.tenantId(), range.from(), range.to(), profitAvailable);
                if (!profitAvailable) {
                    notes.add("Profit is hidden for locations where at least one job has incomplete"
                            + " cost inputs.");
                }
            }
            default -> throw ApiException.validation("dimension",
                    "must be LOCATION, SERVICE, CUSTOMER or TECHNICIAN");
        }
        return new AnalyticsDtos.Breakdown(dim, scope.currency(), rows, notes);
    }

    /** Business locations offered as an analytics filter. */
    @Transactional(readOnly = true)
    public List<AnalyticsDtos.BreakdownRow> locations() {
        return rollups.locations(currentUser.tenantId());
    }

    // ================================================================ ageing

    @Transactional
    public AnalyticsDtos.Ageing ageing() {
        Scope scope = scope();
        LocalDate asOf = scope.today();
        List<AnalyticsDtos.AgeingBucket> buckets = new ArrayList<>(
                queries.ageingBuckets(scope.tenantId(), scope.currency(), asOf));
        buckets.sort((left, right) -> Integer.compare(bucketOrder(left.bucket()),
                bucketOrder(right.bucket())));
        BigDecimal[] balances = queries.outstanding(scope.tenantId(), scope.currency(), asOf);
        return new AnalyticsDtos.Ageing(scope.currency(), asOf, buckets,
                scope.round(balances[0]), scope.round(balances[1]),
                queries.openInvoices(scope.tenantId(), scope.currency(), asOf),
                List.of("Ageing is measured from each invoice's due date. Amounts are in "
                        + scope.currency() + "."));
    }

    private static int bucketOrder(String bucket) {
        return switch (bucket) {
            case "current" -> 0;
            case "1-30" -> 1;
            case "31-60" -> 2;
            case "61-90" -> 3;
            default -> 4;
        };
    }

    // ================================================================ job profit

    @Transactional
    public AnalyticsDtos.JobProfitability jobProfitability(LocalDate from, LocalDate to, UUID locationId) {
        Scope scope = scope();
        Range range = range(from, to, scope);
        List<AnalyticsDtos.JobRow> rows = jobRows(scope, range, locationId);
        long incomplete = rows.stream().filter(job -> !job.complete()).count();
        List<AnalyticsDtos.JobRow> complete = rows.stream().filter(AnalyticsDtos.JobRow::complete).toList();
        BigDecimal revenue = sum(complete, AnalyticsDtos.JobRow::revenue);
        BigDecimal cost = sum(complete, AnalyticsDtos.JobRow::cost);
        List<String> notes = new ArrayList<>();
        notes.add("Revenue is invoiced value; cost is direct job cost. Overheads are excluded.");
        if (incomplete > 0) {
            notes.add(incomplete + " job(s) are excluded from the totals because a cost input is missing.");
        }
        return new AnalyticsDtos.JobProfitability(scope.currency(), rows, (int) incomplete,
                scope.round(revenue), scope.round(cost), scope.round(revenue.subtract(cost)), notes);
    }

    private List<AnalyticsDtos.JobRow> jobRows(Scope scope, Range range, UUID locationId) {
        List<AnalyticsDtos.JobRow> rows = new ArrayList<>();
        for (Object[] row : queries.jobRows(scope.tenantId(), scope.currency(), range.from(),
                range.to(), locationId)) {
            BigDecimal revenue = (BigDecimal) row[5];
            BigDecimal cost = (BigDecimal) row[6];
            long invoiceCount = (Long) row[7];
            long openLogs = (Long) row[8];
            long unpriced = (Long) row[9];
            List<String> reasons = new ArrayList<>();
            if (invoiceCount == 0) {
                reasons.add("No invoice raised");
            }
            if (openLogs > 0) {
                reasons.add(openLogs + " time entr(y/ies) still running");
            }
            if (unpriced > 0) {
                reasons.add(unpriced + " time entr(y/ies) belong to someone with no hourly cost");
            }
            boolean complete = reasons.isEmpty();
            BigDecimal profit = complete ? revenue.subtract(cost) : null;
            BigDecimal margin = complete && revenue.signum() > 0
                    ? profit.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, RoundingMode.HALF_UP)
                    : null;
            rows.add(new AnalyticsDtos.JobRow((UUID) row[0], (String) row[1], (String) row[2],
                    (String) row[3], (LocalDate) row[4], scope.round(revenue), scope.round(cost),
                    profit == null ? null : scope.round(profit), margin, complete, reasons));
        }
        return rows;
    }

    // ================================================================ refresh

    @Transactional
    public AnalyticsDtos.RefreshStatus refresh(LocalDate from, LocalDate to) {
        Scope scope = scope();
        Range range = range(from, to, scope);
        AnalyticsDtos.RefreshStatus status = rebuild(scope, range);
        audit.record(AuditAction.ANALYTICS_REFRESHED, "metric_daily", null,
                Map.of("from", range.from().toString(), "to", range.to().toString(),
                        "rows", status.rowCount()));
        return status;
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.RefreshStatus status() {
        Scope scope = scope();
        return rollups.status(scope.tenantId()).orElse(null);
    }

    private AnalyticsDtos.RefreshStatus rebuild(Scope scope, Range range) {
        int rows = rollups.rebuild(scope.tenantId(), scope.currency(), range.from(), range.to());
        return new AnalyticsDtos.RefreshStatus(java.time.Instant.now(clock), range.from(), range.to(),
                rows);
    }

    /**
     * Rebuilds the rollup when it is missing, stale, does not cover the requested range, or the
     * workspace has been written to since the last refresh. The last condition is what stops a
     * freshly issued invoice from being invisible in Analytics while it shows up everywhere else.
     */
    private void ensureFresh(Scope scope, Range range) {
        java.time.Instant cutoff = java.time.Instant.now(clock).minus(FRESH_FOR);
        java.time.Instant newestWrite = rollups.latestSourceChange(scope.tenantId()).orElse(null);
        boolean stale = rollups.status(scope.tenantId())
                .map(status -> status.refreshedAt().isBefore(cutoff)
                        || status.rangeStart().isAfter(range.from())
                        || status.rangeEnd().isBefore(range.to())
                        || (newestWrite != null && newestWrite.isAfter(status.refreshedAt())))
                .orElse(true);
        if (stale) {
            LocalDate start = rollups.status(scope.tenantId())
                    .map(status -> status.rangeStart().isBefore(range.from())
                            ? status.rangeStart() : range.from())
                    .orElse(range.from());
            rollups.rebuild(scope.tenantId(), scope.currency(), start, range.to());
        }
    }

    // ================================================================ helpers

    private record Range(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo) {
    }

    private Range range(LocalDate from, LocalDate to, Scope scope) {
        LocalDate end = to == null ? scope.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw ApiException.validation("from", "must not be after 'to'");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw ApiException.validation("from", "the range must be two years or less");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        return new Range(start, end, start.minusDays(days), start.minusDays(1));
    }

    private AnalyticsDtos.MetricValue value(String key, BigDecimal value, BigDecimal previous,
                                            boolean available, String reason) {
        MetricDefinition.Metric metric = MetricDefinition.require(key);
        BigDecimal change = null;
        if (available && previous != null && previous.signum() != 0 && value != null) {
            change = value.subtract(previous).multiply(BigDecimal.valueOf(100))
                    .divide(previous.abs(), 1, RoundingMode.HALF_UP);
        }
        return new AnalyticsDtos.MetricValue(metric.key(), metric.label(), metric.unit().name(),
                available ? value : null, previous, change, available, reason);
    }

    private static BigDecimal sum(List<AnalyticsDtos.JobRow> rows,
                                  java.util.function.Function<AnalyticsDtos.JobRow, BigDecimal> part) {
        return rows.stream().map(part).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Distinct set of metric keys the AI layer is allowed to request. */
    public List<String> metricKeys() {
        return MetricDefinition.all().stream().map(MetricDefinition.Metric::key).toList();
    }

    /** Kept for the scheduler: refresh every workspace over the trailing year. */
    @Transactional
    public int refreshWorkspace(UUID tenantId) {
        Tenant tenant = tenants.require(tenantId);
        Currency currency = referenceData.requireCurrency(tenant.getCurrency());
        LocalDate end = LocalDate.now(clock);
        LocalDate start = end.minusDays(400);
        return rollups.rebuild(tenantId, currency.getCode(), start, end);
    }

}
