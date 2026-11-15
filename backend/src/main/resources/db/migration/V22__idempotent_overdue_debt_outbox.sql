ALTER TABLE outbox_event ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX uk_outbox_idempotency_key ON outbox_event(idempotency_key);
