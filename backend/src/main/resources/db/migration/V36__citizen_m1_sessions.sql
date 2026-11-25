-- Sesiones opacas de ciudadanos autenticados por M1 (ADR 0012). Existen en todos los perfiles,
-- incluido prod: no son parte del esquema DEMO. Sólo se guarda el hash del token.
-- Reemplaza las tablas de V35 (login local de #197, retirado): sólo tenían sesiones de prueba
-- de corta duración, así que se descartan.
DROP TABLE IF EXISTS citizen_login_window;
DROP TABLE IF EXISTS citizen_session;

CREATE TABLE citizen_session (
 id BIGSERIAL PRIMARY KEY,
 taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id),
 canonical_citizen_id UUID NOT NULL,
 token_hash VARCHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 revoked_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT ck_citizen_session_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_citizen_session_taxpayer ON citizen_session(taxpayer_id);
