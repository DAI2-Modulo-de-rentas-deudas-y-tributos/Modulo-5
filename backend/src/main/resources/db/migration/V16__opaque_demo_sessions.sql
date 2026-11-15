CREATE TABLE demo_auth_session (
 id BIGSERIAL PRIMARY KEY,
 demo_user_id BIGINT NOT NULL REFERENCES demo_user(id),
 token_hash VARCHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 revoked_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT ck_demo_auth_session_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_demo_auth_session_user ON demo_auth_session(demo_user_id);

-- Fila estable para serializar el primer bootstrap incluso con demo_user vacío.
CREATE TABLE demo_bootstrap_lock (
 id INTEGER PRIMARY KEY CHECK (id = 1)
);
INSERT INTO demo_bootstrap_lock (id) VALUES (1);
