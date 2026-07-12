-- =====================================================================
-- V063: tariff_categories catalogue + benefit_tariff_categories join
-- =====================================================================
-- Promotes tariff_codes.category from a nullable free-text string to a
-- first-class tenant catalogue. Two motivations:
--
-- 1. Every tariff must belong to a category (FK, not free text) so two
--    admins can't spell "Consultation" and "consultation" and end up
--    with two logically-identical rows that don't share a mapping.
-- 2. Every scheme_benefit must declare which tariff categories it
--    covers (many-to-many). The V062 approach routed via benefit_type
--    as an intermediary, which is a coarse label rather than the
--    actual coverage set. This makes coverage explicit at the
--    scheme_benefit level.
--
-- Cap-only tariffs are tagged on the category itself via is_cap_only
-- rather than on the mapping row — a whole category of tariffs
-- either does or doesn't belong to a per-benefit balance.
-- =====================================================================

-- 1) tariff_categories catalogue --------------------------------------
CREATE TABLE IF NOT EXISTS tariff_categories (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(64)  NOT NULL UNIQUE,
    label        VARCHAR(200) NOT NULL,
    description  TEXT,
    is_cap_only  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tariff_categories_active
    ON tariff_categories(is_active) WHERE is_active = TRUE;

-- Sensible defaults so a fresh tenant is usable day-one. GENERAL is
-- seeded is_cap_only=TRUE — tariffs in it deduct from the scheme's
-- annual cap without touching a per-benefit balance.
INSERT INTO tariff_categories (code, label, is_cap_only, sort_order) VALUES
    ('CONSULTATION',         'Consultation',          FALSE, 10),
    ('PATHOLOGY',            'Pathology',             FALSE, 20),
    ('RADIOLOGY',            'Radiology',             FALSE, 30),
    ('SURGERY',              'Surgery',               FALSE, 40),
    ('CHRONIC_MEDICATION',   'Chronic medication',    FALSE, 50),
    ('DENTAL',               'Dental',                FALSE, 60),
    ('OPTICAL',              'Optical',               FALSE, 70),
    ('MATERNITY',            'Maternity',             FALSE, 80),
    ('EMERGENCY_EVACUATION', 'Emergency evacuation',  FALSE, 90),
    ('GENERAL',              'General (annual cap)',  TRUE, 100)
ON CONFLICT (code) DO NOTHING;

-- 2) benefit_tariff_categories join -----------------------------------
-- Per-scheme_benefit list of categories the benefit is authoritative
-- for. Delete-on-cascade against scheme_benefits so removing a benefit
-- automatically clears its coverage rows.
CREATE TABLE IF NOT EXISTS benefit_tariff_categories (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_benefit_id   UUID NOT NULL REFERENCES scheme_benefits(id) ON DELETE CASCADE,
    tariff_category_id  UUID NOT NULL REFERENCES tariff_categories(id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_benefit_category UNIQUE (scheme_benefit_id, tariff_category_id)
);

CREATE INDEX IF NOT EXISTS idx_btc_category ON benefit_tariff_categories(tariff_category_id);
CREATE INDEX IF NOT EXISTS idx_btc_benefit  ON benefit_tariff_categories(scheme_benefit_id);

-- 3) Backfill tariff_categories from tariff_codes.category strings ---
-- Every distinct non-null category string becomes a row. Existing seed
-- rows above may match some — ON CONFLICT DO NOTHING keeps them.
INSERT INTO tariff_categories (code, label)
SELECT DISTINCT
       UPPER(REPLACE(TRIM(category), ' ', '_')) AS code,
       TRIM(category)                            AS label
  FROM tariff_codes
 WHERE category IS NOT NULL
   AND TRIM(category) <> ''
ON CONFLICT (code) DO NOTHING;

-- 4) Backfill benefit_tariff_categories from V062 mappings -----------
-- For every (tariff_category, benefit_type_id) row in V062 where a
-- benefit_type is set: link every scheme_benefit with that benefit_type
-- to the matching category. Cap-only mappings (benefit_type_id IS NULL)
-- are propagated to tariff_categories.is_cap_only in step 4b.
INSERT INTO benefit_tariff_categories (scheme_benefit_id, tariff_category_id)
SELECT sb.id, tc.id
  FROM tariff_benefit_mappings tbm
  JOIN tariff_categories tc
    ON tc.code = UPPER(REPLACE(TRIM(tbm.tariff_category), ' ', '_'))
  JOIN scheme_benefits sb
    ON sb.benefit_type_id = tbm.benefit_type_id
 WHERE tbm.benefit_type_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- 4b) Cap-only propagation: category rows referenced by a mapping
-- with NULL benefit_type_id are cap-only.
UPDATE tariff_categories
   SET is_cap_only = TRUE
 WHERE code IN (
    SELECT UPPER(REPLACE(TRIM(tbm.tariff_category), ' ', '_'))
      FROM tariff_benefit_mappings tbm
     WHERE tbm.benefit_type_id IS NULL
 );

-- 5) tariff_codes.category_id ----------------------------------------
-- Populate the new FK column from the legacy free-text column. Rows
-- with a null / blank category are linked to GENERAL (cap-only) so the
-- ledger continues to make progress — an operator can reclassify later.
ALTER TABLE tariff_codes ADD COLUMN IF NOT EXISTS category_id UUID
    REFERENCES tariff_categories(id) ON DELETE RESTRICT;

UPDATE tariff_codes t
   SET category_id = tc.id
  FROM tariff_categories tc
 WHERE tc.code = UPPER(REPLACE(TRIM(t.category), ' ', '_'))
   AND t.category IS NOT NULL
   AND TRIM(t.category) <> ''
   AND t.category_id IS NULL;

UPDATE tariff_codes
   SET category_id = (SELECT id FROM tariff_categories WHERE code = 'GENERAL')
 WHERE category_id IS NULL;

ALTER TABLE tariff_codes ALTER COLUMN category_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_tariff_codes_category
    ON tariff_codes(category_id);

-- 6) Drop V062 mapping -----------------------------------------------
-- Fully superseded by tariff_categories + benefit_tariff_categories.
-- Pre-prod — no data to preserve beyond the backfill above.
DROP TABLE IF EXISTS tariff_benefit_mappings;
