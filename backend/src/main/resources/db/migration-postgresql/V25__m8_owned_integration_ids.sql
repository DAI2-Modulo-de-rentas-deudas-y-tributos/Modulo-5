-- PROPOSAL ONLY: do not move into db/migration or start the normal backend before approval.
-- M5-owned IDs are assigned once per existing row; all internal BIGINT primary keys remain unchanged.
ALTER TABLE debt ADD COLUMN integration_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE debt ADD CONSTRAINT uq_debt_integration_id UNIQUE (integration_id);

ALTER TABLE liquidation ADD COLUMN integration_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE liquidation ADD CONSTRAINT uq_liquidation_integration_id UNIQUE (integration_id);

ALTER TABLE exemption_request ADD COLUMN integration_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE exemption_request ADD CONSTRAINT uq_exemption_request_integration_id UNIQUE (integration_id);

ALTER TABLE exemption ADD COLUMN integration_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE exemption ADD CONSTRAINT uq_exemption_integration_id UNIQUE (integration_id);

-- Remains NULL until M1 supplies an authoritative legacy-ID -> canonical-UUID correspondence.
-- No citizen identity is fabricated or backfilled by this script.
ALTER TABLE taxpayer_reference ADD COLUMN canonical_citizen_id UUID;
ALTER TABLE taxpayer_reference ADD CONSTRAINT uq_taxpayer_canonical_citizen_id UNIQUE (canonical_citizen_id);
