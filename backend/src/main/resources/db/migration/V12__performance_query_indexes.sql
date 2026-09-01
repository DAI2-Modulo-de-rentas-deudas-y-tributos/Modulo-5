CREATE INDEX idx_payment_status_paid_at ON payment(status, paid_at);
CREATE INDEX idx_payment_plan_status ON payment_plan(status);
