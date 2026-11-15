CREATE TABLE demo_login_origin_attempt (
    origin_key_hash VARCHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    failures BIGINT NOT NULL CHECK (failures > 0)
);

CREATE INDEX idx_demo_login_origin_attempt_window_started_at
    ON demo_login_origin_attempt (window_started_at);
