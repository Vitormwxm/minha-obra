CREATE TABLE notification_device (
    installation_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES app_account(id) ON DELETE CASCADE,
    auth_version BIGINT NOT NULL CHECK (auth_version >= 0),
    token VARCHAR(4096) NOT NULL,
    token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    zone_id VARCHAR(64) NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX notification_device_account_refresh_idx
    ON notification_device (account_id, refreshed_at DESC);
