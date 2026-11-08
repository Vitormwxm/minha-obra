CREATE TABLE account_deletion_request (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL UNIQUE REFERENCES app_account(id) ON DELETE CASCADE,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX account_deletion_request_order ON account_deletion_request(requested_at, id);

-- Spring Session indexes authenticated sessions by SessionPrincipal.getName(), the account UUID as text.
-- The foreign key closes the race with a login persisting its session after account removal.
ALTER TABLE app_account
    ADD COLUMN session_principal VARCHAR(100) GENERATED ALWAYS AS (id::text) STORED UNIQUE;

-- Stale sessions from earlier versions can only refer to accounts that no longer exist.
DELETE FROM spring_session s
WHERE s.principal_name IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM app_account a WHERE a.session_principal = s.principal_name);

ALTER TABLE spring_session
    ADD CONSTRAINT spring_session_account_fk FOREIGN KEY (principal_name)
    REFERENCES app_account(session_principal) ON DELETE CASCADE;
