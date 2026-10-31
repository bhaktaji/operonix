-- Operonix v1 schema. Shared schema with mandatory tenant discriminator.
-- See docs/ERD.md for the authoritative data dictionary.

CREATE TABLE tenant (
    id          uuid         PRIMARY KEY,
    name        varchar(120) NOT NULL,
    slug        varchar(63)  NOT NULL,
    currency    varchar(3)   NOT NULL,
    timezone    varchar(64)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_slug UNIQUE (slug),
    CONSTRAINT ck_tenant_name_len CHECK (char_length(name) BETWEEN 2 AND 120),
    CONSTRAINT ck_tenant_slug CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    CONSTRAINT ck_tenant_currency CHECK (currency IN ('USD', 'EUR', 'GBP', 'INR', 'AUD', 'CAD'))
);

CREATE TABLE app_user (
    id            uuid         PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    email         varchar(254) NOT NULL,
    password_hash varchar(100) NOT NULL,
    full_name     varchar(120) NOT NULL,
    role          varchar(20)  NOT NULL,
    active        boolean      NOT NULL DEFAULT true,
    last_login_at timestamptz,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'TECHNICIAN'))
);

CREATE INDEX ix_app_user_tenant_role ON app_user (tenant_id, role);

CREATE TABLE refresh_token (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_token_user_expiry ON refresh_token (user_id, expires_at);

CREATE TABLE customer (
    id                  uuid         PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenant (id),
    name                varchar(160) NOT NULL,
    contact_name        varchar(120),
    email               varchar(254),
    phone               varchar(32),
    notes               varchar(2000),
    status              varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    billing_line1       varchar(160),
    billing_line2       varchar(160),
    billing_city        varchar(80),
    billing_region      varchar(80),
    billing_postal_code varchar(16),
    billing_country     varchar(2),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_customer_name_len CHECK (char_length(name) >= 1),
    CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX ix_customer_tenant_name ON customer (tenant_id, name);
CREATE INDEX ix_customer_tenant_status ON customer (tenant_id, status);

CREATE TABLE service_location (
    id            uuid         PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    customer_id   uuid         NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    label         varchar(80)  NOT NULL,
    line1         varchar(160) NOT NULL,
    line2         varchar(160),
    city          varchar(80)  NOT NULL,
    region        varchar(80),
    postal_code   varchar(16)  NOT NULL,
    country       varchar(2)   NOT NULL,
    access_notes  varchar(1000),
    is_primary    boolean      NOT NULL DEFAULT false,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL
);

CREATE INDEX ix_service_location_tenant_customer ON service_location (tenant_id, customer_id);
CREATE UNIQUE INDEX uq_service_location_primary ON service_location (customer_id) WHERE is_primary;

CREATE TABLE service_item (
    id               uuid          PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    name             varchar(120)  NOT NULL,
    description      varchar(500),
    unit             varchar(16)   NOT NULL,
    base_price       numeric(12, 2) NOT NULL,
    duration_minutes integer       NOT NULL,
    tax_rate         numeric(5, 4) NOT NULL DEFAULT 0,
    active           boolean       NOT NULL DEFAULT true,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_service_item_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_service_item_unit CHECK (unit IN ('HOUR', 'VISIT', 'SQUARE_FOOT', 'ROOM', 'FLAT')),
    CONSTRAINT ck_service_item_price CHECK (base_price >= 0),
    CONSTRAINT ck_service_item_duration CHECK (duration_minutes BETWEEN 15 AND 1440),
    CONSTRAINT ck_service_item_tax CHECK (tax_rate >= 0 AND tax_rate <= 1)
);

CREATE INDEX ix_service_item_tenant_active ON service_item (tenant_id, active);

CREATE TABLE quote (
    id               uuid           PRIMARY KEY,
    tenant_id        uuid           NOT NULL REFERENCES tenant (id),
    customer_id      uuid           NOT NULL REFERENCES customer (id),
    location_id      uuid           REFERENCES service_location (id),
    number           varchar(20)    NOT NULL,
    status           varchar(16)    NOT NULL DEFAULT 'DRAFT',
    issue_date       date           NOT NULL,
    valid_until      date           NOT NULL,
    currency         varchar(3)     NOT NULL,
    notes            varchar(2000),
    subtotal         numeric(12, 2) NOT NULL DEFAULT 0,
    discount_amount  numeric(12, 2) NOT NULL DEFAULT 0,
    tax_amount       numeric(12, 2) NOT NULL DEFAULT 0,
    total            numeric(12, 2) NOT NULL DEFAULT 0,
    converted_job_id uuid,
    created_by       uuid           NOT NULL REFERENCES app_user (id),
    created_at       timestamptz    NOT NULL,
    updated_at       timestamptz    NOT NULL,
    version          bigint         NOT NULL DEFAULT 0,
    CONSTRAINT uq_quote_number UNIQUE (tenant_id, number),
    CONSTRAINT ck_quote_status CHECK (status IN ('DRAFT', 'SENT', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CONVERTED')),
    CONSTRAINT ck_quote_validity CHECK (valid_until >= issue_date),
    CONSTRAINT ck_quote_discount CHECK (discount_amount >= 0 AND discount_amount <= subtotal)
);

CREATE INDEX ix_quote_tenant_status_date ON quote (tenant_id, status, issue_date DESC);
CREATE INDEX ix_quote_tenant_customer ON quote (tenant_id, customer_id);

CREATE TABLE quote_line (
    id              uuid           PRIMARY KEY,
    tenant_id       uuid           NOT NULL REFERENCES tenant (id),
    quote_id        uuid           NOT NULL REFERENCES quote (id) ON DELETE CASCADE,
    service_item_id uuid           REFERENCES service_item (id) ON DELETE SET NULL,
    position        integer        NOT NULL,
    description     varchar(300)   NOT NULL,
    quantity        numeric(10, 3) NOT NULL,
    unit_price      numeric(12, 2) NOT NULL,
    tax_rate        numeric(5, 4)  NOT NULL,
    line_subtotal   numeric(12, 2) NOT NULL,
    line_tax        numeric(12, 2) NOT NULL,
    line_total      numeric(12, 2) NOT NULL,
    created_at      timestamptz    NOT NULL,
    CONSTRAINT uq_quote_line_position UNIQUE (quote_id, position),
    CONSTRAINT ck_quote_line_position CHECK (position >= 0),
    CONSTRAINT ck_quote_line_qty CHECK (quantity > 0),
    CONSTRAINT ck_quote_line_price CHECK (unit_price >= 0),
    CONSTRAINT ck_quote_line_tax CHECK (tax_rate >= 0 AND tax_rate <= 1)
);

CREATE INDEX ix_quote_line_quote ON quote_line (quote_id, position);

CREATE TABLE job (
    id              uuid          PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenant (id),
    customer_id     uuid          NOT NULL REFERENCES customer (id),
    location_id     uuid          NOT NULL REFERENCES service_location (id),
    quote_id        uuid          REFERENCES quote (id),
    number          varchar(20)   NOT NULL,
    title           varchar(160)  NOT NULL,
    scheduled_start timestamptz   NOT NULL,
    scheduled_end   timestamptz   NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'SCHEDULED',
    recurrence      varchar(16)   NOT NULL DEFAULT 'NONE',
    series_id       uuid,
    notes           varchar(2000),
    created_by      uuid          NOT NULL REFERENCES app_user (id),
    completed_at    timestamptz,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    version         bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_job_number UNIQUE (tenant_id, number),
    CONSTRAINT ck_job_window CHECK (scheduled_end > scheduled_start),
    CONSTRAINT ck_job_status CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_job_recurrence CHECK (recurrence IN ('NONE', 'WEEKLY', 'BIWEEKLY', 'MONTHLY')),
    CONSTRAINT ck_job_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX ix_job_tenant_start ON job (tenant_id, scheduled_start);
CREATE INDEX ix_job_tenant_customer ON job (tenant_id, customer_id);
CREATE INDEX ix_job_tenant_status ON job (tenant_id, status);
CREATE INDEX ix_job_series ON job (tenant_id, series_id);
CREATE UNIQUE INDEX uq_job_quote ON job (quote_id) WHERE quote_id IS NOT NULL;

CREATE TABLE job_assignment (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    job_id     uuid        NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_job_assignment UNIQUE (job_id, user_id)
);

CREATE INDEX ix_job_assignment_tenant_user ON job_assignment (tenant_id, user_id);

CREATE TABLE invoice (
    id              uuid           PRIMARY KEY,
    tenant_id       uuid           NOT NULL REFERENCES tenant (id),
    customer_id     uuid           NOT NULL REFERENCES customer (id),
    job_id          uuid           REFERENCES job (id),
    number          varchar(20)    NOT NULL,
    status          varchar(16)    NOT NULL DEFAULT 'DRAFT',
    issue_date      date           NOT NULL,
    due_date        date           NOT NULL,
    currency        varchar(3)     NOT NULL,
    notes           varchar(2000),
    subtotal        numeric(12, 2) NOT NULL DEFAULT 0,
    discount_amount numeric(12, 2) NOT NULL DEFAULT 0,
    tax_amount      numeric(12, 2) NOT NULL DEFAULT 0,
    total           numeric(12, 2) NOT NULL DEFAULT 0,
    amount_paid     numeric(12, 2) NOT NULL DEFAULT 0,
    paid_at         timestamptz,
    void_reason     varchar(500),
    created_by      uuid           NOT NULL REFERENCES app_user (id),
    created_at      timestamptz    NOT NULL,
    updated_at      timestamptz    NOT NULL,
    version         bigint         NOT NULL DEFAULT 0,
    CONSTRAINT uq_invoice_number UNIQUE (tenant_id, number),
    CONSTRAINT ck_invoice_status CHECK (status IN ('DRAFT', 'SENT', 'PARTIALLY_PAID', 'PAID', 'VOID')),
    CONSTRAINT ck_invoice_due CHECK (due_date >= issue_date),
    CONSTRAINT ck_invoice_discount CHECK (discount_amount >= 0 AND discount_amount <= subtotal),
    CONSTRAINT ck_invoice_paid CHECK (amount_paid >= 0 AND amount_paid <= total),
    CONSTRAINT ck_invoice_void_reason CHECK (status <> 'VOID' OR void_reason IS NOT NULL)
);

CREATE INDEX ix_invoice_tenant_status_due ON invoice (tenant_id, status, due_date);
CREATE INDEX ix_invoice_tenant_customer ON invoice (tenant_id, customer_id);
CREATE UNIQUE INDEX uq_invoice_job_active ON invoice (job_id) WHERE job_id IS NOT NULL AND status <> 'VOID';

CREATE TABLE invoice_line (
    id              uuid           PRIMARY KEY,
    tenant_id       uuid           NOT NULL REFERENCES tenant (id),
    invoice_id      uuid           NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    service_item_id uuid           REFERENCES service_item (id) ON DELETE SET NULL,
    position        integer        NOT NULL,
    description     varchar(300)   NOT NULL,
    quantity        numeric(10, 3) NOT NULL,
    unit_price      numeric(12, 2) NOT NULL,
    tax_rate        numeric(5, 4)  NOT NULL,
    line_subtotal   numeric(12, 2) NOT NULL,
    line_tax        numeric(12, 2) NOT NULL,
    line_total      numeric(12, 2) NOT NULL,
    created_at      timestamptz    NOT NULL,
    CONSTRAINT uq_invoice_line_position UNIQUE (invoice_id, position),
    CONSTRAINT ck_invoice_line_position CHECK (position >= 0),
    CONSTRAINT ck_invoice_line_qty CHECK (quantity > 0),
    CONSTRAINT ck_invoice_line_price CHECK (unit_price >= 0),
    CONSTRAINT ck_invoice_line_tax CHECK (tax_rate >= 0 AND tax_rate <= 1)
);

CREATE INDEX ix_invoice_line_invoice ON invoice_line (invoice_id, position);

CREATE TABLE payment (
    id          uuid           PRIMARY KEY,
    tenant_id   uuid           NOT NULL REFERENCES tenant (id),
    invoice_id  uuid           NOT NULL REFERENCES invoice (id),
    amount      numeric(12, 2) NOT NULL,
    method      varchar(16)    NOT NULL,
    reference   varchar(120),
    paid_at     timestamptz    NOT NULL,
    recorded_by uuid           NOT NULL REFERENCES app_user (id),
    created_at  timestamptz    NOT NULL,
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_method CHECK (method IN ('CASH', 'CARD', 'BANK_TRANSFER', 'CHECK', 'OTHER'))
);

CREATE INDEX ix_payment_tenant_invoice ON payment (tenant_id, invoice_id);
CREATE INDEX ix_payment_tenant_paid_at ON payment (tenant_id, paid_at);

CREATE TABLE audit_event (
    id            uuid         PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    actor_user_id uuid         REFERENCES app_user (id),
    action        varchar(40)  NOT NULL,
    entity_type   varchar(40)  NOT NULL,
    entity_id     uuid,
    detail        varchar(4000),
    ip_address    varchar(45),
    request_id    varchar(64),
    created_at    timestamptz  NOT NULL
);

CREATE INDEX ix_audit_event_tenant_created ON audit_event (tenant_id, created_at DESC);

CREATE TABLE document_sequence (
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    doc_type   varchar(10) NOT NULL,
    year       integer     NOT NULL,
    next_value integer     NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, doc_type, year),
    CONSTRAINT ck_document_sequence_type CHECK (doc_type IN ('QUOTE', 'JOB', 'INVOICE')),
    CONSTRAINT ck_document_sequence_value CHECK (next_value >= 1)
);
