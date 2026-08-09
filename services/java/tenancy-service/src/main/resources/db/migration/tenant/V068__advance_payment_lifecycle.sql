-- Advance payment lifecycle — status column, append-only reversal link,
-- approval trail. Compensating-entry pattern lifted from Adjustment
-- (see AdjustmentController "post a reversing adjustment instead"). Originals
-- never mutate on reversal; a REVERSAL row references its origin via
-- reverses_advance_id and negates the outstanding-balance query.

ALTER TABLE advance_payments
    ADD COLUMN IF NOT EXISTS type              text        NOT NULL DEFAULT 'ADVANCE',
    ADD COLUMN IF NOT EXISTS status            text        NOT NULL DEFAULT 'approved',
    ADD COLUMN IF NOT EXISTS approved_by       uuid,
    ADD COLUMN IF NOT EXISTS approved_at       timestamptz,
    ADD COLUMN IF NOT EXISTS reverses_advance_id uuid;

ALTER TABLE advance_payments
    DROP CONSTRAINT IF EXISTS advance_payments_type_check;
ALTER TABLE advance_payments
    ADD CONSTRAINT advance_payments_type_check
        CHECK (type IN ('ADVANCE', 'REVERSAL'));

ALTER TABLE advance_payments
    DROP CONSTRAINT IF EXISTS advance_payments_status_check;
ALTER TABLE advance_payments
    ADD CONSTRAINT advance_payments_status_check
        CHECK (status IN ('pending', 'approved', 'applied', 'reversed'));

ALTER TABLE advance_payments
    DROP CONSTRAINT IF EXISTS advance_payments_reversal_link_check;
ALTER TABLE advance_payments
    ADD CONSTRAINT advance_payments_reversal_link_check
        CHECK ((type = 'REVERSAL') = (reverses_advance_id IS NOT NULL));

ALTER TABLE advance_payments
    DROP CONSTRAINT IF EXISTS advance_payments_reverses_fk;
ALTER TABLE advance_payments
    ADD CONSTRAINT advance_payments_reverses_fk
        FOREIGN KEY (reverses_advance_id) REFERENCES advance_payments(id);

CREATE INDEX IF NOT EXISTS idx_advance_payments_status
    ON advance_payments(status);
CREATE INDEX IF NOT EXISTS idx_advance_payments_reverses
    ON advance_payments(reverses_advance_id)
    WHERE reverses_advance_id IS NOT NULL;

-- Existing rows: default status='approved' is correct for the tiny set of
-- API-created advances that exist today (no threshold was ever enforced).
-- Backfill approved_at from recorded_at so the audit trail has a coherent
-- timestamp.
UPDATE advance_payments
   SET approved_at = recorded_at,
       approved_by = recorded_by
 WHERE approved_at IS NULL;

-- Bridging: an advance can apply partially to multiple payments across
-- multiple runs. Recorded by PaymentRunService.execute() when the rules
-- engine consumes advance balance against a run item.
CREATE TABLE IF NOT EXISTS advance_payment_applications (
    id                   uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    advance_payment_id   uuid           NOT NULL REFERENCES advance_payments(id),
    payment_id           uuid,
    payment_run_id       uuid           REFERENCES payment_runs(id),
    payment_run_item_id  uuid           REFERENCES payment_run_items(id),
    amount_applied       numeric(19, 4) NOT NULL CHECK (amount_applied > 0),
    currency_code        varchar(3)     NOT NULL,
    applied_at           timestamptz    NOT NULL DEFAULT now(),
    applied_by           uuid
);

CREATE INDEX IF NOT EXISTS idx_apa_advance
    ON advance_payment_applications(advance_payment_id);
CREATE INDEX IF NOT EXISTS idx_apa_payment
    ON advance_payment_applications(payment_id);
CREATE INDEX IF NOT EXISTS idx_apa_run
    ON advance_payment_applications(payment_run_id);

-- ── Permissions seed ─────────────────────────────────────────────────────
-- Two new finance permissions. approve_advance_payment is separated from
-- manage_advance_payments so tenants can grant "record, don't approve"
-- (finance clerks) vs "approve" (finance HoD).
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), r.id, p.permission, 'full'
  FROM roles r
 CROSS JOIN (VALUES
    ('finance:approve_advance_payment'),
    ('finance:reverse_advance_payment')
 ) AS p(permission)
 WHERE r.name = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;
