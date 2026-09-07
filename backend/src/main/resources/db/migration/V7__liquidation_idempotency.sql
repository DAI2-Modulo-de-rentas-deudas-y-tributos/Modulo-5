ALTER TABLE liquidation ADD CONSTRAINT uk_liquidation_business UNIQUE(taxpayer_id,tax_concept_id,period);
