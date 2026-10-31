-- Operonix P1a — global financial foundation.
-- Additive only: every column added here has a default or is backfilled, no historical document is
-- recalculated, and no V1/V2 row is deleted. See docs/ERD.md §9 for the data dictionary.

-- ---------------------------------------------------------------- tax catalogue (platform-owned)

CREATE TABLE tax_definition (
    id             uuid         PRIMARY KEY,
    country_code   varchar(2)   REFERENCES country (code),
    code           varchar(40)  NOT NULL,
    name           varchar(120) NOT NULL,
    mode           varchar(10)  NOT NULL,
    rounding       varchar(10)  NOT NULL DEFAULT 'LINE',
    effective_from date         NOT NULL DEFAULT DATE '2020-01-01',
    effective_to   date,
    active         boolean      NOT NULL DEFAULT true,
    CONSTRAINT uq_tax_definition_code UNIQUE (code),
    CONSTRAINT ck_tax_definition_mode CHECK (mode IN ('EXCLUSIVE', 'INCLUSIVE')),
    CONSTRAINT ck_tax_definition_rounding CHECK (rounding IN ('LINE', 'DOCUMENT')),
    CONSTRAINT ck_tax_definition_window CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE TABLE tax_component (
    id               uuid          PRIMARY KEY,
    tax_definition_id uuid         NOT NULL REFERENCES tax_definition (id) ON DELETE CASCADE,
    code             varchar(20)   NOT NULL,
    name             varchar(80)   NOT NULL,
    rate             numeric(7, 5) NOT NULL,
    compound         boolean       NOT NULL DEFAULT false,
    position         integer       NOT NULL DEFAULT 0,
    CONSTRAINT uq_tax_component_code UNIQUE (tax_definition_id, code),
    CONSTRAINT ck_tax_component_rate CHECK (rate >= 0 AND rate <= 1),
    CONSTRAINT ck_tax_component_position CHECK (position >= 0)
);

CREATE INDEX ix_tax_component_definition ON tax_component (tax_definition_id, position);

-- Seed jurisdictions for the markets the platform can bill in from day one. Rates are defaults a
-- tenant can override with its own profile; they are data, so a rate change is an insert, not code.
INSERT INTO tax_definition (id, country_code, code, name, mode, rounding) VALUES
    ('a1000000-0000-4000-8000-000000000001', 'IN', 'IN-GST-18',       'India GST 18%',              'EXCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-000000000002', 'IN', 'IN-GST-5',        'India GST 5%',               'EXCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-000000000003', 'IN', 'IN-GST-0',        'India GST exempt',          'EXCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-000000000004', 'US', 'US-CA-SALES-825', 'California sales tax 8.25%', 'EXCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-000000000005', 'US', 'US-NY-SALES-8',   'New York sales tax 8%',      'EXCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-000000000006', 'GB', 'GB-VAT-20',       'UK VAT 20%',                 'INCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-000000000007', 'GB', 'GB-VAT-0',        'UK VAT zero-rated',          'INCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-000000000008', 'JP', 'JP-CT-10',        'Japan consumption tax 10%',  'INCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-000000000009', 'JP', 'JP-CT-8',         'Japan consumption tax 8%',   'INCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-00000000000a', 'AU', 'AU-GST-10',       'Australia GST 10%',          'INCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-00000000000b', 'DE', 'DE-VAT-19',       'Germany VAT 19%',            'EXCLUSIVE', 'LINE'),
    ('a1000000-0000-4000-8000-00000000000c', NULL, 'GLOBAL-EXEMPT',  'No tax (exempt)',           'EXCLUSIVE', 'LINE');

INSERT INTO tax_component (id, tax_definition_id, code, name, rate, position) VALUES
    ('b1000000-0000-4000-8000-000000000001', 'a1000000-0000-4000-8000-000000000001', 'CGST', 'Central GST', 0.09000, 0),
    ('b1000000-0000-4000-8000-000000000002', 'a1000000-0000-4000-8000-000000000001', 'SGST', 'State GST',   0.09000, 1),
    ('b1000000-0000-4000-8000-000000000003', 'a1000000-0000-4000-8000-000000000002', 'CGST', 'Central GST', 0.02500, 0),
    ('b1000000-0000-4000-8000-000000000004', 'a1000000-0000-4000-8000-000000000002', 'SGST', 'State GST',   0.02500, 1),
    ('b1000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000003', 'EXEMPT', 'Exempt',    0.00000, 0),
    ('b1000000-0000-4000-8000-000000000006', 'a1000000-0000-4000-8000-000000000004', 'STATE', 'State sales tax', 0.08250, 0),
    ('b1000000-0000-4000-8000-000000000007', 'a1000000-0000-4000-8000-000000000005', 'STATE', 'State sales tax', 0.08000, 0),
    ('b1000000-0000-4000-8000-000000000008', 'a1000000-0000-4000-8000-000000000006', 'VAT', 'VAT',         0.20000, 0),
    ('b1000000-0000-4000-8000-000000000009', 'a1000000-0000-4000-8000-000000000007', 'VAT', 'VAT',         0.00000, 0),
    ('b1000000-0000-4000-8000-00000000000a', 'a1000000-0000-4000-8000-000000000008', 'CT',  'Consumption tax', 0.10000, 0),
    ('b1000000-0000-4000-8000-00000000000b', 'a1000000-0000-4000-8000-000000000009', 'CT',  'Consumption tax', 0.08000, 0),
    ('b1000000-0000-4000-8000-00000000000c', 'a1000000-0000-4000-8000-00000000000a', 'GST', 'GST',         0.10000, 0),
    ('b1000000-0000-4000-8000-00000000000d', 'a1000000-0000-4000-8000-00000000000b', 'VAT', 'VAT',         0.19000, 0),
    ('b1000000-0000-4000-8000-00000000000e', 'a1000000-0000-4000-8000-00000000000c', 'EXEMPT', 'Exempt',    0.00000, 0);

-- A tax profile is the tenant's own, named tax setup: one or more components, a mode, and an
-- optional legal entity it belongs to. Business logic only ever references a profile, never a
-- definition, so a rate change is a new profile rather than a code branch.
CREATE TABLE tax_profile (
    id                uuid         PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenant (id),
    legal_entity_id   uuid,
    name              varchar(120) NOT NULL,
    tax_definition_id uuid         REFERENCES tax_definition (id),
    mode              varchar(10),
    is_default        boolean      NOT NULL DEFAULT false,
    active            boolean      NOT NULL DEFAULT true,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_tax_profile_mode CHECK (mode IS NULL OR mode IN ('EXCLUSIVE', 'INCLUSIVE'))
);

CREATE INDEX ix_tax_profile_tenant ON tax_profile (tenant_id, active);
CREATE UNIQUE INDEX uq_tax_profile_default ON tax_profile (tenant_id) WHERE is_default;
CREATE UNIQUE INDEX uq_tax_profile_tenant_name ON tax_profile (tenant_id, lower(name));

-- ---------------------------------------------------------------- entities and locations

CREATE TABLE legal_entity (
    id            uuid         PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    name          varchar(160) NOT NULL,
    country_code  varchar(2)   NOT NULL REFERENCES country (code),
    tax_number    varchar(32),
    base_currency varchar(3)   NOT NULL REFERENCES currency (code),
    tax_mode      varchar(10)  NOT NULL DEFAULT 'EXCLUSIVE',
    tax_number_label varchar(40),
    address       jsonb,
    is_default    boolean      NOT NULL DEFAULT false,
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_legal_entity_mode CHECK (tax_mode IN ('EXCLUSIVE', 'INCLUSIVE'))
);

CREATE INDEX ix_legal_entity_tenant ON legal_entity (tenant_id, active);
CREATE UNIQUE INDEX uq_legal_entity_default ON legal_entity (tenant_id) WHERE is_default;

CREATE TABLE business_location (
    id              uuid         PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenant (id),
    legal_entity_id uuid         NOT NULL REFERENCES legal_entity (id),
    name            varchar(120) NOT NULL,
    country_code    varchar(2)   NOT NULL REFERENCES country (code),
    timezone        varchar(64)  NOT NULL,
    address         jsonb,
    is_default      boolean      NOT NULL DEFAULT false,
    active          boolean      NOT NULL DEFAULT true,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0
);

CREATE INDEX ix_business_location_tenant ON business_location (tenant_id, active);
CREATE UNIQUE INDEX uq_business_location_default ON business_location (tenant_id) WHERE is_default;
CREATE UNIQUE INDEX uq_business_location_tenant_name ON business_location (tenant_id, lower(name));

CREATE TABLE tenant_settings (
    tenant_id                  uuid        PRIMARY KEY REFERENCES tenant (id),
    locale                     varchar(16) NOT NULL,
    week_start                 smallint    NOT NULL DEFAULT 1,
    fiscal_year_start_month    smallint    NOT NULL DEFAULT 1,
    default_tax_profile_id     uuid        REFERENCES tax_profile (id),
    default_legal_entity_id    uuid        REFERENCES legal_entity (id),
    date_format                varchar(20) NOT NULL DEFAULT 'MEDIUM',
    number_format              varchar(16) NOT NULL DEFAULT 'STANDARD',
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    version                    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_tenant_settings_week CHECK (week_start BETWEEN 0 AND 6),
    CONSTRAINT ck_tenant_settings_fiscal CHECK (fiscal_year_start_month BETWEEN 1 AND 12)
);

-- ---------------------------------------------------------------- numbering

-- Replaces the hardcoded "PREFIX-YEAR-0000" format. The backfilled rules reproduce today's numbers
-- exactly (Q-2026-0001, J-2026-0001, INV-2026-0001), so existing tenants see no change.
CREATE TABLE document_number_rule (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    legal_entity_id uuid        REFERENCES legal_entity (id),
    doc_type        varchar(10) NOT NULL,
    prefix          varchar(12) NOT NULL,
    include_year    boolean     NOT NULL DEFAULT true,
    padding         smallint    NOT NULL DEFAULT 4,
    reset_policy    varchar(10) NOT NULL DEFAULT 'YEARLY',
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT ck_document_number_rule_type CHECK (doc_type IN ('QUOTE', 'JOB', 'INVOICE')),
    CONSTRAINT ck_document_number_rule_reset CHECK (reset_policy IN ('YEARLY', 'NEVER')),
    CONSTRAINT ck_document_number_rule_padding CHECK (padding BETWEEN 1 AND 10),
    CONSTRAINT ck_document_number_rule_prefix CHECK (prefix ~ '^[A-Za-z0-9/_-]{1,12}$')
);

CREATE UNIQUE INDEX uq_document_number_rule ON document_number_rule (tenant_id, doc_type)
    WHERE legal_entity_id IS NULL;

-- ---------------------------------------------------------------- foreign exchange

CREATE TABLE exchange_rate (
    id      uuid          PRIMARY KEY,
    base    varchar(3)    NOT NULL REFERENCES currency (code),
    quote   varchar(3)    NOT NULL REFERENCES currency (code),
    rate    numeric(18, 8) NOT NULL,
    as_of   date          NOT NULL,
    source  varchar(32)   NOT NULL DEFAULT 'MANUAL',
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_exchange_rate UNIQUE (base, quote, as_of),
    CONSTRAINT ck_exchange_rate_pair CHECK (base <> quote),
    CONSTRAINT ck_exchange_rate_positive CHECK (rate > 0)
);

CREATE INDEX ix_exchange_rate_lookup ON exchange_rate (base, quote, as_of DESC);

-- ---------------------------------------------------------------- per-document currency and tax snapshots

ALTER TABLE quote
    ADD COLUMN legal_entity_id uuid REFERENCES legal_entity (id),
    ADD COLUMN tax_profile_id  uuid REFERENCES tax_profile (id),
    ADD COLUMN tax_profile_name varchar(120),
    ADD COLUMN tax_mode        varchar(10) NOT NULL DEFAULT 'EXCLUSIVE',
    ADD COLUMN fx_rate         numeric(18, 8) NOT NULL DEFAULT 1,
    ADD COLUMN base_currency   varchar(3)  REFERENCES currency (code),
    ADD COLUMN base_total      numeric(12, 2) NOT NULL DEFAULT 0;

ALTER TABLE quote
    ADD CONSTRAINT ck_quote_tax_mode CHECK (tax_mode IN ('EXCLUSIVE', 'INCLUSIVE')),
    ADD CONSTRAINT ck_quote_fx_rate CHECK (fx_rate > 0);

-- Every existing quote keeps its economics: same currency, rate 1, base total = total.
UPDATE quote SET base_currency = currency, base_total = total, fx_rate = 1;

ALTER TABLE quote ALTER COLUMN base_currency SET NOT NULL;

ALTER TABLE invoice
    ADD COLUMN legal_entity_id uuid REFERENCES legal_entity (id),
    ADD COLUMN tax_profile_id  uuid REFERENCES tax_profile (id),
    ADD COLUMN tax_profile_name varchar(120),
    ADD COLUMN tax_mode        varchar(10) NOT NULL DEFAULT 'EXCLUSIVE',
    ADD COLUMN tax_number      varchar(32),
    ADD COLUMN reverse_charge  boolean NOT NULL DEFAULT false,
    ADD COLUMN fx_rate         numeric(18, 8) NOT NULL DEFAULT 1,
    ADD COLUMN base_currency   varchar(3)  REFERENCES currency (code),
    ADD COLUMN base_total      numeric(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN base_amount_paid numeric(12, 2) NOT NULL DEFAULT 0;

ALTER TABLE invoice
    ADD CONSTRAINT ck_invoice_tax_mode CHECK (tax_mode IN ('EXCLUSIVE', 'INCLUSIVE')),
    ADD CONSTRAINT ck_invoice_fx_rate CHECK (fx_rate > 0);

UPDATE invoice SET base_currency = currency, base_total = total, base_amount_paid = amount_paid, fx_rate = 1;

ALTER TABLE invoice ALTER COLUMN base_currency SET NOT NULL;

-- One row per printed tax component per line. Existing single-rate lines are not rewritten; the
-- application synthesizes one component for them at read time, so reports and PDFs stay uniform.
CREATE TABLE line_tax_component (
    id                uuid          PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    line_type         varchar(10)   NOT NULL,
    line_id           uuid          NOT NULL,
    tax_component_id  uuid          REFERENCES tax_component (id),
    code              varchar(20)   NOT NULL,
    name              varchar(80)   NOT NULL,
    base              numeric(12, 2) NOT NULL,
    rate              numeric(7, 5) NOT NULL,
    amount            numeric(12, 2) NOT NULL,
    position          integer       NOT NULL DEFAULT 0,
    CONSTRAINT ck_line_tax_component_type CHECK (line_type IN ('QUOTE', 'INVOICE')),
    CONSTRAINT ck_line_tax_component_position CHECK (position >= 0)
);

CREATE INDEX ix_line_tax_component_line ON line_tax_component (line_type, line_id);
CREATE INDEX ix_line_tax_component_tenant ON line_tax_component (tenant_id);

ALTER TABLE service_item ADD COLUMN tax_profile_id uuid REFERENCES tax_profile (id);

-- ---------------------------------------------------------------- backfill tenant configuration

-- One default legal entity per workspace: existing documents stay attributable to the same seller.
INSERT INTO legal_entity (id, tenant_id, name, country_code, tax_number, base_currency, tax_mode,
                          tax_number_label, is_default, created_at, updated_at)
SELECT md5('legal-entity:' || t.id::text)::uuid,
       t.id,
       COALESCE(t.legal_name, t.name),
       t.country_code,
       t.tax_number,
       t.currency,
       'EXCLUSIVE',
       c.tax_label,
       true,
       now(),
       now()
FROM tenant t
JOIN country c ON c.code = t.country_code;

-- One default profile per workspace, chosen from the country's standard rate. Profiles are only
-- consulted when a catalog item opts in, so no existing total changes.
INSERT INTO tax_profile (id, tenant_id, legal_entity_id, name, tax_definition_id, mode, is_default,
                         created_at, updated_at)
SELECT md5('tax-profile:' || t.id::text)::uuid,
       t.id,
       md5('legal-entity:' || t.id::text)::uuid,
       td.name,
       td.id,
       td.mode,
       true,
       now(),
       now()
FROM tenant t
JOIN tax_definition td ON td.code = CASE t.country_code
        WHEN 'IN' THEN 'IN-GST-18'
        WHEN 'US' THEN 'US-CA-SALES-825'
        WHEN 'GB' THEN 'GB-VAT-20'
        WHEN 'JP' THEN 'JP-CT-10'
        WHEN 'AU' THEN 'AU-GST-10'
        WHEN 'DE' THEN 'DE-VAT-19'
        ELSE 'GLOBAL-EXEMPT'
    END;

INSERT INTO tenant_settings (tenant_id, locale, default_tax_profile_id, default_legal_entity_id,
                             created_at, updated_at)
SELECT t.id,
       t.locale,
       md5('tax-profile:' || t.id::text)::uuid,
       md5('legal-entity:' || t.id::text)::uuid,
       now(),
       now()
FROM tenant t;

INSERT INTO business_location (id, tenant_id, legal_entity_id, name, country_code, timezone,
                               is_default, created_at, updated_at)
SELECT md5('business-location:' || t.id::text)::uuid,
       t.id,
       md5('legal-entity:' || t.id::text)::uuid,
       'Main location',
       t.country_code,
       t.timezone,
       true,
       now(),
       now()
FROM tenant t;

INSERT INTO document_number_rule (id, tenant_id, doc_type, prefix, include_year, padding,
                                  reset_policy, created_at, updated_at)
SELECT md5('number-rule:' || t.id::text || ':' || d.doc_type)::uuid,
       t.id,
       d.doc_type,
       d.prefix,
       true,
       4,
       'YEARLY',
       now(),
       now()
FROM tenant t
CROSS JOIN (VALUES ('QUOTE', 'Q'), ('JOB', 'J'), ('INVOICE', 'INV')) AS d(doc_type, prefix);

-- Document rows carry the seller and the tax configuration that produced them, for audit.
UPDATE invoice i SET legal_entity_id = md5('legal-entity:' || i.tenant_id::text)::uuid,
                     tax_profile_id  = md5('tax-profile:' || i.tenant_id::text)::uuid
WHERE i.legal_entity_id IS NULL;

UPDATE quote q SET legal_entity_id = md5('legal-entity:' || q.tenant_id::text)::uuid,
                   tax_profile_id  = md5('tax-profile:' || q.tenant_id::text)::uuid
WHERE q.legal_entity_id IS NULL;
