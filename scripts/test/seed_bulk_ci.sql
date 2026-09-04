-- Sólo para PostgreSQL efímero del job CI. No ejecutar sobre DEV/TEST compartidos.
INSERT INTO taxpayer_reference
    (taxpayer_type, external_id, dni, display_name, external_status, created_at, updated_at)
SELECT 'CITIZEN', 'CI_BULK_' || number, 'CI_BULK_' || number,
       'Contribuyente QA masivo ' || number, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM generate_series(1, 200) AS number
ON CONFLICT (taxpayer_type, external_id) DO NOTHING;
