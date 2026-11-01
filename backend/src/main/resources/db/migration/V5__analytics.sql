-- Operonix P2a — business intelligence rollups.
-- Additive only. Every row is derived from source records by AnalyticsService and can be rebuilt
-- from scratch, so the tables are a cache with an explicit refresh state rather than a system of
-- record.

CREATE TABLE metric_daily (
    id              uuid           PRIMARY KEY,
    tenant_id       uuid           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    metric_key      varchar(60)    NOT NULL,
    dimension_type  varchar(20)    NOT NULL,          -- TOTAL | LOCATION
    /** '' for the TOTAL row and for the "unassigned" location bucket. */
    dimension_key   varchar(64)    NOT NULL DEFAULT '',
    dimension_id    uuid,
    dimension_label varchar(160),
    day             date           NOT NULL,
    currency        varchar(3)     NOT NULL REFERENCES currency (code),
    value           numeric(18, 4) NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL,
    updated_at      timestamptz    NOT NULL,
    version         bigint         NOT NULL DEFAULT 0,
    CONSTRAINT uq_metric_daily UNIQUE (tenant_id, metric_key, dimension_type, dimension_key, day)
);

CREATE INDEX ix_metric_daily_lookup ON metric_daily (tenant_id, metric_key, day);

-- One row per workspace: what range the rollup currently covers and when it was last rebuilt.
CREATE TABLE analytics_refresh (
    tenant_id          uuid        PRIMARY KEY REFERENCES tenant (id) ON DELETE CASCADE,
    last_refreshed_at  timestamptz NOT NULL,
    range_start        date        NOT NULL,
    range_end          date        NOT NULL,
    row_count          integer     NOT NULL DEFAULT 0,
    updated_at         timestamptz NOT NULL
);

-- Void invoices are excluded everywhere; this partial index keeps the revenue scans cheap.
CREATE INDEX ix_invoice_revenue_scan ON invoice (tenant_id, issue_date)
    WHERE status <> 'VOID';

CREATE INDEX ix_job_delivery_scan ON job (tenant_id, coalesce(actual_end, scheduled_end));
