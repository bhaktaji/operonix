-- Payments are reversed, never deleted.
--
-- Reversing used to delete the row, which left an invoice showing "no payments recorded" and made
-- the payment invisible to anyone reconciling the invoice against the audit trail or a bank
-- statement. A reversal now marks the row: the original amount, method, reference and time stay on
-- record, and reporting ignores reversed rows by filtering `reversed_at is null`.
ALTER TABLE payment ADD COLUMN reversed_at timestamptz;
ALTER TABLE payment ADD COLUMN reversed_by uuid;
ALTER TABLE payment ADD COLUMN reversal_reason varchar(280);

ALTER TABLE payment
    ADD CONSTRAINT fk_payment_reversed_by
        FOREIGN KEY (reversed_by) REFERENCES app_user (id) ON DELETE SET NULL;

-- The live-payments path (balance sums, invoice timelines, cash collected) only ever reads the
-- active rows, so the partial index keeps that lookup small as history grows.
CREATE INDEX ix_payment_active ON payment (tenant_id, invoice_id) WHERE reversed_at IS NULL;