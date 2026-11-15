CREATE TABLE demo_user (
 id BIGSERIAL PRIMARY KEY,
 username VARCHAR(100) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(255) NOT NULL,
 role VARCHAR(20) NOT NULL,
 taxpayer_id BIGINT REFERENCES taxpayer_reference(id),
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT ck_demo_user_role CHECK (role IN ('RENTAS','SUPERVISOR','CASHIER','AUDITOR','TAXPAYER')),
 CONSTRAINT ck_demo_user_taxpayer CHECK (role <> 'TAXPAYER' OR taxpayer_id IS NOT NULL)
);

CREATE TABLE late_charge_rule (
 id BIGSERIAL PRIMARY KEY,
 code VARCHAR(60) NOT NULL UNIQUE,
 surcharge_rate NUMERIC(9,4) NOT NULL,
 daily_interest_rate NUMERIC(9,4) NOT NULL,
 active BOOLEAN NOT NULL,
 valid_from DATE NOT NULL,
 valid_until DATE,
 CONSTRAINT ck_late_charge_rates CHECK (surcharge_rate >= 0 AND daily_interest_rate >= 0)
);

INSERT INTO late_charge_rule(code,surcharge_rate,daily_interest_rate,active,valid_from)
VALUES ('GENERAL_MUNICIPAL',2.0000,0.1000,TRUE,DATE '2020-01-01');

CREATE TABLE late_charge_application (
 id BIGSERIAL PRIMARY KEY,
 debt_id BIGINT NOT NULL REFERENCES debt(id),
 rule_id BIGINT NOT NULL REFERENCES late_charge_rule(id),
 calculation_date DATE NOT NULL,
 days_overdue INTEGER NOT NULL,
 principal NUMERIC(19,2) NOT NULL,
 surcharge_rate NUMERIC(9,4) NOT NULL,
 surcharge_amount NUMERIC(19,2) NOT NULL,
 interest_rate NUMERIC(9,4) NOT NULL,
 interest_amount NUMERIC(19,2) NOT NULL,
 previous_adjustments NUMERIC(19,2) NOT NULL,
 total_adjustment NUMERIC(19,2) NOT NULL,
 updated_total NUMERIC(19,2) NOT NULL,
 applied_by VARCHAR(255) NOT NULL,
 applied_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(debt_id,rule_id,calculation_date),
 CONSTRAINT ck_late_charge_amounts CHECK (days_overdue > 0 AND principal >= 0 AND surcharge_amount >= 0 AND interest_amount >= 0 AND total_adjustment >= 0)
);

CREATE TABLE due_date_processing (
 id BIGSERIAL PRIMARY KEY,
 processing_date DATE NOT NULL UNIQUE,
 processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 debts_scanned INTEGER NOT NULL,
 debts_overdue INTEGER NOT NULL,
 adjustments_generated INTEGER NOT NULL,
 installments_overdue INTEGER NOT NULL,
 plans_defaulted INTEGER NOT NULL,
 skipped_already_processed INTEGER NOT NULL,
 errors INTEGER NOT NULL,
 processed_by VARCHAR(255) NOT NULL
);

CREATE TABLE electronic_reconciliation_batch (
 id BIGSERIAL PRIMARY KEY,
 external_batch_reference VARCHAR(120) NOT NULL UNIQUE,
 total_items INTEGER NOT NULL,
 reconciled_items INTEGER NOT NULL,
 observed_items INTEGER NOT NULL,
 not_found_items INTEGER NOT NULL,
 imported_by VARCHAR(255) NOT NULL,
 imported_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE electronic_reconciliation_item (
 id BIGSERIAL PRIMARY KEY,
 batch_id BIGINT NOT NULL REFERENCES electronic_reconciliation_batch(id),
 external_reference VARCHAR(120) NOT NULL UNIQUE,
 taxpayer_document VARCHAR(30) NOT NULL,
 amount NUMERIC(19,2) NOT NULL,
 paid_at TIMESTAMP WITH TIME ZONE NOT NULL,
 status VARCHAR(20) NOT NULL,
 matched_payment_id BIGINT REFERENCES payment(id),
 resolution_reason VARCHAR(1000),
 resolved_by VARCHAR(255),
 resolved_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT ck_reconciliation_status CHECK (status IN ('CONCILIATED','OBSERVED','NOT_FOUND')),
 CONSTRAINT ck_reconciliation_amount CHECK (amount > 0)
);

CREATE INDEX idx_late_charge_debt ON late_charge_application(debt_id);
CREATE INDEX idx_due_date_processing_date ON due_date_processing(processing_date);
CREATE INDEX idx_reconciliation_batch ON electronic_reconciliation_item(batch_id);
CREATE INDEX idx_reconciliation_status ON electronic_reconciliation_item(status);
