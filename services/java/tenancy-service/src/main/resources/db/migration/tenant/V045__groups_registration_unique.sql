-- Auto-generated group registration numbers must be unique per tenant
-- schema. Enforce it at the DB level so a concurrent create can't beat
-- GroupNumberService's cross-check and land two groups on the same
-- number. Postgres UNIQUE indexes treat NULL as distinct so
-- pre-existing legacy rows with a NULL registration_number don't
-- collide; new rows always have a value (GroupService populates it
-- server-side).

-- Deduplicate any pre-existing duplicate registration numbers before
-- imposing the UNIQUE index. Retention rule: keep the oldest row
-- (smallest created_at); null out the duplicates so the operator can
-- rename them by hand later. Safer than dropping data, and NULL is
-- allowed under the new index anyway.
WITH dupes AS (
    SELECT id
      FROM (
          SELECT id,
                 ROW_NUMBER() OVER (
                     PARTITION BY registration_number
                     ORDER BY created_at ASC, id ASC
                 ) AS rn
            FROM groups
           WHERE registration_number IS NOT NULL
      ) t
     WHERE rn > 1
)
UPDATE groups
   SET registration_number = NULL
 WHERE id IN (SELECT id FROM dupes);

CREATE UNIQUE INDEX IF NOT EXISTS groups_registration_number_unique
    ON groups (registration_number)
 WHERE registration_number IS NOT NULL;
