-- Test-only H2 equivalent of the PostgreSQL proposal. Never included in application Flyway locations.
ALTER TABLE debt ADD COLUMN integration_id UUID DEFAULT RANDOM_UUID() NOT NULL;
ALTER TABLE debt ADD CONSTRAINT uq_debt_integration_id UNIQUE (integration_id);
ALTER TABLE liquidation ADD COLUMN integration_id UUID DEFAULT RANDOM_UUID() NOT NULL;
ALTER TABLE liquidation ADD CONSTRAINT uq_liquidation_integration_id UNIQUE (integration_id);
ALTER TABLE exemption_request ADD COLUMN integration_id UUID DEFAULT RANDOM_UUID() NOT NULL;
ALTER TABLE exemption_request ADD CONSTRAINT uq_exemption_request_integration_id UNIQUE (integration_id);
ALTER TABLE exemption ADD COLUMN integration_id UUID DEFAULT RANDOM_UUID() NOT NULL;
ALTER TABLE exemption ADD CONSTRAINT uq_exemption_integration_id UNIQUE (integration_id);
ALTER TABLE taxpayer_reference ADD COLUMN canonical_citizen_id UUID;
ALTER TABLE taxpayer_reference ADD CONSTRAINT uq_taxpayer_canonical_citizen_id UNIQUE (canonical_citizen_id);
