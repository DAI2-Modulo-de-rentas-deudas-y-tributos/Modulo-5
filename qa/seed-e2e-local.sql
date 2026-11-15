-- Datos ficticios e idempotentes para pruebas locales de M5.
-- No ejecutar en una base compartida ni de producción.
INSERT INTO taxpayer_reference
    (taxpayer_type, external_id, dni, cuit, canonical_citizen_id, display_name, external_status, created_at, updated_at)
VALUES
    ('CITIZEN', 'QA-M5-20261017-A', '99900001', '20999000014', NULL, 'QA Ciudadano A', 'ACTIVE', now(), now()),
    ('CITIZEN', 'QA-M5-20261017-B', '99900002', '20999000022', NULL, 'QA Ciudadano B', 'ACTIVE', now(), now()),
    ('ORGANIZATION', 'QA-M5-20261017-ORG', NULL, '30999999999', NULL, 'QA Organización M4', 'ACTIVE', now(), now()),
    ('CITIZEN', '4', NULL, NULL, 'f398dbcb-a2b4-49af-a724-3529c6ddd0f6', 'QA Ciudadano M1 4', 'ACTIVE', now(), now())
ON CONFLICT (taxpayer_type, external_id) DO NOTHING;

-- La identidad M1 4 fue confirmada oficialmente; nunca reemplazar una asociación contradictoria.
UPDATE taxpayer_reference
SET canonical_citizen_id = 'f398dbcb-a2b4-49af-a724-3529c6ddd0f6'
WHERE taxpayer_type = 'CITIZEN' AND external_id = '4' AND canonical_citizen_id IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM taxpayer_reference
        WHERE taxpayer_type = 'CITIZEN' AND external_id = '4'
          AND canonical_citizen_id = 'f398dbcb-a2b4-49af-a724-3529c6ddd0f6'
    ) THEN
        RAISE EXCEPTION 'El fixture M1 external_id=4 tiene una identidad canónica contradictoria';
    END IF;
END $$;

SELECT id, taxpayer_type, external_id
FROM taxpayer_reference
WHERE external_id IN ('QA-M5-20261017-A', 'QA-M5-20261017-B', 'QA-M5-20261017-ORG', '4')
ORDER BY external_id;
