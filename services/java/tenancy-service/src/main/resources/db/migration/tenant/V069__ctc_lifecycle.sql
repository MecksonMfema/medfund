-- =====================================================================
-- V069: CTC (Claims-to-Contributions) full lifecycle
--
-- The V016 header comment misdescribes CTC as "cash-to-cardholder" — an
-- unrelated legacy MASCA construct. That comment is locked (Flyway verifies
-- the V016 checksum on boot); this migration's header is where the
-- terminology correction lives.
--
-- CTC = the fund offsets a member's own contribution debt with an approved
-- claim payout that would otherwise be paid to the member (payee_type=MEMBER
-- claims per V066). This migration lands three things:
--
--   1. member_payables (+ applications bridge) — source-of-truth ledger of
--      what the fund owes members whose claims routed to MEMBER.
--   2. CTC lifecycle columns on ctc_payments — status machine, reversal
--      link, member_payable_id, committed_at/by. Existing 'committed'
--      boolean is kept in place for one release; status='committed' is the
--      new source of truth.
--   3. transaction_types seed for CTC_OFFSET (sign '-') and
--      CTC_OFFSET_REVERSAL (sign '+') — consumed by contributions-service.
--   4. role_permissions seed for finance:reverse_ctc_payment and
--      finance:view_member_payables against tenant_admin. (There is no
--      permissions_catalogue table in the tenant schema — permissions are
--      code-defined in Java's PermissionCatalogue; the sibling shared/
--      permissions.yaml + Permissions.java update lands in the same PR.)
--
-- Backfill of historical committed=true CTC rows: they get
-- type='CTC', status='committed' but do NOT retroactively post a
-- CTC_OFFSET transaction — their member_payable_id remains NULL because
-- no payable ledger existed at the time. They stay as an audit trail of
-- the pre-lifecycle era.
-- =====================================================================

-- ---------- 1. member_payables ---------------------------------------

CREATE TABLE IF NOT EXISTS member_payables (
    id              uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id       uuid           NOT NULL,
    claim_id        uuid           NOT NULL,
    claim_number    text,
    amount          numeric(19, 4) NOT NULL CHECK (amount > 0),
    currency_code   varchar(3)     NOT NULL,
    status          text           NOT NULL DEFAULT 'open',
    -- 'open' | 'applied' | 'reversed'   (applied = fully consumed by CTC or
    -- future member payment runs; reversed = source claim was reversed)
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    recorded_by     uuid,
    CONSTRAINT member_payables_status_check
        CHECK (status IN ('open', 'applied', 'reversed')),
    CONSTRAINT member_payables_claim_unique UNIQUE (claim_id)
);

CREATE INDEX IF NOT EXISTS idx_member_payables_member
    ON member_payables(member_id);
CREATE INDEX IF NOT EXISTS idx_member_payables_status
    ON member_payables(status);
CREATE INDEX IF NOT EXISTS idx_member_payables_currency
    ON member_payables(currency_code);

-- Bridging: a member-payable is consumed a bit at a time. Today only CTC
-- writes here; future member-payment-run features add rows with
-- source_type='PAYMENT'. The source_id + source_type pair replaces a
-- per-source FK column — keeps this table stable when the second consumer
-- lands.
CREATE TABLE IF NOT EXISTS member_payable_applications (
    id                   uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    member_payable_id    uuid           NOT NULL REFERENCES member_payables(id),
    source_type          text           NOT NULL,        -- 'CTC' (this plan); future: 'PAYMENT'
    source_id            uuid           NOT NULL,        -- ctc_payment.id
    amount_applied       numeric(19, 4) NOT NULL,        -- positive = consumed; negative = reversal
    currency_code        varchar(3)     NOT NULL,
    applied_at           timestamptz    NOT NULL DEFAULT now(),
    applied_by           uuid,
    CONSTRAINT mpa_amount_nonzero CHECK (amount_applied <> 0),
    CONSTRAINT mpa_source_type_check CHECK (source_type IN ('CTC'))
);

CREATE INDEX IF NOT EXISTS idx_mpa_payable
    ON member_payable_applications(member_payable_id);
CREATE INDEX IF NOT EXISTS idx_mpa_source
    ON member_payable_applications(source_type, source_id);

-- ---------- 2. ctc_payments lifecycle columns ------------------------

ALTER TABLE ctc_payments
    ADD COLUMN IF NOT EXISTS type              text,
    ADD COLUMN IF NOT EXISTS status            text,
    ADD COLUMN IF NOT EXISTS member_payable_id uuid REFERENCES member_payables(id),
    ADD COLUMN IF NOT EXISTS reverses_ctc_id   uuid,
    ADD COLUMN IF NOT EXISTS committed_at      timestamptz,
    ADD COLUMN IF NOT EXISTS committed_by      uuid;

-- Backfill existing rows: everything created before this migration is a
-- pre-lifecycle CTC. If committed=true, mark status='committed'; else
-- status='draft'. Type='CTC' consistently — no historical REVERSAL rows.
UPDATE ctc_payments
   SET type   = 'CTC',
       status = CASE WHEN committed = TRUE THEN 'committed' ELSE 'draft' END
 WHERE type IS NULL;

ALTER TABLE ctc_payments
    ALTER COLUMN type   SET NOT NULL,
    ALTER COLUMN type   SET DEFAULT 'CTC',
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'draft';

ALTER TABLE ctc_payments
    DROP CONSTRAINT IF EXISTS ctc_payments_type_check;
ALTER TABLE ctc_payments
    ADD CONSTRAINT ctc_payments_type_check
        CHECK (type IN ('CTC', 'REVERSAL'));

ALTER TABLE ctc_payments
    DROP CONSTRAINT IF EXISTS ctc_payments_status_check;
ALTER TABLE ctc_payments
    ADD CONSTRAINT ctc_payments_status_check
        CHECK (status IN ('draft', 'committed', 'reversed'));

ALTER TABLE ctc_payments
    DROP CONSTRAINT IF EXISTS ctc_payments_reversal_link_check;
ALTER TABLE ctc_payments
    ADD CONSTRAINT ctc_payments_reversal_link_check
        CHECK ((type = 'REVERSAL') = (reverses_ctc_id IS NOT NULL));

ALTER TABLE ctc_payments
    DROP CONSTRAINT IF EXISTS ctc_payments_reverses_fk;
ALTER TABLE ctc_payments
    ADD CONSTRAINT ctc_payments_reverses_fk
        FOREIGN KEY (reverses_ctc_id) REFERENCES ctc_payments(id);

CREATE INDEX IF NOT EXISTS idx_ctc_status
    ON ctc_payments(status);
CREATE INDEX IF NOT EXISTS idx_ctc_member_payable
    ON ctc_payments(member_payable_id)
    WHERE member_payable_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ctc_reverses
    ON ctc_payments(reverses_ctc_id)
    WHERE reverses_ctc_id IS NOT NULL;

-- ---------- 3. transaction_types seed --------------------------------
-- Consumed by contributions-service TransactionService.recordFromCtcOffset
-- to move the member's contribution ledger when a CTC commits/reverses.
-- Sign drives balance movement (TransactionService.applyBalanceUpdate reads
-- transaction_types.sign).

INSERT INTO transaction_types (code, label, description, sign, requires_approval) VALUES
    ('CTC_OFFSET',          'CTC offset',           'Claims-to-Contributions offset — an approved claim payout applied to the member''s own contribution ledger.', '-', FALSE),
    ('CTC_OFFSET_REVERSAL', 'CTC offset reversal',  'Reversal of a Claims-to-Contributions offset.', '+', FALSE)
ON CONFLICT (code) DO NOTHING;

-- ---------- 4. role_permissions seed --------------------------------
-- finance:reverse_ctc_payment — gates the compensating-row endpoint.
--   Separated from finance:manage_ctc_payments so tenants can grant create
--   + commit (finance clerks) without granting reverse (finance HoD only).
-- finance:view_member_payables — read the outstanding "member is owed"
--   balances. Read-only. Needed by the CTC form + admin surfaces.
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), r.id, p.permission, 'full'
  FROM roles r
 CROSS JOIN (VALUES
    ('finance:reverse_ctc_payment'),
    ('finance:view_member_payables')
 ) AS p(permission)
 WHERE r.name = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;
