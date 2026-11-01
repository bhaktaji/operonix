package com.operonix.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live drill-down queries.
 *
 * <p>Aggregates come from the rollup, but every drill-down reads the source records, so clicking a
 * number always lands on rows that add back up to it.
 */
@Repository
public class AnalyticsQueryRepository {

    private final JdbcTemplate jdbc;

    public AnalyticsQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Balance still owed, and how much of it is past due, as at {@code asOf}. */
    public BigDecimal[] outstanding(UUID tenantId, String currency, LocalDate asOf) {
        BigDecimal outstanding = jdbc.queryForObject("""
                select coalesce(sum(case when currency = ? then total - amount_paid
                                         when base_currency = ? then (total - amount_paid) * fx_rate
                                         else 0 end), 0)
                from invoice
                where tenant_id = ? and status <> 'VOID' and issue_date <= ?
                """, BigDecimal.class, currency, currency, tenantId, Date.valueOf(asOf));
        BigDecimal overdue = jdbc.queryForObject("""
                select coalesce(sum(case when currency = ? then total - amount_paid
                                         when base_currency = ? then (total - amount_paid) * fx_rate
                                         else 0 end), 0)
                from invoice
                where tenant_id = ? and status <> 'VOID' and issue_date <= ?
                  and due_date < ? and total > amount_paid
                """, BigDecimal.class, currency, currency, tenantId, Date.valueOf(asOf),
                Date.valueOf(asOf));
        return new BigDecimal[]{nz(outstanding), nz(overdue)};
    }

    /** Open invoices bucketed by how long they have been due. */
    public List<AnalyticsDtos.AgeingBucket> ageingBuckets(UUID tenantId, String currency, LocalDate asOf) {
        return jdbc.query("""
                        select bucket, sum(amount) as amount, count(*) as invoices from (
                            select case
                                     when due_date >= ? then 'current'
                                     when ? - due_date <= 30 then '1-30'
                                     when ? - due_date <= 60 then '31-60'
                                     when ? - due_date <= 90 then '61-90'
                                     else '90+'
                                   end as bucket,
                                   case when i.currency = ? then i.total - i.amount_paid
                                        when i.base_currency = ? then (i.total - i.amount_paid) * i.fx_rate
                                        else 0 end as amount
                            from invoice i
                            where i.tenant_id = ? and i.status <> 'VOID'
                              and i.issue_date <= ? and i.total > i.amount_paid
                        ) t
                        group by bucket
                        """,
                (rs, rowNum) -> new AnalyticsDtos.AgeingBucket(rs.getString("bucket"),
                        nz(rs.getBigDecimal("amount")), rs.getLong("invoices")),
                Date.valueOf(asOf), Date.valueOf(asOf), Date.valueOf(asOf), Date.valueOf(asOf),
                currency, currency, tenantId, Date.valueOf(asOf));
    }

    public List<AnalyticsDtos.InvoiceRow> openInvoices(UUID tenantId, String currency, LocalDate asOf) {
        return jdbc.query("""
                        select i.id, i.number, i.customer_id, c.name as customer_name, i.issue_date,
                               i.due_date,
                               case when i.currency = ? then i.total - i.amount_paid
                                    when i.base_currency = ? then (i.total - i.amount_paid) * i.fx_rate
                                    else 0 end as balance
                        from invoice i
                        join customer c on c.id = i.customer_id
                        where i.tenant_id = ? and i.status <> 'VOID'
                          and i.issue_date <= ? and i.total > i.amount_paid
                        order by i.due_date asc
                        limit 100
                        """,
                (rs, rowNum) -> {
                    LocalDate due = rs.getDate("due_date").toLocalDate();
                    long overdueDays = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(due, asOf));
                    return new AnalyticsDtos.InvoiceRow(rs.getObject("id", UUID.class),
                            rs.getString("number"), rs.getString("customer_name"),
                            rs.getDate("issue_date").toLocalDate(), due,
                            nz(rs.getBigDecimal("balance")), overdueDays);
                },
                currency, currency, tenantId, Date.valueOf(asOf));
    }

    /** Revenue grouped by catalogue service, with the drill-down filter for each row. */
    public List<AnalyticsDtos.BreakdownRow> byService(UUID tenantId, String currency, LocalDate from,
                                                      LocalDate to) {
        return jdbc.query("""
                        select coalesce(s.name, il.description) as label, s.id as service_id,
                               sum(case when i.currency = ? then il.line_subtotal
                                        when i.base_currency = ? then il.line_subtotal * i.fx_rate
                                        else 0 end) as revenue,
                               count(distinct i.id) as invoices,
                               sum(il.quantity) as quantity
                        from invoice i
                        join invoice_line il on il.invoice_id = i.id
                        left join service_item s on s.id = il.service_item_id
                        where i.tenant_id = ? and i.status <> 'VOID' and i.issue_date between ? and ?
                        group by 1, 2
                        order by revenue desc
                        limit 25
                        """,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("service_id", UUID.class);
                    return new AnalyticsDtos.BreakdownRow(rs.getString("label"), id,
                            id == null ? Map.of("description", rs.getString("label"))
                                    : Map.of("serviceItemId", id.toString()),
                            nz(rs.getBigDecimal("revenue")), null, null, null, null, null,
                            Math.round(rs.getDouble("quantity")), false);
                },
                currency, currency, tenantId, Date.valueOf(from), Date.valueOf(to));
    }

    /** Revenue, invoice and job counts per customer, for the period. */
    public List<AnalyticsDtos.BreakdownRow> byCustomer(UUID tenantId, String currency, LocalDate from,
                                                       LocalDate to) {
        return jdbc.query("""
                        select c.id, c.name,
                               sum(case when i.currency = ? then i.total
                                        when i.base_currency = ? then i.base_total else 0 end) as revenue,
                               count(distinct i.id) as invoices,
                               count(distinct i.job_id) as jobs
                        from invoice i
                        join customer c on c.id = i.customer_id
                        where i.tenant_id = ? and i.status <> 'VOID' and i.issue_date between ? and ?
                        group by 1, 2
                        order by revenue desc
                        limit 25
                        """,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new AnalyticsDtos.BreakdownRow(rs.getString("name"), id,
                            Map.of("customerId", id.toString()),
                            nz(rs.getBigDecimal("revenue")), null, null, null, null,
                            rs.getLong("jobs"), rs.getLong("invoices"), false);
                },
                currency, currency, tenantId, Date.valueOf(from), Date.valueOf(to));
    }

    /** Time and labour cost per team member. */
    public List<AnalyticsDtos.BreakdownRow> byTechnician(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                        select u.id, u.full_name,
                               count(distinct w.job_id) as jobs,
                               sum(extract(epoch from (w.end_at - w.start_at)) / 60) as minutes,
                               sum(coalesce(u.hourly_cost, 0)
                                   * extract(epoch from (w.end_at - w.start_at)) / 3600) as labour_cost
                        from work_log w
                        join app_user u on u.id = w.app_user_id
                        where w.tenant_id = ? and w.end_at is not null
                          and (w.start_at at time zone 'UTC')::date between ? and ?
                        group by 1, 2
                        order by minutes desc
                        limit 25
                        """,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new AnalyticsDtos.BreakdownRow(rs.getString("full_name"), id,
                            Map.of("technicianId", id.toString()),
                            null, nz(rs.getBigDecimal("labour_cost")), null, null, null,
                            Math.round(rs.getDouble("minutes")), rs.getLong("jobs"), false);
                },
                tenantId, Date.valueOf(from), Date.valueOf(to));
    }

    /** Location breakdown straight from the LOCATION rollup rows. */
    public List<AnalyticsDtos.BreakdownRow> byLocation(UUID tenantId, LocalDate from, LocalDate to,
                                                       boolean profitAvailable) {
        return jdbc.query("""
                        select dimension_key, dimension_label,
                               coalesce(sum(case when metric_key = 'revenue' then value end), 0) as revenue,
                               coalesce(sum(case when metric_key = 'job_cost' then value end), 0) as cost,
                               coalesce(sum(case when metric_key = 'jobs_completed' then value end), 0) as jobs
                        from metric_daily
                        where tenant_id = ? and dimension_type = 'LOCATION' and day between ? and ?
                        group by 1, 2
                        order by revenue desc
                        """,
                (rs, rowNum) -> {
                    String key = rs.getString("dimension_key");
                    String label = rs.getString("dimension_label");
                    BigDecimal revenue = nz(rs.getBigDecimal("revenue"));
                    BigDecimal cost = nz(rs.getBigDecimal("cost"));
                    BigDecimal profit = profitAvailable ? revenue.subtract(cost) : null;
                    return new AnalyticsDtos.BreakdownRow(
                            label == null || label.isBlank() ? "Unassigned" : label,
                            null,
                            key == null || key.isBlank() ? Map.of() : Map.of("locationId", key),
                            revenue, cost, profit, null, null, null,
                            rs.getLong("jobs"), profitAvailable);
                },
                tenantId, Date.valueOf(from), Date.valueOf(to));
    }

    /**
     * Delivered jobs with revenue, cost and completeness. A job is {@code complete} only when it has
     * at least one non-void invoice, no open time log and every person who logged time has a rate.
     */
    public List<Object[]> jobRows(UUID tenantId, String currency, LocalDate from, LocalDate to,
                                  UUID locationId) {
        return jdbc.query("""
                        select j.id, j.number, j.title, c.name as customer_name,
                               (coalesce(j.actual_end, j.scheduled_end) at time zone 'UTC')::date as delivered,
                               coalesce((select sum(case when i.currency = ? then i.total
                                                         when i.base_currency = ? then i.base_total
                                                         else 0 end)
                                           from invoice i
                                          where i.job_id = j.id and i.tenant_id = j.tenant_id
                                            and i.status <> 'VOID'), 0) as revenue,
                               coalesce(jc.total_cost, 0) as cost,
                               (select count(*) from invoice i
                                 where i.job_id = j.id and i.tenant_id = j.tenant_id
                                   and i.status <> 'VOID') as invoice_count,
                               (select count(*) from work_log w
                                 where w.job_id = j.id and w.end_at is null) as open_logs,
                               (select count(*) from work_log w
                                  join app_user u on u.id = w.app_user_id
                                 where w.job_id = j.id and coalesce(u.hourly_cost, 0) <= 0) as unpriced_logs
                        from job j
                        join customer c on c.id = j.customer_id
                        left join job_cost jc on jc.job_id = j.id
                        where j.tenant_id = ? and j.status = 'COMPLETED'
                          and (coalesce(j.actual_end, j.scheduled_end) at time zone 'UTC')::date between ? and ?
                          and (?::uuid is null or j.location_id = ?::uuid)
                        order by delivered desc, j.number desc
                        limit 200
                        """,
                (rs, rowNum) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getString("number"),
                        rs.getString("title"),
                        rs.getString("customer_name"),
                        rs.getDate("delivered").toLocalDate(),
                        nz(rs.getBigDecimal("revenue")),
                        nz(rs.getBigDecimal("cost")),
                        rs.getLong("invoice_count"),
                        rs.getLong("open_logs"),
                        rs.getLong("unpriced_logs")},
                currency, currency, tenantId, Date.valueOf(from), Date.valueOf(to),
                locationId == null ? null : locationId.toString(),
                locationId == null ? null : locationId.toString());
    }

    /** Revenue in the period from customers with more than one non-void invoice in the last year. */
    public BigDecimal repeatCustomerRevenue(UUID tenantId, String currency, LocalDate from,
                                            LocalDate to) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(case when i.currency = ? then i.total
                                         when i.base_currency = ? then i.base_total else 0 end), 0)
                from invoice i
                where i.tenant_id = ? and i.status <> 'VOID' and i.issue_date between ? and ?
                  and (select count(*) from invoice x
                        where x.tenant_id = i.tenant_id and x.customer_id = i.customer_id
                          and x.status <> 'VOID' and x.issue_date >= ? and x.issue_date <= ?) > 1
                """, BigDecimal.class, currency, currency, tenantId, Date.valueOf(from),
                Date.valueOf(to), Date.valueOf(to.minusYears(1)), Date.valueOf(to));
        return nz(value);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
