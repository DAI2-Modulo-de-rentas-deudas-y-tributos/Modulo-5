CREATE TABLE adjustment_request (
 id BIGSERIAL PRIMARY KEY, debt_id BIGINT NOT NULL REFERENCES debt(id), type VARCHAR(30) NOT NULL,
 amount NUMERIC(19,2) NOT NULL, reason VARCHAR(1000) NOT NULL, status VARCHAR(40) NOT NULL,
 requested_by VARCHAR(255) NOT NULL, requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
 resolved_by VARCHAR(255), resolved_at TIMESTAMP WITH TIME ZONE, resolution_reason VARCHAR(1000),
 previous_debt_amount NUMERIC(19,2), new_debt_amount NUMERIC(19,2)
);

CREATE TABLE liquidation_run (
 id BIGSERIAL PRIMARY KEY, tax_concept_id BIGINT NOT NULL REFERENCES tax_concept(id), period VARCHAR(7) NOT NULL,
 due_date DATE NOT NULL, configuration_id BIGINT REFERENCES tax_configuration(id), configuration_version INTEGER,
 status VARCHAR(40) NOT NULL, total_items INTEGER NOT NULL, valid_items INTEGER NOT NULL, error_items INTEGER NOT NULL,
 estimated_total_amount NUMERIC(19,2) NOT NULL, created_by VARCHAR(255) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, submitted_at TIMESTAMP WITH TIME ZONE,
 resolved_by VARCHAR(255), resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE liquidation_run_item (
 id BIGSERIAL PRIMARY KEY, liquidation_run_id BIGINT NOT NULL REFERENCES liquidation_run(id),
 taxpayer_id BIGINT NOT NULL, taxable_base NUMERIC(19,2) NOT NULL, preview_amount NUMERIC(19,2),
 status VARCHAR(30) NOT NULL, error_code VARCHAR(255), error_message VARCHAR(1000), liquidation_id BIGINT REFERENCES liquidation(id)
);

CREATE INDEX idx_adjustment_debt ON adjustment_request(debt_id);
CREATE INDEX idx_liquidation_run_status ON liquidation_run(status);
CREATE INDEX idx_liquidation_run_item_run ON liquidation_run_item(liquidation_run_id);
