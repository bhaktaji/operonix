-- Scheduling in the location's time zone, and payment retries that cannot double-charge.
--
-- A job's wall-clock time belongs to where the work happens, not to the device that happened to be
-- typing. `service_location.timezone` (IANA) is what the API resolves a `startLocal`/`endLocal`
-- wall-clock value with; a location without one falls back to the workspace time zone, so existing
-- rows keep working. Instants are still stored as UTC.
ALTER TABLE service_location ADD COLUMN timezone varchar(64);

ALTER TABLE service_location
    ADD CONSTRAINT ck_service_location_timezone
        CHECK (timezone IS NULL OR char_length(timezone) between 3 and 64);

-- A client retrying "record payment" after a timeout must not take the money twice. The key is
-- supplied by the caller, scoped to the workspace: the same key replayed returns the original
-- payment instead of writing a second row.
ALTER TABLE payment ADD COLUMN idempotency_key varchar(64);

CREATE UNIQUE INDEX uq_payment_idempotency
    ON payment (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
