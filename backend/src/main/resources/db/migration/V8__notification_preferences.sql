CREATE TABLE notification_preference (
    account_id UUID PRIMARY KEY REFERENCES app_account(id) ON DELETE CASCADE,
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 9007199254740991),
    enabled BOOLEAN NOT NULL,
    offsets SMALLINT[] NOT NULL CHECK (
        cardinality(offsets) <= 8 AND array_position(offsets, NULL) IS NULL
        AND 0 <= ALL(offsets) AND 30 >= ALL(offsets)
    ),
    reminder_time TIME NOT NULL CHECK (EXTRACT(SECOND FROM reminder_time) = 0 AND reminder_time < TIME '24:00'),
    after_due BOOLEAN NOT NULL,
    weekly BOOLEAN NOT NULL,
    show_amounts BOOLEAN NOT NULL,
    caixa_day SMALLINT CHECK (caixa_day BETWEEN 1 AND 31),
    builder_day SMALLINT CHECK (builder_day BETWEEN 1 AND 31)
);
