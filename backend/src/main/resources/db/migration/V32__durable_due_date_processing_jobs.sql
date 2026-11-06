ALTER TABLE due_date_processing
    ALTER COLUMN processed_at DROP NOT NULL;

ALTER TABLE due_date_processing ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE due_date_processing ADD COLUMN requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE due_date_processing ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE due_date_processing ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE due_date_processing ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE due_date_processing ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE due_date_processing ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE due_date_processing ADD COLUMN last_error_code VARCHAR(80);
ALTER TABLE due_date_processing ADD COLUMN processing_stage VARCHAR(20) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE due_date_processing ADD COLUMN last_processed_id BIGINT NOT NULL DEFAULT 0;

ALTER TABLE due_date_processing
    ADD CONSTRAINT ck_due_date_processing_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'));
ALTER TABLE due_date_processing
    ADD CONSTRAINT ck_due_date_processing_stage
        CHECK (processing_stage IN ('DEBTS', 'INSTALLMENTS', 'PLANS', 'COMPLETED'));

CREATE INDEX idx_due_date_processing_queue
    ON due_date_processing(status, lease_expires_at, id);
