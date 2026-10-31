-- Operonix 2.0 P0 — global foundation, industry independence, identity hardening.
-- Additive only: no table, column or row created by V1 is dropped or rewritten destructively.

-- ---------------------------------------------------------------- reference data

CREATE TABLE country (
    code              varchar(2)   PRIMARY KEY,
    name              varchar(80)  NOT NULL,
    default_currency  varchar(3)   NOT NULL,
    default_locale    varchar(16)  NOT NULL,
    calling_code      varchar(8)   NOT NULL,
    postal_code_regex varchar(160),
    tax_label         varchar(40),
    active            boolean      NOT NULL DEFAULT true,
    CONSTRAINT ck_country_code CHECK (code ~ '^[A-Z]{2}$')
);

CREATE TABLE currency (
    code         varchar(3)  PRIMARY KEY,
    name         varchar(64) NOT NULL,
    symbol       varchar(8)  NOT NULL,
    minor_units  smallint    NOT NULL,
    active       boolean     NOT NULL DEFAULT true,
    CONSTRAINT ck_currency_code CHECK (code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_currency_minor_units CHECK (minor_units BETWEEN 0 AND 4)
);

INSERT INTO currency (code, name, symbol, minor_units) VALUES
    ('USD', 'US dollar', '$', 2),
    ('EUR', 'Euro', '€', 2),
    ('GBP', 'British pound', '£', 2),
    ('INR', 'Indian rupee', '₹', 2),
    ('JPY', 'Japanese yen', '¥', 0),
    ('AUD', 'Australian dollar', 'A$', 2),
    ('CAD', 'Canadian dollar', 'C$', 2),
    ('SGD', 'Singapore dollar', 'S$', 2),
    ('AED', 'UAE dirham', 'د.إ', 2),
    ('NZD', 'New Zealand dollar', 'NZ$', 2),
    ('ZAR', 'South African rand', 'R', 2),
    ('CHF', 'Swiss franc', 'CHF', 2),
    ('MYR', 'Malaysian ringgit', 'RM', 2),
    ('BRL', 'Brazilian real', 'R$', 2),
    ('MXN', 'Mexican peso', 'MX$', 2);

INSERT INTO country (code, name, default_currency, default_locale, calling_code, postal_code_regex, tax_label) VALUES
    ('IN', 'India', 'INR', 'en-IN', '+91', '^[1-9][0-9]{5}$', 'GSTIN'),
    ('US', 'United States', 'USD', 'en-US', '+1', '^[0-9]{5}(-[0-9]{4})?$', 'EIN'),
    ('GB', 'United Kingdom', 'GBP', 'en-GB', '+44', '^[A-Za-z]{1,2}[0-9R][0-9A-Za-z]? ?[0-9][A-Za-z]{2}$', 'VAT number'),
    ('JP', 'Japan', 'JPY', 'ja-JP', '+81', '^[0-9]{3}-?[0-9]{4}$', 'Invoice registration number'),
    ('AU', 'Australia', 'AUD', 'en-AU', '+61', '^[0-9]{4}$', 'ABN'),
    ('CA', 'Canada', 'CAD', 'en-CA', '+1', '^[A-Za-z][0-9][A-Za-z] ?[0-9][A-Za-z][0-9]$', 'Business number'),
    ('SG', 'Singapore', 'SGD', 'en-SG', '+65', '^[0-9]{6}$', 'UEN'),
    ('AE', 'United Arab Emirates', 'AED', 'en-AE', '+971', NULL, 'TRN'),
    ('NZ', 'New Zealand', 'NZD', 'en-NZ', '+64', '^[0-9]{4}$', 'NZBN'),
    ('ZA', 'South Africa', 'ZAR', 'en-ZA', '+27', '^[0-9]{4}$', 'VAT number'),
    ('CH', 'Switzerland', 'CHF', 'de-CH', '+41', '^[0-9]{4}$', 'VAT number'),
    ('MY', 'Malaysia', 'MYR', 'en-MY', '+60', '^[0-9]{5}$', 'SST number'),
    ('BR', 'Brazil', 'BRL', 'pt-BR', '+55', '^[0-9]{5}-?[0-9]{3}$', 'CNPJ'),
    ('MX', 'Mexico', 'MXN', 'es-MX', '+52', '^[0-9]{5}$', 'RFC'),
    ('DE', 'Germany', 'EUR', 'de-DE', '+49', '^[0-9]{5}$', 'VAT number'),
    ('FR', 'France', 'EUR', 'fr-FR', '+33', '^[0-9]{5}$', 'VAT number'),
    ('ES', 'Spain', 'EUR', 'es-ES', '+34', '^[0-9]{5}$', 'NIF'),
    ('NL', 'Netherlands', 'EUR', 'nl-NL', '+31', '^[0-9]{4} ?[A-Za-z]{2}$', 'VAT number'),
    ('IE', 'Ireland', 'EUR', 'en-IE', '+353', NULL, 'VAT number'),
    ('PT', 'Portugal', 'EUR', 'pt-PT', '+351', '^[0-9]{4}-?[0-9]{3}$', 'NIF');

-- ---------------------------------------------------------------- tenant global configuration

ALTER TABLE tenant
    ADD COLUMN country_code       varchar(2)  REFERENCES country (code),
    ADD COLUMN locale             varchar(16) NOT NULL DEFAULT 'en',
    ADD COLUMN industry_template  varchar(40) NOT NULL DEFAULT 'GENERAL_SERVICE',
    ADD COLUMN home_region        varchar(32) NOT NULL DEFAULT 'asia-south1',
    ADD COLUMN legal_name         varchar(160),
    ADD COLUMN tax_number         varchar(32),
    ADD COLUMN onboarded_at       timestamptz;

-- Existing workspaces were created before country selection existed. They keep their currency and
-- get the best matching country, so nothing they already rely on changes.
UPDATE tenant SET country_code = CASE upper(currency)
        WHEN 'INR' THEN 'IN'
        WHEN 'GBP' THEN 'GB'
        WHEN 'EUR' THEN 'DE'
        WHEN 'AUD' THEN 'AU'
        WHEN 'CAD' THEN 'CA'
        ELSE 'US'
    END;

ALTER TABLE tenant ALTER COLUMN country_code SET NOT NULL;

ALTER TABLE tenant DROP CONSTRAINT ck_tenant_currency;
ALTER TABLE tenant ADD CONSTRAINT fk_tenant_currency FOREIGN KEY (currency) REFERENCES currency (code);
ALTER TABLE tenant ADD CONSTRAINT ck_tenant_locale CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$');
ALTER TABLE tenant ADD CONSTRAINT ck_tenant_industry CHECK (industry_template IN (
    'GENERAL_SERVICE', 'CLEANING', 'PEST_CONTROL', 'EQUIPMENT_RENTAL', 'AUTO_REPAIR',
    'PLUMBING_ELECTRICAL', 'PROPERTY_MAINTENANCE', 'CONSTRUCTION', 'LOGISTICS', 'TRAINING'));

CREATE INDEX ix_tenant_country ON tenant (country_code);

-- ---------------------------------------------------------------- identity: multi-workspace membership

-- A person may belong to several workspaces. Uniqueness becomes per workspace, and the global index
-- that made the second membership impossible is replaced by a lookup index for sign-in resolution.
ALTER TABLE app_user DROP CONSTRAINT uq_app_user_email;
CREATE UNIQUE INDEX uq_app_user_tenant_email ON app_user (tenant_id, lower(email));
CREATE INDEX ix_app_user_email_lower ON app_user (lower(email));

-- ---------------------------------------------------------------- refresh token rotation families

ALTER TABLE refresh_token
    ADD COLUMN family_id   uuid,
    ADD COLUMN replaced_by uuid REFERENCES refresh_token (id),
    ADD COLUMN user_agent  varchar(200);

UPDATE refresh_token SET family_id = id;
ALTER TABLE refresh_token ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);
CREATE INDEX ix_refresh_token_expiry ON refresh_token (expires_at);

-- ---------------------------------------------------------------- shared rate limiting

-- Fixed-window counters shared by every Cloud Run instance. The bucket already namespaces the
-- caller (login:<ip>:<email>, refresh:<ip>, register:<ip>), so the window alone completes the key.
CREATE TABLE rate_limit_counter (
    bucket       varchar(200) NOT NULL,
    window_start timestamptz  NOT NULL,
    hits         integer      NOT NULL,
    PRIMARY KEY (bucket, window_start),
    CONSTRAINT ck_rate_limit_hits CHECK (hits > 0)
);

CREATE INDEX ix_rate_limit_window ON rate_limit_counter (window_start);