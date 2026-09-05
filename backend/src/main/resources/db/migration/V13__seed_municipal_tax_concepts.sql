INSERT INTO tax_concept (code, name, description, type, origin_module, active, created_at, updated_at)
SELECT 'TASA_SERVICIOS', 'Tasa de servicios generales',
       'Tasa municipal por la prestación de servicios generales.',
       'FEE', 'M5', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM tax_concept WHERE code = 'TASA_SERVICIOS');

INSERT INTO tax_concept (code, name, description, type, origin_module, active, created_at, updated_at)
SELECT 'ABL', 'Alumbrado, barrido y limpieza',
       'Tasa municipal de alumbrado, barrido y limpieza.',
       'FEE', 'M5', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM tax_concept WHERE code = 'ABL');

INSERT INTO tax_concept (code, name, description, type, origin_module, active, created_at, updated_at)
SELECT 'PATENTE', 'Patente automotor',
       'Tributo municipal asociado a vehículos automotores.',
       'FEE', 'M5', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM tax_concept WHERE code = 'PATENTE');
