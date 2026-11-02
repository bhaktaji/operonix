package com.operonix.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains and reads the {@code metric_daily} rollup.
 *
 * <p>Every refresh deletes the requested day range for the tenant and rebuilds it from source
 * tables, so a refresh is idempotent and the rollup can never drift into a double count. Each metric
 * is written twice: once per business location and once as the TOTAL row for that day. TOTAL is the
 * sum of the location rows (including the "unassigned" bucket), which keeps the two levels adding up.
 */
@Repository
public class AnalyticsRollupRepository {

    private static final String UNASSIGNED = "";

    private final JdbcTemplate jdbc;

    public AnalyticsRollupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Cell(LocalDate day, String locationKey, UUID locationId, String label,
                        BigDecimal value) {
    }

    /** What the refresh state table says, if this workspace has ever been refreshed. */
    public Optional<AnalyticsDtos.RefreshStatus> status(UUID tenantId) {
        return jdbc.query("""
                        select last_refreshed_at, range_start, range_end, row_count
                        from analytics_refresh where tenant_id = ?
                        """,
                (rs, rowNum) -> new AnalyticsDtos.RefreshStatus(
                        rs.getTimestamp("last_refreshed_at").toInstant(),
                        rs.getDate("range_start").toLocalDate(),
                        rs.getDate("range_end").toLocalDate(),
                        rs.getLong("row_count")),
                tenantId).stream().findFirst();
    }

    /**
     * The newest write to any source row this rollup is derived from.
     *
     * <p>Time-based staleness alone is not enough: an invoice issued a minute after the last refresh
     * leaves the rollup "fresh" while the revenue it reports is already wrong. Comparing the refresh
     * timestamp with the newest source write makes a changed workspace rebuild immediately, and a
     * workspace that has not changed since reads straight from the rollup.
     */
    public Optional<java.time.Instant> latestSourceChange(UUID tenantId) {
        Timestamp latest = jdbc.queryForObject("""
                select greatest(
                    coalesce((select max(updated_at) from invoice where tenant_id = ?), to_timestamp(0)),
                    coalesce((select max(created_at) from payment where tenant_id = ?), to_timestamp(0)),
                    coalesce((select max(updated_at) from job where tenant_id = ?), to_timestamp(0)),
                    coalesce((select max(computed_at) from job_cost where tenant_id = ?), to_timestamp(0)),
                    coalesce((select max(updated_at) from work_log where tenant_id = ?), to_timestamp(0)),
                    coalesce((select max(updated_at) from expense where tenant_id = ?), to_timestamp(0))
                )
                """, Timestamp.class, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId);
        return latest == null ? Optional.empty() : Optional.of(latest.toInstant());
    }

    /**
     * Rebuilds every rolled-up metric for the given day range.
     *
     * @return number of metric_daily rows written
     */
    public int rebuild(UUID tenantId, String workspaceCurrency, LocalDate from, LocalDate to) {
        // An analytics page issues several calls at once (overview, trend, breakdown) and each may
        // trigger a refresh. Serialise rebuilds per workspace: otherwise two refreshes interleave
        // their delete/insert over the same rows and one of them fails on uq_metric_daily, turning a
        // read into a 409. The transaction-scoped lock is released when the caller commits.
        Boolean locked = jdbc.queryForObject(
                "select pg_try_advisory_xact_lock(hashtext('operonix.analytics'), hashtext(?))",
                Boolean.class, tenantId.toString());
        if (!Boolean.TRUE.equals(locked)) {
            // Another request is already rebuilding this workspace; its rows are about to be visible.
            return 0;
        }

        jdbc.update("delete from metric_daily where tenant_id = ? and day between ? and ?",
                tenantId, Date.valueOf(from), Date.valueOf(to));

        List<Cell> revenue = cells(tenantId, workspaceCurrency, from, to, REVENUE_SQL, true);
        List<Cell> payments = cells(tenantId, workspaceCurrency, from, to, PAYMENTS_SQL, true);
        List<Cell> invoices = cells(tenantId, workspaceCurrency, from, to, INVOICES_SQL, false);
        List<Cell> jobs = cells(tenantId, workspaceCurrency, from, to, JOBS_SQL, false);
        List<Cell> jobCost = cells(tenantId, workspaceCurrency, from, to, JOB_COST_SQL, false);
        List<Cell> labour = cells(tenantId, workspaceCurrency, from, to, LABOUR_SQL, false);
        List<Cell> expenses = cells(tenantId, workspaceCurrency, from, to, EXPENSE_SQL, false);

        List<Object[]> rows = new ArrayList<>();
        int written = 0;
        written += write(rows, tenantId, MetricDefinition.REVENUE, workspaceCurrency, revenue);
        written += write(rows, tenantId, MetricDefinition.PAYMENTS_COLLECTED, workspaceCurrency, payments);
        written += write(rows, tenantId, MetricDefinition.INVOICES_ISSUED, workspaceCurrency, invoices);
        written += write(rows, tenantId, MetricDefinition.JOBS_COMPLETED, workspaceCurrency, jobs);
        written += write(rows, tenantId, MetricDefinition.JOB_COST, workspaceCurrency, jobCost);
        written += write(rows, tenantId, MetricDefinition.LABOUR_MINUTES, workspaceCurrency, labour);
        written += write(rows, tenantId, MetricDefinition.EXPENSE_TOTAL, workspaceCurrency, expenses);

        Timestamp now = Timestamp.from(java.time.Instant.now());
        // Upsert as well as the lock: the insert must never be able to fail a read path, and a
        // concurrent writer can only ever write the same value computed from the same source rows.
        jdbc.batchUpdate("""
                insert into metric_daily (id, tenant_id, metric_key, dimension_type, dimension_key,
                                          dimension_id, dimension_label, day, currency, value,
                                          created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                on conflict (tenant_id, metric_key, dimension_type, dimension_key, day) do update
                   set dimension_id = excluded.dimension_id,
                       dimension_label = excluded.dimension_label,
                       currency = excluded.currency,
                       value = excluded.value,
                       updated_at = excluded.updated_at,
                       version = metric_daily.version + 1
                """, rows);

        jdbc.update("""
                insert into analytics_refresh (tenant_id, last_refreshed_at, range_start, range_end,
                                               row_count, updated_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (tenant_id) do update
                   set last_refreshed_at = excluded.last_refreshed_at,
                       range_start = excluded.range_start,
                       range_end = excluded.range_end,
                       row_count = excluded.row_count,
                       updated_at = excluded.updated_at
                """, tenantId, now, Date.valueOf(from), Date.valueOf(to), written, now);
        return written;
    }

    /** Adds the LOCATION rows and the matching TOTAL row for one metric. */
    private int write(List<Object[]> rows, UUID tenantId, String metricKey, String currency,
                      List<Cell> cells) {
        if (cells.isEmpty()) {
            return 0;
        }
        // De-duplicate by (day, location): a metric may produce several rows for the same cell
        // (e.g. two invoices for the same job on the same day).
        Map<String, Cell> merged = new LinkedHashMap<>();
        for (Cell cell : cells) {
            String key = cell.day() + "|" + cell.locationKey();
            merged.merge(key, cell, (left, right) -> new Cell(left.day(), left.locationKey(),
                    left.locationId(), left.label(), left.value().add(right.value())));
        }

        Map<LocalDate, BigDecimal> totals = new LinkedHashMap<>();
        for (Cell cell : merged.values()) {
            rows.add(row(tenantId, metricKey, "LOCATION", cell.locationKey(), cell.locationId(),
                    cell.label(), cell.day(), currency, cell.value()));
            totals.merge(cell.day(), cell.value(), BigDecimal::add);
        }
        for (Map.Entry<LocalDate, BigDecimal> total : totals.entrySet()) {
            rows.add(row(tenantId, metricKey, "TOTAL", "", null, null, total.getKey(), currency,
                    total.getValue()));
        }
        return merged.size() + totals.size();
    }

    private Object[] row(UUID tenantId, String metricKey, String dimensionType, String dimensionKey,
                         UUID dimensionId, String label, LocalDate day, String currency,
                         BigDecimal value) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        return new Object[]{UUID.randomUUID(), tenantId, metricKey, dimensionType, dimensionKey,
                dimensionId, label, Date.valueOf(day), currency, value, now, now};
    }

    private List<Cell> cells(UUID tenantId, String currency, LocalDate from, LocalDate to, String sql,
                             boolean usesCurrency) {
        // The currency-aware metrics open with two currency placeholders (document currency, then
        // the workspace base currency) before the tenant and date bounds.
        Object[] params = usesCurrency
                ? new Object[]{currency, currency, tenantId, Date.valueOf(from), Date.valueOf(to)}
                : new Object[]{tenantId, Date.valueOf(from), Date.valueOf(to)};
        return jdbc.query(sql, (rs, rowNum) -> new Cell(
                rs.getDate("day").toLocalDate(),
                rs.getString("location_key") == null ? UNASSIGNED : rs.getString("location_key"),
                rs.getObject("location_id", UUID.class),
                rs.getString("label"),
                rs.getBigDecimal("value") == null ? BigDecimal.ZERO : rs.getBigDecimal("value")),
                params);
    }

    // ------------------------------------------------------------------ rollup reads

    /** TOTAL value per metric for a range, keyed by metric. */
    public Map<String, BigDecimal> totals(UUID tenantId, List<String> metrics, LocalDate from,
                                          LocalDate to, String locationKey) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String metric : metrics) {
            BigDecimal value = jdbc.queryForObject("""
                    select coalesce(sum(value), 0) from metric_daily
                    where tenant_id = ? and metric_key = ? and dimension_type = ?
                      and dimension_key = ? and day between ? and ?
                    """, BigDecimal.class, tenantId, metric,
                    locationKey == null ? "TOTAL" : "LOCATION",
                    locationKey == null ? "" : locationKey,
                    Date.valueOf(from), Date.valueOf(to));
            result.put(metric, value == null ? BigDecimal.ZERO : value);
        }
        return result;
    }

    /** Daily series for one metric, already summed across whichever dimension is requested. */
    public List<AnalyticsDtos.TrendPoint> dailySeries(UUID tenantId, String metric, LocalDate from,
                                                      LocalDate to, String locationKey) {
        return jdbc.query("""
                        select day, sum(value) as value from metric_daily
                        where tenant_id = ? and metric_key = ? and dimension_type = ?
                          and dimension_key = ? and day between ? and ?
                        group by day order by day
                        """,
                (rs, rowNum) -> new AnalyticsDtos.TrendPoint(rs.getDate("day").toLocalDate(),
                        rs.getBigDecimal("value")),
                tenantId, metric, locationKey == null ? "TOTAL" : "LOCATION",
                locationKey == null ? "" : locationKey, Date.valueOf(from), Date.valueOf(to));
    }

    /** Business locations available as an analytics filter. */
    public List<AnalyticsDtos.BreakdownRow> locations(UUID tenantId) {
        return jdbc.query("""
                        select id, name from business_location
                        where tenant_id = ? and active order by is_default desc, name
                        """,
                (rs, rowNum) -> new AnalyticsDtos.BreakdownRow(rs.getString("name"),
                        rs.getObject("id", UUID.class),
                        Map.of("locationId", rs.getString("id")),
                        null, null, null, null, null, null, null, false),
                tenantId);
    }

    // ------------------------------------------------------------------ metric SQL
    //
    // Revenue, payments and costs must all land in the workspace currency. A document already in that
    // currency contributes its own total; one in another currency contributes its frozen base value;
    // anything else contributes zero and is excluded, never guessed at a current rate.

    private static final String REVENUE_SQL = """
            select i.issue_date as day,
                   coalesce(j.location_id::text, '') as location_key,
                   j.location_id as location_id,
                   bl.name as label,
                   sum(case when i.currency = ? then i.total - i.tax_amount
                            when i.base_currency = ? then i.base_total - (i.tax_amount * i.fx_rate)
                            else 0 end) as value
            from invoice i
            left join job j on j.id = i.job_id and j.tenant_id = i.tenant_id
            left join business_location bl on bl.id = j.location_id
            where i.tenant_id = ? and i.status <> 'VOID'
              and i.issue_date between ? and ?
            group by 1, 2, 3, 4
            """;

    private static final String PAYMENTS_SQL = """
            select (p.paid_at at time zone 'UTC')::date as day,
                   coalesce(j.location_id::text, '') as location_key,
                   j.location_id as location_id,
                   bl.name as label,
                   sum(case when i.currency = ? then p.amount
                            when i.base_currency = ? then p.amount * i.fx_rate
                            else 0 end) as value
            from payment p
            join invoice i on i.id = p.invoice_id and i.tenant_id = p.tenant_id
            left join job j on j.id = i.job_id and j.tenant_id = i.tenant_id
            left join business_location bl on bl.id = j.location_id
            where p.tenant_id = ? and p.reversed_at is null
              and (p.paid_at at time zone 'UTC')::date between ? and ?
            group by 1, 2, 3, 4
            """;

    private static final String INVOICES_SQL = """
            select i.issue_date as day,
                   coalesce(j.location_id::text, '') as location_key,
                   j.location_id as location_id,
                   bl.name as label,
                   count(*) as value
            from invoice i
            left join job j on j.id = i.job_id and j.tenant_id = i.tenant_id
            left join business_location bl on bl.id = j.location_id
            where i.tenant_id = ? and i.status <> 'VOID'
              and i.issue_date between ? and ?
            group by 1, 2, 3, 4
            """;

    private static final String JOBS_SQL = """
            select (coalesce(j.actual_end, j.scheduled_end) at time zone 'UTC')::date as day,
                   coalesce(j.location_id::text, '') as location_key,
                   j.location_id as location_id,
                   bl.name as label,
                   count(*) as value
            from job j
            left join business_location bl on bl.id = j.location_id
            where j.tenant_id = ? and j.status = 'COMPLETED'
              and (coalesce(j.actual_end, j.scheduled_end) at time zone 'UTC')::date between ? and ?
            group by 1, 2, 3, 4
            """;

    private static final String JOB_COST_SQL = """
            select (coalesce(j.actual_end, j.scheduled_end) at time zone 'UTC')::date as day,
                   coalesce(j.location_id::text, '') as location_key,
                   j.location_id as location_id,
                   bl.name as label,
                   sum(c.total_cost) as value
            from job j
            join job_cost c on c.job_id = j.id and c.tenant_id = j.tenant_id
            left join business_location bl on bl.id = j.location_id
            where j.tenant_id = ? and j.status = 'COMPLETED'
              and (coalesce(j.actual_end, j.scheduled_end) at time zone 'UTC')::date between ? and ?
            group by 1, 2, 3, 4
            """;

    private static final String LABOUR_SQL = """
            select (w.start_at at time zone 'UTC')::date as day,
                   coalesce(j.location_id::text, '') as location_key,
                   j.location_id as location_id,
                   bl.name as label,
                   sum(extract(epoch from (w.end_at - w.start_at)) / 60) as value
            from work_log w
            join job j on j.id = w.job_id and j.tenant_id = w.tenant_id
            left join business_location bl on bl.id = j.location_id
            where w.tenant_id = ? and w.end_at is not null
              and (w.start_at at time zone 'UTC')::date between ? and ?
            group by 1, 2, 3, 4
            """;

    private static final String EXPENSE_SQL = """
            select e.expense_date as day,
                   coalesce(j.location_id::text, '') as location_key,
                   j.location_id as location_id,
                   bl.name as label,
                   sum(e.workspace_amount) as value
            from expense e
            left join job j on j.id = e.job_id and j.tenant_id = e.tenant_id
            left join business_location bl on bl.id = j.location_id
            where e.tenant_id = ? and e.expense_date between ? and ?
            group by 1, 2, 3, 4
            """;
}
