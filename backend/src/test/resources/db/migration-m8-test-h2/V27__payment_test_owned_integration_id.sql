ALTER TABLE payment ADD COLUMN integration_id UUID DEFAULT RANDOM_UUID() NOT NULL;
ALTER TABLE payment ADD CONSTRAINT uq_payment_integration_id UNIQUE (integration_id);
