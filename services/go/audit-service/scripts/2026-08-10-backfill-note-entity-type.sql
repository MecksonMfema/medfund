-- =====================================================================
-- One-shot backfill: renumber historic audit_events rows from the
-- pre-V074 entity_type values ('Adjustment', 'DebitNote', 'CreditNote')
-- onto the unified 'Note' entity_type. Original value preserved in
-- original_entity_type so investigators can still tell which pre-rename
-- table an event came from.
--
-- Idempotent: safe to re-run. Only touches rows whose entity_type still
-- points at the pre-rename value AND whose original_entity_type is NULL.
--
-- Run once per tenant's audit database as part of the V074 deploy
-- runbook — this script is NOT tracked by Flyway (audit-service uses
-- its own idempotent schema init in internal/db/db.go), so it must be
-- invoked manually.
-- =====================================================================

BEGIN;

ALTER TABLE audit_events
    ADD COLUMN IF NOT EXISTS original_entity_type TEXT;

UPDATE audit_events
   SET original_entity_type = entity_type,
       entity_type          = 'Note'
 WHERE entity_type IN ('Adjustment', 'DebitNote', 'CreditNote')
   AND original_entity_type IS NULL;

COMMIT;
