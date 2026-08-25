CREATE TABLE taxpayer_reference (
 id BIGSERIAL PRIMARY KEY, taxpayer_type VARCHAR(30) NOT NULL, external_id VARCHAR(255) NOT NULL,
 dni VARCHAR(255), cuit VARCHAR(255), display_name VARCHAR(255) NOT NULL, external_status VARCHAR(30) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT uk_taxpayer_external UNIQUE(taxpayer_type, external_id)
);
CREATE TABLE tax_concept (
 id BIGSERIAL PRIMARY KEY, code VARCHAR(255) NOT NULL UNIQUE, name VARCHAR(255) NOT NULL, description VARCHAR(255),
 type VARCHAR(30) NOT NULL, origin_module VARCHAR(255) NOT NULL, active BOOLEAN NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE tax_configuration (
 id BIGSERIAL PRIMARY KEY, tax_concept_id BIGINT NOT NULL REFERENCES tax_concept(id), version INTEGER NOT NULL,
 calculation_type VARCHAR(30) NOT NULL, rate NUMERIC(19,4), fixed_amount NUMERIC(19,2), minimum_amount NUMERIC(19,2), maximum_amount NUMERIC(19,2),
 partial_payment_allowed BOOLEAN NOT NULL, payment_plan_allowed BOOLEAN NOT NULL, valid_from DATE NOT NULL, valid_until DATE,
 status VARCHAR(30) NOT NULL, created_by VARCHAR(255) NOT NULL, approved_by VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL, approved_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT uk_configuration_version UNIQUE(tax_concept_id,version), CONSTRAINT ck_configuration_dates CHECK(valid_until IS NULL OR valid_until>=valid_from)
);
CREATE TABLE liquidation (
 id BIGSERIAL PRIMARY KEY, taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id), tax_concept_id BIGINT NOT NULL REFERENCES tax_concept(id),
 tax_configuration_id BIGINT NOT NULL REFERENCES tax_configuration(id), configuration_version INTEGER NOT NULL, period VARCHAR(7) NOT NULL,
 taxable_base NUMERIC(19,2) NOT NULL, base_amount NUMERIC(19,2) NOT NULL, exemption_amount NUMERIC(19,2) NOT NULL, final_amount NUMERIC(19,2) NOT NULL,
 due_date DATE NOT NULL, status VARCHAR(30) NOT NULL, created_by VARCHAR(255) NOT NULL, issued_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE external_obligation (
 id BIGSERIAL PRIMARY KEY, source_module VARCHAR(255) NOT NULL, external_type VARCHAR(50) NOT NULL, external_reference_id VARCHAR(255) NOT NULL,
 source_event_id UUID NOT NULL, external_taxpayer_type VARCHAR(30) NOT NULL, external_taxpayer_id VARCHAR(255) NOT NULL,
 taxpayer_id BIGINT REFERENCES taxpayer_reference(id), tax_concept_id BIGINT REFERENCES tax_concept(id), amount NUMERIC(19,2) NOT NULL,
 due_date DATE NOT NULL, status VARCHAR(30) NOT NULL, error_message VARCHAR(255), retry_count INTEGER NOT NULL,
 received_at TIMESTAMP WITH TIME ZONE NOT NULL, processed_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT uk_external_business UNIQUE(source_module,external_type,external_reference_id)
);
CREATE TABLE debt (
 id BIGSERIAL PRIMARY KEY, taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id), tax_concept_id BIGINT NOT NULL REFERENCES tax_concept(id),
 origin_type VARCHAR(40) NOT NULL, liquidation_id BIGINT REFERENCES liquidation(id), external_obligation_id BIGINT REFERENCES external_obligation(id),
 configuration_id BIGINT REFERENCES tax_configuration(id), original_amount NUMERIC(19,2) NOT NULL, current_amount NUMERIC(19,2) NOT NULL,
 outstanding_balance NUMERIC(19,2) NOT NULL, due_date DATE NOT NULL, status VARCHAR(30) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT ck_debt_amounts CHECK(original_amount>=0 AND current_amount>=0 AND outstanding_balance>=0),
 CONSTRAINT ck_debt_origin CHECK((origin_type='LIQUIDATION' AND liquidation_id IS NOT NULL AND external_obligation_id IS NULL) OR (origin_type='EXTERNAL_OBLIGATION' AND external_obligation_id IS NOT NULL AND liquidation_id IS NULL))
);
CREATE TABLE payment (
 id BIGSERIAL PRIMARY KEY, taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id), payment_method VARCHAR(30) NOT NULL,
 amount NUMERIC(19,2) NOT NULL, allocated_amount NUMERIC(19,2) NOT NULL, unallocated_amount NUMERIC(19,2) NOT NULL,
 status VARCHAR(30) NOT NULL, allocation_status VARCHAR(30) NOT NULL, origin VARCHAR(30) NOT NULL, receipt_number VARCHAR(255) NOT NULL UNIQUE,
 registered_by VARCHAR(255) NOT NULL, paid_at TIMESTAMP WITH TIME ZONE NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT ck_payment_amount CHECK(amount>0 AND allocated_amount>=0 AND unallocated_amount>=0)
);
CREATE TABLE payment_allocation (
 id BIGSERIAL PRIMARY KEY, payment_id BIGINT NOT NULL REFERENCES payment(id), debt_id BIGINT NOT NULL REFERENCES debt(id),
 amount NUMERIC(19,2) NOT NULL, status VARCHAR(30) NOT NULL, allocated_by VARCHAR(255) NOT NULL, allocated_at TIMESTAMP WITH TIME ZONE NOT NULL, reversed_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT ck_allocation_amount CHECK(amount>0)
);
CREATE TABLE credit_balance (
 id BIGSERIAL PRIMARY KEY, taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id), source_payment_id BIGINT NOT NULL UNIQUE REFERENCES payment(id),
 original_amount NUMERIC(19,2) NOT NULL, available_amount NUMERIC(19,2) NOT NULL, status VARCHAR(30) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT ck_credit_amount CHECK(original_amount>0 AND available_amount>=0 AND available_amount<=original_amount)
);
CREATE TABLE payment_reversal_request (
 id BIGSERIAL PRIMARY KEY, payment_id BIGINT NOT NULL REFERENCES payment(id), reason VARCHAR(255) NOT NULL, status VARCHAR(30) NOT NULL,
 requested_by VARCHAR(255) NOT NULL, requested_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by VARCHAR(255), resolved_at TIMESTAMP WITH TIME ZONE, executed_by VARCHAR(255), executed_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE processed_event (event_id UUID PRIMARY KEY,event_type VARCHAR(255) NOT NULL,source_module VARCHAR(255) NOT NULL,received_at TIMESTAMP WITH TIME ZONE NOT NULL,processed_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE TABLE integration_event_log (
 id BIGSERIAL PRIMARY KEY,event_id UUID NOT NULL,event_type VARCHAR(255) NOT NULL,source_module VARCHAR(255) NOT NULL,direction VARCHAR(30) NOT NULL,status VARCHAR(30) NOT NULL,
 payload TEXT NOT NULL,retry_count INTEGER NOT NULL,error_message VARCHAR(255),occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,received_at TIMESTAMP WITH TIME ZONE NOT NULL,processed_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE outbox_event (
 id UUID PRIMARY KEY,event_type VARCHAR(255) NOT NULL,target_module VARCHAR(255) NOT NULL,aggregate_type VARCHAR(255) NOT NULL,aggregate_id VARCHAR(255) NOT NULL,
 payload TEXT NOT NULL,status VARCHAR(30) NOT NULL,retry_count INTEGER NOT NULL DEFAULT 0,created_at TIMESTAMP WITH TIME ZONE NOT NULL,published_at TIMESTAMP WITH TIME ZONE,last_attempt_at TIMESTAMP WITH TIME ZONE,error_message VARCHAR(255)
);
CREATE TABLE audit_entry (
 id BIGSERIAL PRIMARY KEY,entity_type VARCHAR(255) NOT NULL,entity_id VARCHAR(255) NOT NULL,action VARCHAR(255) NOT NULL,user_id VARCHAR(255) NOT NULL,user_role VARCHAR(255) NOT NULL,
 new_data TEXT,correlation_id VARCHAR(255),occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE payment_plan (
 id BIGSERIAL PRIMARY KEY,taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id),debt_id BIGINT NOT NULL REFERENCES debt(id),
 original_principal_amount NUMERIC(19,2) NOT NULL,financing_interest_amount NUMERIC(19,2) NOT NULL,total_plan_amount NUMERIC(19,2) NOT NULL,
 paid_amount NUMERIC(19,2) NOT NULL,outstanding_plan_amount NUMERIC(19,2) NOT NULL,installment_count INTEGER NOT NULL,status VARCHAR(30) NOT NULL,
 granted_by VARCHAR(255) NOT NULL,granted_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE installment (
 id BIGSERIAL PRIMARY KEY,payment_plan_id BIGINT NOT NULL REFERENCES payment_plan(id),number INTEGER NOT NULL,principal_amount NUMERIC(19,2) NOT NULL,
 interest_amount NUMERIC(19,2) NOT NULL,total_amount NUMERIC(19,2) NOT NULL,paid_amount NUMERIC(19,2) NOT NULL,outstanding_amount NUMERIC(19,2) NOT NULL,
 due_date DATE NOT NULL,status VARCHAR(30) NOT NULL,UNIQUE(payment_plan_id,number)
);
CREATE TABLE exemption_request (
 id BIGSERIAL PRIMARY KEY,taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id),tax_concept_id BIGINT NOT NULL REFERENCES tax_concept(id),reason VARCHAR(255) NOT NULL,
 requested_percentage NUMERIC(5,2) NOT NULL,requested_from DATE NOT NULL,requested_until DATE,status VARCHAR(40) NOT NULL,requested_by VARCHAR(255) NOT NULL,requested_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE exemption (
 id BIGSERIAL PRIMARY KEY,request_id BIGINT NOT NULL UNIQUE REFERENCES exemption_request(id),taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id),
 tax_concept_id BIGINT NOT NULL REFERENCES tax_concept(id),percentage NUMERIC(5,2) NOT NULL,valid_from DATE NOT NULL,valid_until DATE,status VARCHAR(30) NOT NULL,
 approved_by VARCHAR(255) NOT NULL,approved_at TIMESTAMP WITH TIME ZONE NOT NULL,CONSTRAINT ck_exemption_percentage CHECK(percentage>0 AND percentage<=100)
);
CREATE INDEX idx_debt_taxpayer ON debt(taxpayer_id); CREATE INDEX idx_debt_status ON debt(status); CREATE INDEX idx_payment_taxpayer ON payment(taxpayer_id); CREATE INDEX idx_payment_paid_at ON payment(paid_at);
CREATE INDEX idx_audit_entity ON audit_entry(entity_type,entity_id); CREATE INDEX idx_integration_status ON integration_event_log(status); CREATE INDEX idx_outbox_status ON outbox_event(status);
