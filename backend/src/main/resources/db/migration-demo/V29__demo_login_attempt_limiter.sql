CREATE TABLE demo_login_attempt (
    attempt_key_hash CHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 5)
);

CREATE INDEX idx_demo_login_attempt_window_started_at
    ON demo_login_attempt (window_started_at);
