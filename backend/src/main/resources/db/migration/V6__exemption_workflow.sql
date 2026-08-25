ALTER TABLE exemption_request ADD COLUMN reviewed_by VARCHAR(255);
ALTER TABLE exemption_request ADD COLUMN review_started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE exemption_request ADD COLUMN resolution_submitted_by VARCHAR(255);
ALTER TABLE exemption_request ADD COLUMN resolution_submitted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE exemption_request ADD COLUMN resolved_by VARCHAR(255);
ALTER TABLE exemption_request ADD COLUMN resolved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE exemption_request ADD COLUMN resolution_reason VARCHAR(1000);

CREATE TABLE exemption_request_document (
 id BIGSERIAL PRIMARY KEY, exemption_request_id BIGINT NOT NULL REFERENCES exemption_request(id),
 external_document_id VARCHAR(255) NOT NULL, document_type VARCHAR(255) NOT NULL, file_name VARCHAR(255),
 uploaded_by VARCHAR(255) NOT NULL, uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE exemption ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_exemption_request_taxpayer ON exemption_request(taxpayer_id);
