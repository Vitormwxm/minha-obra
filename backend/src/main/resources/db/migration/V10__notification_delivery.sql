ALTER TABLE notification_preference
    ADD COLUMN enabled_since TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE notification_delivery (
    id UUID PRIMARY KEY,
    installation_id UUID NOT NULL REFERENCES notification_device(installation_id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES app_account(id) ON DELETE CASCADE,
    auth_version BIGINT NOT NULL,
    notice_key VARCHAR(150) NOT NULL,
    registration_id UUID REFERENCES app_account(id) ON DELETE CASCADE,
    financing_id UUID REFERENCES financing(id) ON DELETE CASCADE,
    payment_kind VARCHAR(11) CHECK (payment_kind IN ('CAIXA','CONSTRUTORA')),
    due_on DATE,
    notice_on DATE,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL CHECK (expires_at > scheduled_at),
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    leased_until TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    state VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SENT','CANCELED','FAILED')),
    UNIQUE (installation_id, recipient_id, notice_key),
    CHECK ((registration_id IS NOT NULL AND financing_id IS NULL AND payment_kind IS NULL AND due_on IS NULL AND notice_on IS NULL)
        OR (registration_id IS NULL AND financing_id IS NOT NULL AND payment_kind IS NOT NULL AND due_on IS NOT NULL AND notice_on IS NOT NULL))
);
CREATE INDEX notification_delivery_available
    ON notification_delivery(available_at,id) WHERE state='PENDING';
CREATE INDEX notification_delivery_expiration ON notification_delivery(expires_at);
