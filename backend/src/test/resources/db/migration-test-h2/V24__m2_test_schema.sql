-- H2-only schema fixture. PostgreSQL integration tests execute the real V24 proposal.
-- This file is available only to tests; it is not included in application migrations.
ALTER TABLE demo_user ADD COLUMN integration_actor_id UUID DEFAULT RANDOM_UUID() NOT NULL;
ALTER TABLE demo_user ADD CONSTRAINT uq_demo_user_integration_actor_id UNIQUE (integration_actor_id);

ALTER TABLE ticket_case ADD COLUMN request_type VARCHAR(255);
ALTER TABLE ticket_case ADD COLUMN subcategory VARCHAR(255);
