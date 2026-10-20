-- Datos ficticios e idempotentes para pruebas locales de M5.
-- No ejecutar en una base compartida ni de producción.
INSERT INTO taxpayer_reference
    (taxpayer_type, external_id, dni, cuit, display_name, external_status, created_at, updated_at)
VALUES
    ('CITIZEN', 'QA-M5-20261017-A', '99900001', '20999000014', 'QA Ciudadano A', 'ACTIVE', now(), now()),
    ('CITIZEN', 'QA-M5-20261017-B', '99900002', '20999000022', 'QA Ciudadano B', 'ACTIVE', now(), now()),
    ('ORGANIZATION', 'QA-M5-20261017-ORG', NULL, '30999999999', 'QA Organización M4', 'ACTIVE', now(), now())
ON CONFLICT (taxpayer_type, external_id) DO NOTHING;

SELECT id, taxpayer_type, external_id
FROM taxpayer_reference
WHERE external_id IN ('QA-M5-20261017-A', 'QA-M5-20261017-B', 'QA-M5-20261017-ORG')
ORDER BY external_id;
