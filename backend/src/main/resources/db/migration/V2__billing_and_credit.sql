ALTER TABLE payment ADD COLUMN bill_id BIGINT;

CREATE TABLE bill (
 id BIGSERIAL PRIMARY KEY, number VARCHAR(255) NOT NULL UNIQUE, taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id),
 total_amount NUMERIC(19,2) NOT NULL, issue_date DATE NOT NULL, due_date DATE NOT NULL, status VARCHAR(30) NOT NULL,
 created_by VARCHAR(255) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT ck_bill_amount CHECK(total_amount>0)
);
ALTER TABLE payment ADD CONSTRAINT fk_payment_bill FOREIGN KEY (bill_id) REFERENCES bill(id);
CREATE TABLE bill_debt (
 id BIGSERIAL PRIMARY KEY,bill_id BIGINT NOT NULL REFERENCES bill(id),debt_id BIGINT NOT NULL REFERENCES debt(id),
 amount_at_issue NUMERIC(19,2) NOT NULL,CONSTRAINT uk_bill_debt UNIQUE(bill_id,debt_id)
);
CREATE TABLE electronic_payment_attempt (
 id BIGSERIAL PRIMARY KEY,payment_id BIGINT REFERENCES payment(id),taxpayer_id BIGINT NOT NULL REFERENCES taxpayer_reference(id),
 debt_id BIGINT NOT NULL REFERENCES debt(id),amount NUMERIC(19,2) NOT NULL,status VARCHAR(30) NOT NULL,
 gateway_reference VARCHAR(255) NOT NULL UNIQUE,created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE credit_balance_application (
 id BIGSERIAL PRIMARY KEY,credit_balance_id BIGINT NOT NULL REFERENCES credit_balance(id),debt_id BIGINT NOT NULL REFERENCES debt(id),
 amount NUMERIC(19,2) NOT NULL,status VARCHAR(30) NOT NULL,applied_by VARCHAR(255) NOT NULL,
 applied_at TIMESTAMP WITH TIME ZONE NOT NULL,reversed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_bill_taxpayer ON bill(taxpayer_id);
CREATE INDEX idx_bill_debt_bill ON bill_debt(bill_id);
CREATE INDEX idx_credit_application_credit ON credit_balance_application(credit_balance_id);
