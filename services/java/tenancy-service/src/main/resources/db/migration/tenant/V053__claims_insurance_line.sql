-- =====================================================================
-- V053: claims.insurance_line + optional claims.batch_number
-- =====================================================================
-- Downstream consumers (finance ledger writers, notification routers)
-- have to know a claim's insurance line without joining schemes twice
-- — denormalise it onto the claim at submission time. Backfill from
-- schemes.insurance_line (added in V021) so historical rows are not
-- left NULL. Matches the 32-char size on schemes.insurance_line for
-- consistency.
--
-- batch_number: optional per-claim tag for capture flows that group
-- related claims. Always nullable — a single ad-hoc claim never gets
-- forced into a batch. Never auto-generated; the operator either
-- types one at capture time or leaves the field blank.
-- =====================================================================

ALTER TABLE claims
    ADD COLUMN IF NOT EXISTS insurance_line VARCHAR(32),
    ADD COLUMN IF NOT EXISTS batch_number   VARCHAR(16);

UPDATE claims c
   SET insurance_line = COALESCE(s.insurance_line, 'HEALTH')
  FROM schemes s
 WHERE s.id = c.scheme_id
   AND c.insurance_line IS NULL;

-- Any claim whose scheme is gone (data cleanup) defaults to HEALTH so
-- the NOT NULL below can't fail; safer than dropping rows.
UPDATE claims
   SET insurance_line = 'HEALTH'
 WHERE insurance_line IS NULL;

ALTER TABLE claims
    ALTER COLUMN insurance_line SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_claims_insurance_line ON claims(insurance_line);
