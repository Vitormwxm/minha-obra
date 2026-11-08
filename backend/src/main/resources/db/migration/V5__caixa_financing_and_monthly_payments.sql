CREATE TABLE financing (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL UNIQUE REFERENCES app_account(id) ON DELETE CASCADE,
    version BIGINT NOT NULL CHECK (version >= 0),
    financed_cents BIGINT NOT NULL CHECK (financed_cents BETWEEN 1 AND 10000000000),
    released_cents BIGINT NOT NULL CHECK (released_cents BETWEEN 0 AND financed_cents),
    annual_nominal_rate NUMERIC(9,6) NOT NULL CHECK (annual_nominal_rate BETWEEN 0 AND 100),
    expected_delivery_date DATE NOT NULL CHECK (expected_delivery_date <= DATE '2100-12-31'),
    tracking_start_month DATE NOT NULL CHECK (tracking_start_month >= DATE '1900-01-01'
        AND EXTRACT(DAY FROM tracking_start_month) = 1),
    subsidy_cents BIGINT CHECK (subsidy_cents BETWEEN 0 AND 10000000000),
    CHECK (expected_delivery_date >= tracking_start_month)
);

-- A deleted month may be recreated, but an old version must never match it.
CREATE SEQUENCE finance_month_revision;

CREATE TABLE finance_month (
    financing_id UUID NOT NULL REFERENCES financing(id) ON DELETE CASCADE,
    competence DATE NOT NULL CHECK (competence BETWEEN DATE '1900-01-01' AND DATE '2100-12-01'
        AND EXTRACT(DAY FROM competence) = 1),
    version BIGINT NOT NULL DEFAULT nextval('finance_month_revision') CHECK (version > 0),
    caixa_due_date DATE CHECK (date_trunc('month', caixa_due_date::timestamp)::date = competence),
    builder_due_date DATE CHECK (date_trunc('month', builder_due_date::timestamp)::date = competence),
    caixa_status VARCHAR(10) NOT NULL CHECK (caixa_status IN ('OPEN', 'PAID', 'NO_PAYMENT')),
    builder_status VARCHAR(10) NOT NULL CHECK (builder_status IN ('OPEN', 'PAID', 'NO_PAYMENT')),
    released_cents BIGINT CHECK (released_cents BETWEEN 0 AND 10000000000),
    insurance_cents BIGINT CHECK (insurance_cents BETWEEN 0 AND 10000000000),
    fees_cents BIGINT CHECK (fees_cents BETWEEN 0 AND 10000000000),
    PRIMARY KEY (financing_id, competence)
);

CREATE TABLE finance_payment (
    financing_id UUID NOT NULL,
    competence DATE NOT NULL,
    position SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 99),
    kind VARCHAR(11) NOT NULL CHECK (kind IN ('CAIXA', 'CONSTRUTORA')),
    paid_on DATE NOT NULL CHECK (paid_on BETWEEN DATE '1900-01-01' AND DATE '2100-12-31'),
    paid_cents BIGINT NOT NULL CHECK (paid_cents BETWEEN 1 AND 10000000000),
    late_charge_cents BIGINT CHECK (late_charge_cents BETWEEN 0 AND paid_cents),
    PRIMARY KEY (financing_id, competence, position),
    FOREIGN KEY (financing_id, competence) REFERENCES finance_month(financing_id, competence) ON DELETE CASCADE
);
