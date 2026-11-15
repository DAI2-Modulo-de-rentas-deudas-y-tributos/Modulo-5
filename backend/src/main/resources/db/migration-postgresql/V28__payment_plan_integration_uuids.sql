ALTER TABLE payment_plan_request ADD COLUMN integration_id UUID DEFAULT gen_random_uuid();
ALTER TABLE payment_plan ADD COLUMN integration_id UUID DEFAULT gen_random_uuid();

ALTER TABLE payment_plan_request ALTER COLUMN integration_id SET NOT NULL;
ALTER TABLE payment_plan ALTER COLUMN integration_id SET NOT NULL;

ALTER TABLE payment_plan_request ADD CONSTRAINT uk_payment_plan_request_integration_id UNIQUE (integration_id);
ALTER TABLE payment_plan ADD CONSTRAINT uk_payment_plan_integration_id UNIQUE (integration_id);
