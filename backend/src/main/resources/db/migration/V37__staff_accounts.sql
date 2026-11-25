-- Cuentas propias del personal de Rentas (ADR 0013). Existen en todos los perfiles, incluido prod.
-- Se cargan desde STAFF_ACCOUNTS con hashes BCrypt; nunca se guardan contraseñas ni tokens en claro.
CREATE TABLE staff_account (
 id BIGSERIAL PRIMARY KEY,
 username VARCHAR(60) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(160) NOT NULL,
 role VARCHAR(20) NOT NULL,
 integration_actor_id UUID NOT NULL UNIQUE,
 active BOOLEAN NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT ck_staff_account_role CHECK (role IN ('RENTAS','SUPERVISOR','CASHIER','AUDITOR'))
);

CREATE TABLE staff_session (
 id BIGSERIAL PRIMARY KEY,
 staff_account_id BIGINT NOT NULL REFERENCES staff_account(id),
 token_hash VARCHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 revoked_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT ck_staff_session_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_staff_session_account ON staff_session(staff_account_id);
