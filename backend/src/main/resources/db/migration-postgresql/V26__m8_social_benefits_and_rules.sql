-- PROPOSAL ONLY: run after V25, on an isolated database first. Not in Flyway's active locations.
ALTER TABLE social_benefit_reference ALTER COLUMN valid_from DROP NOT NULL;
ALTER TABLE social_benefit_reference ADD COLUMN external_reason VARCHAR(255);
ALTER TABLE social_benefit_reference ADD COLUMN resolution_status VARCHAR(40) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE social_benefit_reference ADD COLUMN source_payload_hash VARCHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000';
ALTER TABLE social_benefit_reference ADD COLUMN last_correlation_id UUID;
ALTER TABLE social_benefit_reference ADD COLUMN received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();

CREATE TABLE social_benefit_event_history (
 id BIGSERIAL PRIMARY KEY,
 source_event_id UUID NOT NULL UNIQUE,
 external_benefit_id VARCHAR(255) NOT NULL,
 source_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 external_status VARCHAR(255) NOT NULL,
 outcome VARCHAR(255) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_social_benefit_history_benefit ON social_benefit_event_history(external_benefit_id,source_updated_at);

CREATE TABLE social_benefit_rule (
 id BIGSERIAL PRIMARY KEY,
 program_id UUID NOT NULL,
 tax_concept_id BIGINT NOT NULL REFERENCES tax_concept(id),
 version INTEGER NOT NULL CHECK (version > 0),
 percentage NUMERIC(5,2) NOT NULL CHECK (percentage >= 0 AND percentage <= 100),
 valid_from DATE NOT NULL,
 valid_until DATE,
 enabled BOOLEAN NOT NULL DEFAULT false,
 status VARCHAR(255) NOT NULL CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED')),
 proposed_by VARCHAR(255) NOT NULL,
 approved_by VARCHAR(255),
 approved_actor_id UUID,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 approved_at TIMESTAMP WITH TIME ZONE,
 disabled_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT uq_social_rule_version UNIQUE (program_id,tax_concept_id,version),
 CONSTRAINT ck_social_rule_dates CHECK (valid_until IS NULL OR valid_until >= valid_from),
 CONSTRAINT ck_social_rule_approval CHECK (status <> 'APPROVED' OR (approved_by IS NOT NULL AND approved_actor_id IS NOT NULL AND approved_at IS NOT NULL))
);
CREATE INDEX idx_social_rule_lookup ON social_benefit_rule(program_id,tax_concept_id,status,enabled);

ALTER TABLE liquidation_component ADD COLUMN source_benefit_id VARCHAR(255);
ALTER TABLE liquidation_component ADD COLUMN social_rule_id BIGINT REFERENCES social_benefit_rule(id);
ALTER TABLE liquidation_component ADD COLUMN social_rule_version INTEGER;
