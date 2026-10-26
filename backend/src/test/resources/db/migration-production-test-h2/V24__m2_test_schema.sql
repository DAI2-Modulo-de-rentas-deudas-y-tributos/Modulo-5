-- H2-only production schema fixture. V18 has already removed demo_user.
-- PostgreSQL production behavior is validated with the real V24 proposal.
ALTER TABLE ticket_case ADD COLUMN request_type VARCHAR(255);
ALTER TABLE ticket_case ADD COLUMN subcategory VARCHAR(255);
