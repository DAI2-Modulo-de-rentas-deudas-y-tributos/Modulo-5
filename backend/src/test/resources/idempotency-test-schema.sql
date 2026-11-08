CREATE TABLE IF NOT EXISTS idempotency_request (
    actor_id VARCHAR(255) NOT NULL,
    operation VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_idempotency_request PRIMARY KEY (actor_id, operation, idempotency_key)
);
