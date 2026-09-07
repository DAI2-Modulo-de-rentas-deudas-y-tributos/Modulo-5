-- La clave se limita al contribuyente; NULL conserva los pagos sin cabecera.
ALTER TABLE payment ADD COLUMN idempotency_key VARCHAR(128);
ALTER TABLE payment ADD COLUMN idempotency_fingerprint VARCHAR(64);
ALTER TABLE payment ADD CONSTRAINT uq_payment_taxpayer_idempotency
    UNIQUE (taxpayer_id, idempotency_key);
ALTER TABLE payment ADD CONSTRAINT ck_payment_idempotency_pair
    CHECK ((idempotency_key IS NULL AND idempotency_fingerprint IS NULL)
        OR (idempotency_key IS NOT NULL AND idempotency_fingerprint IS NOT NULL));
