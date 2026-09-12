ALTER TABLE payment ADD COLUMN idempotency_fingerprint VARCHAR(64);

DROP INDEX IF EXISTS uk_payment_idempotency_key;
CREATE UNIQUE INDEX uk_payment_taxpayer_idempotency_key
    ON payment(taxpayer_id, idempotency_key);
