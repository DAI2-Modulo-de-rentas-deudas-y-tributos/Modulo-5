ALTER TABLE payment_allocation ADD COLUMN target_type VARCHAR(30) NOT NULL DEFAULT 'DEBT';
ALTER TABLE payment_allocation ADD COLUMN installment_id BIGINT REFERENCES installment(id);
ALTER TABLE payment_allocation ALTER COLUMN debt_id DROP NOT NULL;
ALTER TABLE payment_allocation ADD CONSTRAINT ck_allocation_target CHECK (
 (target_type='DEBT' AND debt_id IS NOT NULL AND installment_id IS NULL) OR
 (target_type='INSTALLMENT' AND installment_id IS NOT NULL AND debt_id IS NULL)
);

CREATE TABLE payment_plan_configuration (
 id BIGSERIAL PRIMARY KEY, version INTEGER NOT NULL UNIQUE, minimum_installments INTEGER NOT NULL,
 maximum_installments INTEGER NOT NULL, minimum_down_payment_percentage NUMERIC(5,2) NOT NULL,
 interest_rate NUMERIC(7,4) NOT NULL, grace_days INTEGER NOT NULL, max_overdue_installments INTEGER NOT NULL,
 partial_installment_payment_allowed BOOLEAN NOT NULL, refinancing_allowed BOOLEAN NOT NULL,
 max_refinancing_count INTEGER NOT NULL, valid_from DATE NOT NULL, valid_until DATE, active BOOLEAN NOT NULL,
 created_by VARCHAR(255) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT ck_plan_config_installments CHECK (minimum_installments > 0 AND maximum_installments >= minimum_installments),
 CONSTRAINT ck_plan_config_down_payment CHECK (minimum_down_payment_percentage >= 0 AND minimum_down_payment_percentage <= 100)
);

CREATE TABLE payment_plan_request (
 id BIGSERIAL PRIMARY KEY, taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id), requested_installments INTEGER NOT NULL,
 total_debt_at_request NUMERIC(19,2) NOT NULL, estimated_down_payment NUMERIC(19,2) NOT NULL,
 estimated_financed_amount NUMERIC(19,2) NOT NULL, estimated_interest NUMERIC(19,2) NOT NULL,
 estimated_total_amount NUMERIC(19,2) NOT NULL, exceptional BOOLEAN NOT NULL, exception_reason VARCHAR(1000),
 exception_approved BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(40) NOT NULL, requested_by VARCHAR(255) NOT NULL,
 requested_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by VARCHAR(255), resolved_at TIMESTAMP WITH TIME ZONE,
 resolution_reason VARCHAR(1000), payment_plan_id BIGINT
);

CREATE TABLE payment_plan_request_debt (
 id BIGSERIAL PRIMARY KEY, request_id BIGINT NOT NULL REFERENCES payment_plan_request(id), debt_id BIGINT NOT NULL REFERENCES debt(id),
 balance_at_request NUMERIC(19,2) NOT NULL, UNIQUE(request_id,debt_id)
);

ALTER TABLE payment_plan ALTER COLUMN debt_id DROP NOT NULL;
ALTER TABLE payment_plan ADD COLUMN request_id BIGINT REFERENCES payment_plan_request(id);
ALTER TABLE payment_plan ADD COLUMN configuration_id BIGINT REFERENCES payment_plan_configuration(id);
ALTER TABLE payment_plan ADD COLUMN configuration_version INTEGER;
ALTER TABLE payment_plan ADD COLUMN down_payment_amount NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE payment_plan ADD COLUMN financed_principal_amount NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE payment_plan ADD COLUMN refinancing_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_plan ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE payment_plan ADD COLUMN expired_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE payment_plan ADD COLUMN refinanced_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE payment_plan_debt (
 id BIGSERIAL PRIMARY KEY, payment_plan_id BIGINT NOT NULL REFERENCES payment_plan(id), debt_id BIGINT NOT NULL REFERENCES debt(id),
 included_principal_amount NUMERIC(19,2) NOT NULL, principal_paid_amount NUMERIC(19,2) NOT NULL,
 remaining_principal_amount NUMERIC(19,2) NOT NULL, status VARCHAR(30) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(payment_plan_id,debt_id)
);

ALTER TABLE installment ADD COLUMN type VARCHAR(30) NOT NULL DEFAULT 'REGULAR';
ALTER TABLE installment ADD COLUMN paid_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE plan_expiration_request (
 id BIGSERIAL PRIMARY KEY, payment_plan_id BIGINT NOT NULL REFERENCES payment_plan(id), reason VARCHAR(1000) NOT NULL,
 status VARCHAR(40) NOT NULL, requested_by VARCHAR(255) NOT NULL, requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
 resolved_by VARCHAR(255), resolved_at TIMESTAMP WITH TIME ZONE, resolution_observation VARCHAR(1000)
);

CREATE TABLE refinancing_request (
 id BIGSERIAL PRIMARY KEY, original_plan_id BIGINT NOT NULL REFERENCES payment_plan(id), taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id),
 requested_installments INTEGER NOT NULL, outstanding_principal_at_request NUMERIC(19,2) NOT NULL,
 estimated_interest NUMERIC(19,2) NOT NULL, estimated_total_amount NUMERIC(19,2) NOT NULL,
 exceptional BOOLEAN NOT NULL, exception_reason VARCHAR(1000), exception_approved BOOLEAN NOT NULL DEFAULT FALSE,
 status VARCHAR(40) NOT NULL, requested_by VARCHAR(255) NOT NULL, requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
 resolved_by VARCHAR(255), resolved_at TIMESTAMP WITH TIME ZONE, new_payment_plan_id BIGINT REFERENCES payment_plan(id)
);

CREATE INDEX idx_plan_request_taxpayer ON payment_plan_request(taxpayer_id);
CREATE INDEX idx_plan_debt_debt_status ON payment_plan_debt(debt_id,status);
CREATE INDEX idx_plan_expiration_status ON plan_expiration_request(status);
CREATE INDEX idx_refinancing_status ON refinancing_request(status);
