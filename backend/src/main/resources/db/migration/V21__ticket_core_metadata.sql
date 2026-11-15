ALTER TABLE ticket_case ADD COLUMN public_id VARCHAR(255);
ALTER TABLE ticket_case ADD COLUMN correlation_id VARCHAR(36);
ALTER TABLE ticket_case ADD COLUMN last_source_event_id VARCHAR(36);
ALTER TABLE ticket_case ADD COLUMN last_external_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE integration_event_log ADD COLUMN business_key VARCHAR(512);
CREATE UNIQUE INDEX idx_integration_business_key ON integration_event_log(business_key);
