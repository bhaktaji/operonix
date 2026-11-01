-- Operonix P1b — costing engine.
-- Additive only: new tables and nullable/defaulted columns. No existing row is rewritten.
-- See docs/ERD.md and docs/product-evolution/IMPLEMENTATION_ROADMAP.md §4.

-- ---------------------------------------------------------------- hourly cost on user
-- Optional labour cost rate, expressed in the workspace currency. Zero means "not configured";
-- the costing service treats that as an incomplete input rather than a free technician.
ALTER TABLE app_user ADD COLUMN hourly_cost numeric(12, 2) NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------- work logs
-- Technician time entry on a job. A log is open while end_at is null.
CREATE TABLE work_log (
    id               uuid          PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    job_id           uuid          NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    app_user_id      uuid          NOT NULL REFERENCES app_user (id),
    start_at         timestamptz   NOT NULL,
    end_at           timestamptz,
    is_manual        boolean       NOT NULL DEFAULT false,
    notes            varchar(2000),
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ck_work_log_order CHECK (end_at IS NULL OR end_at >= start_at)
);

CREATE INDEX ix_work_log_job ON work_log (tenant_id, job_id, start_at);
CREATE INDEX ix_work_log_user ON work_log (tenant_id, app_user_id, start_at);

-- ---------------------------------------------------------------- vendors
-- Supplier / subcontractor catalogue, reusable across jobs.
CREATE TABLE vendor (
    id               uuid          PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    name             varchar(160)  NOT NULL,
    contact_name     varchar(120),
    email            varchar(254),
    phone            varchar(64),
    address          jsonb,
    tax_number       varchar(32),
    default_rate     numeric(12, 2) NOT NULL DEFAULT 0,
    payment_terms    varchar(30)   NOT NULL DEFAULT 'NET_30',
    active           boolean       NOT NULL DEFAULT true,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL DEFAULT 0
);

-- Case-insensitive uniqueness per tenant. A table constraint cannot hold an expression,
-- so this is a unique index.
CREATE UNIQUE INDEX uq_vendor_name ON vendor (tenant_id, lower(name));
CREATE INDEX ix_vendor_tenant ON vendor (tenant_id, active);

-- ---------------------------------------------------------------- expenses
-- Job-linked cost entries. `amount` is in `currency`; `workspace_amount` is the same cost
-- converted at `fx_rate` on `expense_date` and is what the roll-up aggregates. Both are kept so a
-- later rate correction never rewrites history.
CREATE TABLE expense (
    id                uuid           PRIMARY KEY,
    tenant_id         uuid           NOT NULL REFERENCES tenant (id),
    job_id            uuid           REFERENCES job (id) ON DELETE SET NULL,
    vendor_id         uuid           REFERENCES vendor (id),
    description       varchar(360)   NOT NULL,
    amount            numeric(12, 2) NOT NULL,
    currency          varchar(3)     NOT NULL REFERENCES currency (code),
    fx_rate           numeric(18, 8) NOT NULL DEFAULT 1,
    workspace_amount  numeric(12, 2) NOT NULL,
    receipt_reference varchar(120),
    expense_date      date           NOT NULL,
    category          varchar(30)    NOT NULL DEFAULT 'OTHER',
    notes             varchar(2000),
    created_by        uuid           REFERENCES app_user (id),
    created_at        timestamptz    NOT NULL,
    updated_at        timestamptz    NOT NULL,
    version           bigint         NOT NULL DEFAULT 0,
    CONSTRAINT ck_expense_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_expense_category CHECK (category IN ('LABOR', 'MATERIAL', 'EQUIPMENT', 'SUBCONTRACTOR', 'OTHER'))
);

CREATE INDEX ix_expense_job ON expense (tenant_id, job_id, expense_date DESC);
CREATE INDEX ix_expense_vendor ON expense (vendor_id);
CREATE INDEX ix_expense_date ON expense (tenant_id, expense_date DESC);

-- ---------------------------------------------------------------- job_cost roll-up
-- Materialized, idempotent roll-up: exactly one row per job, rebuilt from the source rows
-- (work logs + expenses) on every change. It never accumulates a delta.
CREATE TABLE job_cost (
    id                 uuid           PRIMARY KEY,
    tenant_id          uuid           NOT NULL REFERENCES tenant (id),
    job_id             uuid           NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    labor_cost         numeric(12, 2) NOT NULL DEFAULT 0,
    material_cost      numeric(12, 2) NOT NULL DEFAULT 0,
    equipment_cost     numeric(12, 2) NOT NULL DEFAULT 0,
    subcontractor_cost numeric(12, 2) NOT NULL DEFAULT 0,
    other_cost         numeric(12, 2) NOT NULL DEFAULT 0,
    total_cost         numeric(12, 2) NOT NULL DEFAULT 0,
    currency           varchar(3)     NOT NULL REFERENCES currency (code),
    computed_at        timestamptz    NOT NULL,
    computed_by        uuid           REFERENCES app_user (id),
    created_at         timestamptz    NOT NULL,
    version            bigint         NOT NULL DEFAULT 0,
    CONSTRAINT uq_job_cost UNIQUE (tenant_id, job_id)
);

-- ---------------------------------------------------------------- service_item cost
-- Planned cost per catalogue item; drives planned margin on quotes.
ALTER TABLE service_item ADD COLUMN cost numeric(12, 2) NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------- job actuals
-- Actual start/end of work, derived from work logs so a job reports real duration.
ALTER TABLE job ADD COLUMN actual_start timestamptz;
ALTER TABLE job ADD COLUMN actual_end timestamptz;
