CREATE TABLE ticket_case (
 id BIGSERIAL PRIMARY KEY, external_ticket_id VARCHAR(255) NOT NULL UNIQUE, taxpayer_id BIGINT REFERENCES taxpayer_reference(id),
 external_citizen_id VARCHAR(255), category VARCHAR(255) NOT NULL, description TEXT NOT NULL,
 priority VARCHAR(30) NOT NULL, status VARCHAR(40) NOT NULL, assigned_to VARCHAR(255),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 completed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE ticket_case_update (
 id BIGSERIAL PRIMARY KEY, ticket_case_id BIGINT NOT NULL REFERENCES ticket_case(id), type VARCHAR(40) NOT NULL,
 message TEXT NOT NULL, created_by VARCHAR(255) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE social_benefit_reference (
 id BIGSERIAL PRIMARY KEY, external_benefit_id VARCHAR(255) NOT NULL UNIQUE, taxpayer_id BIGINT REFERENCES taxpayer_reference(id),
 external_citizen_id VARCHAR(255) NOT NULL, benefit_type VARCHAR(255) NOT NULL, external_status VARCHAR(40) NOT NULL,
 discount_percentage NUMERIC(5,2), valid_from DATE NOT NULL, valid_until DATE, source_event_id UUID NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE social_benefit_tax_concept (
 id BIGSERIAL PRIMARY KEY, social_benefit_id BIGINT NOT NULL REFERENCES social_benefit_reference(id),
 tax_concept_id BIGINT NOT NULL REFERENCES tax_concept(id), UNIQUE(social_benefit_id,tax_concept_id)
);

ALTER TABLE integration_event_log ADD COLUMN target_module VARCHAR(255);
ALTER TABLE integration_event_log ADD COLUMN last_retry_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE audit_entry ADD COLUMN previous_data TEXT;

CREATE INDEX idx_ticket_status ON ticket_case(status);
CREATE INDEX idx_benefit_taxpayer ON social_benefit_reference(taxpayer_id);
CREATE INDEX idx_external_obligation_source_status ON external_obligation(source_module,status);
