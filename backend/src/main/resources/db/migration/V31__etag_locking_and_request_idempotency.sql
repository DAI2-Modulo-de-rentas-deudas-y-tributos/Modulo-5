ALTER TABLE tax_concept ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE tax_configuration ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE idempotency_request (
    actor_id VARCHAR(255) NOT NULL,
    operation VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_idempotency_request PRIMARY KEY (actor_id, operation, idempotency_key)
);
