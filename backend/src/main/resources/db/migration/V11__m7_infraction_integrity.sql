ALTER TABLE taxpayer_reference ADD CONSTRAINT uk_taxpayer_type_dni UNIQUE (taxpayer_type,dni);
ALTER TABLE taxpayer_reference ADD CONSTRAINT uk_taxpayer_type_cuit UNIQUE (taxpayer_type,cuit);
ALTER TABLE debt ADD CONSTRAINT uk_debt_external_obligation UNIQUE (external_obligation_id);
