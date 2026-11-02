-- Persistent M5-owned UUID for existing and future payments; internal BIGINT keys stay unchanged.
ALTER TABLE payment ADD COLUMN integration_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE payment ADD CONSTRAINT uq_payment_integration_id UNIQUE (integration_id);
