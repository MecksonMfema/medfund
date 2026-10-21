-- ── Phase 12 §A: IFRS 17 portfolio dimension ─────────────────────────
-- Speculatively lands the IFRS 17 portfolio table now so Phase 15 doesn't
-- need to reshape populated tenants later. Each tenant is seeded with a
-- MISC catchall so every legacy policy has a portfolio_id (grill note 11);
-- Phase 15 will layer the aggregation-into-groups-of-contracts logic on
-- top. FK adds use pg_constraint guards per the V113 idempotent-FK pattern
-- — Postgres does not support `ADD CONSTRAINT IF NOT EXISTS`.

CREATE TABLE IF NOT EXISTS ifrs17_portfolio (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(200) NOT NULL,
    description    TEXT,
    insurance_line VARCHAR(20),                          -- NULL for MISC catchall
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id       UUID,
    actor_email    VARCHAR(255),
    CONSTRAINT uq_ifrs17_portfolio_name UNIQUE (name)
);

-- Seed the tenant's default MISC portfolio (grill note 11).
INSERT INTO ifrs17_portfolio (name, description, insurance_line, is_active)
VALUES ('MISC', 'Default catchall portfolio for un-configured policies', NULL, TRUE)
ON CONFLICT (name) DO NOTHING;

-- ── FK adds — idempotent via pg_constraint guard (Postgres does not
--    support `ADD CONSTRAINT IF NOT EXISTS`). See V113 for the pattern. ──
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_life_portfolio') THEN
        ALTER TABLE life_policies       ADD CONSTRAINT fk_life_portfolio       FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_funeral_portfolio') THEN
        ALTER TABLE funeral_policies    ADD CONSTRAINT fk_funeral_portfolio    FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_disability_portfolio') THEN
        ALTER TABLE disability_policies ADD CONSTRAINT fk_disability_portfolio FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_travel_portfolio') THEN
        ALTER TABLE travel_policies     ADD CONSTRAINT fk_travel_portfolio     FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_vehicle_portfolio') THEN
        ALTER TABLE vehicles            ADD CONSTRAINT fk_vehicle_portfolio    FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_property_portfolio') THEN
        ALTER TABLE properties          ADD CONSTRAINT fk_property_portfolio   FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_contribution_portfolio') THEN
        ALTER TABLE contributions       ADD CONSTRAINT fk_contribution_portfolio FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_scheme_default_portfolio') THEN
        ALTER TABLE schemes             ADD CONSTRAINT fk_scheme_default_portfolio FOREIGN KEY (default_portfolio_id) REFERENCES ifrs17_portfolio(id);
    END IF;
END$$;

-- Backfill: point every legacy policy at MISC.
UPDATE life_policies       SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE funeral_policies    SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE disability_policies SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE travel_policies     SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE vehicles            SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE properties          SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE contributions       SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
