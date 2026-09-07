ALTER TABLE payment_allocation ADD COLUMN principal_applied NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE payment_allocation ADD COLUMN interest_applied NUMERIC(19,2) NOT NULL DEFAULT 0;

UPDATE payment_allocation
SET principal_applied = amount
WHERE target_type = 'DEBT';

UPDATE payment_allocation
SET principal_applied = ROUND(
    amount * (SELECT principal_amount FROM installment WHERE installment.id = payment_allocation.installment_id)
    / NULLIF((SELECT total_amount FROM installment WHERE installment.id = payment_allocation.installment_id), 0),
    2
)
WHERE target_type = 'INSTALLMENT';

UPDATE payment_allocation
SET interest_applied = amount - principal_applied
WHERE target_type = 'INSTALLMENT';

UPDATE payment
SET allocated_amount = amount - unallocated_amount
WHERE amount <> allocated_amount + unallocated_amount;

ALTER TABLE tax_configuration ADD CONSTRAINT ck_configuration_amounts CHECK (
    (rate IS NULL OR rate >= 0)
    AND (fixed_amount IS NULL OR fixed_amount >= 0)
    AND (minimum_amount IS NULL OR minimum_amount >= 0)
    AND (maximum_amount IS NULL OR maximum_amount >= 0)
    AND (minimum_amount IS NULL OR maximum_amount IS NULL OR maximum_amount >= minimum_amount)
    AND (calculation_type <> 'FIXED' OR fixed_amount IS NOT NULL)
    AND (calculation_type <> 'PERCENTAGE' OR rate IS NOT NULL)
);

ALTER TABLE liquidation ADD CONSTRAINT ck_liquidation_amounts CHECK (
    taxable_base >= 0 AND base_amount >= 0 AND discount_amount >= 0 AND exemption_amount >= 0
    AND surcharge_amount >= 0 AND interest_amount >= 0 AND final_amount >= 0
    AND final_amount = base_amount - discount_amount - exemption_amount + surcharge_amount + interest_amount
);

ALTER TABLE external_obligation ADD CONSTRAINT ck_external_obligation_amount CHECK (amount > 0 AND retry_count >= 0);
ALTER TABLE payment ADD CONSTRAINT ck_payment_consistency CHECK (amount = allocated_amount + unallocated_amount);
ALTER TABLE payment_allocation ADD CONSTRAINT ck_allocation_breakdown CHECK (
    principal_applied >= 0 AND interest_applied >= 0
    AND principal_applied + interest_applied = amount
    AND ((target_type = 'DEBT' AND principal_applied = amount AND interest_applied = 0) OR target_type = 'INSTALLMENT')
);
ALTER TABLE credit_balance_application ADD CONSTRAINT ck_credit_application_amount CHECK (amount > 0);
ALTER TABLE adjustment_request ADD CONSTRAINT ck_adjustment_amount CHECK (amount > 0);

ALTER TABLE payment_plan_configuration ADD CONSTRAINT ck_plan_configuration_values CHECK (
    interest_rate >= 0 AND grace_days >= 0 AND max_overdue_installments >= 0 AND max_refinancing_count >= 0
    AND (valid_until IS NULL OR valid_until >= valid_from)
);
ALTER TABLE payment_plan ADD CONSTRAINT ck_payment_plan_amounts CHECK (
    original_principal_amount >= 0 AND down_payment_amount >= 0 AND financed_principal_amount >= 0
    AND financing_interest_amount >= 0 AND total_plan_amount >= 0 AND paid_amount >= 0 AND outstanding_plan_amount >= 0
    AND down_payment_amount + financed_principal_amount = original_principal_amount
    AND original_principal_amount + financing_interest_amount = total_plan_amount
    AND paid_amount + outstanding_plan_amount = total_plan_amount
    AND installment_count > 0 AND refinancing_count >= 0
);
ALTER TABLE payment_plan_debt ADD CONSTRAINT ck_payment_plan_debt_amounts CHECK (
    included_principal_amount >= 0 AND principal_paid_amount >= 0 AND remaining_principal_amount >= 0
    AND principal_paid_amount + remaining_principal_amount = included_principal_amount
);
ALTER TABLE installment ADD CONSTRAINT ck_installment_amounts CHECK (
    number >= 0 AND principal_amount >= 0 AND interest_amount >= 0 AND total_amount >= 0
    AND paid_amount >= 0 AND outstanding_amount >= 0
    AND principal_amount + interest_amount = total_amount
    AND paid_amount + outstanding_amount = total_amount
);
ALTER TABLE exemption_request ADD CONSTRAINT ck_exemption_request_values CHECK (
    requested_percentage > 0 AND requested_percentage <= 100
    AND (requested_until IS NULL OR requested_until >= requested_from)
);
ALTER TABLE liquidation_run ADD CONSTRAINT ck_liquidation_run_counts CHECK (
    total_items >= 0 AND valid_items >= 0 AND error_items >= 0 AND valid_items + error_items <= total_items
    AND estimated_total_amount >= 0
);
ALTER TABLE liquidation_run_item ADD CONSTRAINT ck_liquidation_run_item_amounts CHECK (
    taxable_base >= 0 AND (preview_amount IS NULL OR preview_amount >= 0)
);
ALTER TABLE integration_event_log ADD CONSTRAINT ck_integration_retry_count CHECK (retry_count >= 0);
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_retry_count CHECK (retry_count >= 0);

CREATE INDEX idx_debt_concept ON debt(tax_concept_id);
CREATE INDEX idx_debt_due_date ON debt(due_date);
CREATE INDEX idx_liquidation_taxpayer ON liquidation(taxpayer_id);
CREATE INDEX idx_liquidation_concept_period ON liquidation(tax_concept_id, period);
CREATE INDEX idx_payment_status ON payment(status);
CREATE INDEX idx_payment_plan_taxpayer_status ON payment_plan(taxpayer_id, status);
CREATE INDEX idx_installment_due_status ON installment(due_date, status);
CREATE INDEX idx_exemption_taxpayer_concept ON exemption(taxpayer_id, tax_concept_id);
CREATE INDEX idx_integration_event_id ON integration_event_log(event_id);
