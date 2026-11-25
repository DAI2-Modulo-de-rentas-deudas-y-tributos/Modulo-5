CREATE TABLE citizen_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id) ON DELETE CASCADE,
    citizen_id UUID NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_citizen_session_expiration ON citizen_session(expires_at);

CREATE TABLE citizen_login_window (
    origin_hash VARCHAR(64) PRIMARY KEY,
    window_end BIGINT NOT NULL,
    attempts INTEGER NOT NULL
);
