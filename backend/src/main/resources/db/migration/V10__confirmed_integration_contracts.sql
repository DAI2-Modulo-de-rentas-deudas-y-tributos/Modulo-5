ALTER TABLE processed_event ADD COLUMN external_event_id VARCHAR(255);
UPDATE processed_event SET external_event_id=CAST(event_id AS VARCHAR);
ALTER TABLE processed_event ALTER COLUMN external_event_id SET NOT NULL;
ALTER TABLE processed_event ADD CONSTRAINT uk_processed_external_event UNIQUE (external_event_id);

ALTER TABLE integration_event_log ADD COLUMN external_event_id VARCHAR(255);
UPDATE integration_event_log SET external_event_id=CAST(event_id AS VARCHAR);
ALTER TABLE integration_event_log ALTER COLUMN external_event_id SET NOT NULL;
CREATE INDEX idx_integration_external_event ON integration_event_log(external_event_id);

ALTER TABLE social_benefit_reference ADD COLUMN calculated_status VARCHAR(40);
UPDATE social_benefit_reference SET calculated_status=CASE WHEN external_status IN ('ACTIVE','SUSPENDED','EXPIRED','CANCELLED') THEN external_status ELSE 'CANCELLED' END;
ALTER TABLE social_benefit_reference ALTER COLUMN calculated_status SET NOT NULL;
ALTER TABLE social_benefit_reference ADD COLUMN external_application_id VARCHAR(255);
ALTER TABLE social_benefit_reference ADD COLUMN external_program_id VARCHAR(255);
ALTER TABLE social_benefit_reference ADD COLUMN program_name VARCHAR(255);
ALTER TABLE social_benefit_reference ADD COLUMN benefits_payload TEXT;
UPDATE social_benefit_reference SET benefits_payload='[]';
ALTER TABLE social_benefit_reference ALTER COLUMN benefits_payload SET NOT NULL;
ALTER TABLE social_benefit_reference ADD COLUMN external_source_event_id VARCHAR(255);
UPDATE social_benefit_reference SET external_source_event_id=CAST(source_event_id AS VARCHAR);
ALTER TABLE social_benefit_reference ALTER COLUMN external_source_event_id SET NOT NULL;

CREATE TABLE taxpayer_representation_reference (
 id BIGSERIAL PRIMARY KEY,
 external_representation_id VARCHAR(255) NOT NULL UNIQUE,
 external_person_id VARCHAR(255) NOT NULL,
 external_organization_id VARCHAR(255) NOT NULL,
 scope VARCHAR(255) NOT NULL,
 valid_from DATE,
 valid_until DATE,
 status VARCHAR(40) NOT NULL,
 source_event_id VARCHAR(255) NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
