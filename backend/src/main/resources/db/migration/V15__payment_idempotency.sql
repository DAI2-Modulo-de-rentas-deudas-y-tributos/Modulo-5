ALTER TABLE payment ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);
-- Las columnas NULL no colisionan en los índices únicos de PostgreSQL ni H2,
-- por lo que el índice funciona tanto para solicitudes antiguas como nuevas.
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_idempotency_key ON payment(idempotency_key);
