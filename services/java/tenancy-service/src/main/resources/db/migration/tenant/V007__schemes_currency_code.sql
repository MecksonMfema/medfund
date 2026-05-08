-- Tie each scheme to a single currency. Benefits, age groups, contributions,
-- and insurance quotes on a scheme must use the same currency_code. The
-- match invariant is enforced in SchemeService at the application layer for
-- friendlier error messages; we still index the column for fast lookups.

ALTER TABLE schemes
    ADD COLUMN IF NOT EXISTS currency_code CHAR(3) NOT NULL DEFAULT 'USD';

CREATE INDEX IF NOT EXISTS idx_schemes_currency ON schemes(currency_code);
