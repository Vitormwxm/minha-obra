ALTER TABLE app_account
    ADD COLUMN last_access_at TIMESTAMPTZ,
    DROP CONSTRAINT app_account_status_check,
    DROP CONSTRAINT app_account_pending_user_check;

ALTER TABLE app_account
    ADD CONSTRAINT app_account_status_check
        CHECK (status IN ('PENDING_APPROVAL', 'REJECTED', 'ACTIVE', 'BLOCKED')),
    ADD CONSTRAINT app_account_review_user_check
        CHECK (status NOT IN ('PENDING_APPROVAL', 'REJECTED') OR (role = 'USER' AND NOT must_change_password));
