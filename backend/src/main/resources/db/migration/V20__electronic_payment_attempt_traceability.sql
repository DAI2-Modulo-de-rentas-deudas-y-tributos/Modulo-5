ALTER TABLE electronic_payment_attempt ADD COLUMN rejection_reason VARCHAR(500);
ALTER TABLE electronic_payment_attempt ADD COLUMN idempotency_key VARCHAR(128);
ALTER TABLE electronic_payment_attempt ADD COLUMN idempotency_fingerprint VARCHAR(64);

ALTER TABLE electronic_payment_attempt
    ADD CONSTRAINT uk_electronic_attempt_taxpayer_idempotency UNIQUE (taxpayer_id, idempotency_key);
