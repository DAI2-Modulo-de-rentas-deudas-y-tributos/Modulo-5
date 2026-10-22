ALTER TABLE bill ADD COLUMN idempotency_key VARCHAR(128);
ALTER TABLE bill ADD COLUMN idempotency_fingerprint VARCHAR(64);

CREATE UNIQUE INDEX uk_bill_taxpayer_idempotency_key
    ON bill(taxpayer_id, idempotency_key);
