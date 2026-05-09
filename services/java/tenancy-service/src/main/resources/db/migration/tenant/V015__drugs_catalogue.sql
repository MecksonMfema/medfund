-- Drug catalogue used by drug-claim submission and adjudication. Tenant-
-- scoped; each tenant maintains its own formulary. drug_type drives the
-- adjudication pipeline (chronic medications often need pre-auth and
-- different benefit treatment from acute scripts).
--
-- payment_percentage is the share the scheme covers. do_not_pay flags
-- drugs that are excluded entirely (the adjudication pipeline rejects
-- claims for these on sight).

CREATE TABLE IF NOT EXISTS drugs (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    drug_name           VARCHAR(200) NOT NULL,
    drug_type           VARCHAR(32)  NOT NULL DEFAULT 'ACUTE'
                          CHECK (drug_type IN ('ACUTE', 'CHRONIC', 'OFF_LIMIT')),
    unit_of_measurement VARCHAR(16)  NOT NULL DEFAULT 'unit'
                          CHECK (unit_of_measurement IN ('unit', 'ml', 'g', 'mg', 'tablet', 'capsule')),
    tariff_code         VARCHAR(50),
    wholesale_cost_zwl  NUMERIC(19,4),
    wholesale_cost_usd  NUMERIC(19,4),
    payment_percentage  NUMERIC(5,2) NOT NULL DEFAULT 100.00
                          CHECK (payment_percentage BETWEEN 0 AND 100),
    do_not_pay          BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    CONSTRAINT uq_drugs_name UNIQUE (drug_name)
);

CREATE INDEX IF NOT EXISTS idx_drugs_type    ON drugs(drug_type);
CREATE INDEX IF NOT EXISTS idx_drugs_active  ON drugs(is_active);
CREATE INDEX IF NOT EXISTS idx_drugs_tariff  ON drugs(tariff_code);
