-- Operonix P2b — AI usage, quotas and audit trail.
-- Additive only. The AI never stores financial state: these tables record settings, quota counters
-- and what was asked, never a figure that the rest of the product reads.

CREATE TABLE ai_settings (
    tenant_id          uuid        PRIMARY KEY REFERENCES tenant (id) ON DELETE CASCADE,
    enabled            boolean     NOT NULL DEFAULT true,
    daily_query_limit  integer     NOT NULL DEFAULT 100,
    model              varchar(60) NOT NULL DEFAULT 'gemini-2.0-flash',
    updated_by         uuid        REFERENCES app_user (id),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT ck_ai_daily_limit CHECK (daily_query_limit >= 0)
);

-- One row per workspace per day. Counters are incremented atomically, and the same statement
-- enforces the daily quota so two concurrent requests cannot both slip past the limit.
CREATE TABLE ai_usage (
    tenant_id   uuid    NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    day         date    NOT NULL,
    queries     integer NOT NULL DEFAULT 0,
    blocked     integer NOT NULL DEFAULT 0,
    tokens_in   integer NOT NULL DEFAULT 0,
    tokens_out  integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_ai_usage PRIMARY KEY (tenant_id, day)
);

-- What was asked and what the model said. The financial answer is reproducible from the metric
-- request stored alongside it, which is why no amounts are duplicated here.
CREATE TABLE ai_query_log (
    id            uuid         PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    actor_user_id uuid         REFERENCES app_user (id),
    kind          varchar(20)  NOT NULL,          -- REPORTING | QUOTE_ASSIST | INSIGHTS
    question      varchar(1000),
    metric_request jsonb,
    answer        varchar(8000),
    provider      varchar(30),
    model         varchar(60),
    status        varchar(20)  NOT NULL,          -- OK | DEGRADED | FAILED | QUOTA_EXCEEDED | DISABLED
    error_code    varchar(40),
    tokens_in     integer      NOT NULL DEFAULT 0,
    tokens_out    integer      NOT NULL DEFAULT 0,
    latency_ms    integer      NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL
);

CREATE INDEX ix_ai_query_log_tenant ON ai_query_log (tenant_id, created_at DESC);
