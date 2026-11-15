ALTER TABLE finance_payment ADD COLUMN builder_category VARCHAR(12);

ALTER TABLE finance_payment ADD CONSTRAINT finance_payment_builder_category_check
    CHECK (builder_category IS NULL OR (kind = 'CONSTRUTORA'
        AND builder_category IN ('MONTHLY', 'INTERCALARY', 'DOWN_PAYMENT', 'UNCLASSIFIED')));
