CREATE TABLE official_tr_rate (
    period_start DATE NOT NULL CHECK (period_start >= DATE '1991-02-01'),
    period_end DATE NOT NULL CHECK (period_end - period_start BETWEEN 1 AND 32),
    rate_percent NUMERIC(7,4) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    PRIMARY KEY (period_start, period_end)
);

ALTER TABLE finance_month
    ADD COLUMN tr_period_start DATE,
    ADD COLUMN tr_period_end DATE,
    ADD COLUMN manual_tr_percent NUMERIC(9,6) CHECK (manual_tr_percent BETWEEN 0 AND 100),
    ADD CONSTRAINT finance_month_tr_period CHECK (
        (tr_period_start IS NULL AND tr_period_end IS NULL)
        OR (tr_period_start IS NOT NULL AND tr_period_end IS NOT NULL
            AND tr_period_start >= DATE '1991-02-01'
            AND tr_period_end - tr_period_start BETWEEN 1 AND 32)
    );
