-- ── Phase 12 §A: IFRS 17 cohort dimension ─────────────────────────────
-- Layered on top of ifrs17_portfolio (V107); every policy now has both a
-- portfolio_id and a cohort_id. Each tenant is seeded with a MISC-YYYY
-- default cohort attached to the MISC portfolio (grill note 11), and every
-- legacy row is backfilled onto it. Phase 15 will land the aggregation
-- logic that turns portfolio+cohort into IFRS 17 groups of contracts.

CREATE TABLE IF NOT EXISTS ifrs17_cohort (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id  UUID         NOT NULL REFERENCES ifrs17_portfolio(id) ON DELETE RESTRICT,
    cohort_year   INT          NOT NULL,
    cohort_type   VARCHAR(20)  NOT NULL CHECK (cohort_type IN ('ONEROUS', 'NON_ONEROUS', 'UNCERTAIN')),
    name          VARCHAR(200) NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id      UUID,
    actor_email   VARCHAR(255),
    CONSTRAINT uq_ifrs17_cohort UNIQUE (portfolio_id, cohort_year, cohort_type)
);

-- Seed the tenant's default MISC cohort for the current year (grill note 11).
INSERT INTO ifrs17_cohort (portfolio_id, cohort_year, cohort_type, name)
SELECT id, EXTRACT(YEAR FROM NOW())::INT, 'NON_ONEROUS',
       'MISC-' || EXTRACT(YEAR FROM NOW())::INT || '-DEFAULT'
FROM ifrs17_portfolio WHERE name = 'MISC'
ON CONFLICT (portfolio_id, cohort_year, cohort_type) DO NOTHING;

-- ── FK adds — idempotent via pg_constraint guard. ─────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_life_cohort') THEN
        ALTER TABLE life_policies       ADD CONSTRAINT fk_life_cohort       FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_funeral_cohort') THEN
        ALTER TABLE funeral_policies    ADD CONSTRAINT fk_funeral_cohort    FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_disability_cohort') THEN
        ALTER TABLE disability_policies ADD CONSTRAINT fk_disability_cohort FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_travel_cohort') THEN
        ALTER TABLE travel_policies     ADD CONSTRAINT fk_travel_cohort     FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_vehicle_cohort') THEN
        ALTER TABLE vehicles            ADD CONSTRAINT fk_vehicle_cohort    FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_property_cohort') THEN
        ALTER TABLE properties          ADD CONSTRAINT fk_property_cohort   FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_contribution_cohort') THEN
        ALTER TABLE contributions       ADD CONSTRAINT fk_contribution_cohort FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
    END IF;
END$$;

-- Backfill: point every legacy policy at MISC-YYYY-DEFAULT.
UPDATE life_policies       SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE funeral_policies    SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE disability_policies SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE travel_policies     SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE vehicles            SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE properties          SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE contributions       SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
