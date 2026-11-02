ALTER TABLE tax_configuration ADD COLUMN submitted_by VARCHAR(255);
ALTER TABLE liquidation_run ADD COLUMN submitted_by VARCHAR(255);
ALTER TABLE social_benefit_rule ADD COLUMN submitted_by VARCHAR(255);
ALTER TABLE payment_plan_request ADD COLUMN exception_submitted_by VARCHAR(255);
ALTER TABLE refinancing_request ADD COLUMN exception_submitted_by VARCHAR(255);
