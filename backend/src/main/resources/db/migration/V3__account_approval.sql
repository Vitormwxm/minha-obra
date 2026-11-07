ALTER TABLE app_account
    DROP CONSTRAINT app_account_status_check,
    ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE app_account
    ADD CONSTRAINT app_account_status_check CHECK (status IN ('PENDING_APPROVAL','ACTIVE','BLOCKED')),
    ADD CONSTRAINT app_account_pending_user_check CHECK (
        status <> 'PENDING_APPROVAL' OR (role = 'USER' AND NOT must_change_password)
    );

CREATE UNIQUE INDEX account_registration_request_key
    ON account_idempotency(request_key) WHERE operation = 'register';
