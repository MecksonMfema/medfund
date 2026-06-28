-- =====================================================================
-- V035: Invoice balance snapshots + PDF pointer table
-- =====================================================================
-- Two changes that together give the per-invoice statement page financial
-- integrity and a PDF download:
--
-- 1. Snapshot columns on `invoices`. Captured at commit time in
--    BillingService.persistInvoiceFor → InvoiceSnapshotService. Once
--    written they are NEVER recomputed — historic statements stay
--    accurate even if a back-dated transaction lands later.
--
--      opening_balance         the prior invoice's closing for this
--                              (holder, currency), or 0 on first ever
--      payments_in_window      sum of payments in
--      adjustments_in_window   [prior.committed_at, this.committed_at)
--      closing_balance         opening + total_amount - payments + adjustments
--      committed_at            the commit instant — every invoice from
--                              one commit shares the same value
--      prior_invoice_id        FK to the predecessor (NULL on first)
--
--    The next commit's window starts at this row's committed_at, so
--    every transaction is consumed by exactly one invoice — no
--    double-counting possible (per the user's exact-instant rule).
--
-- 2. invoice_pdfs pointer table — separate from `invoices` so PDF
--    lifecycle (re-render, retention) is decoupled from the immutable
--    financial record. file-service uploads to MinIO and publishes
--    InvoicePdfReady; the consumer in contributions-service UPSERTs
--    this row keyed by invoice_id.
--
-- See plan §3 + §4 in /home/methuseli-mfema/.claude/plans/create-a-plan-for-luminous-tulip.md
-- =====================================================================

-- ── 1. Snapshot columns on invoices ─────────────────────────────────
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS committed_at           TIMESTAMPTZ   NULL,
    ADD COLUMN IF NOT EXISTS opening_balance        NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS closing_balance        NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS payments_in_window     NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS adjustments_in_window  NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS prior_invoice_id       UUID          NULL
        REFERENCES invoices(id) ON DELETE SET NULL;

-- Backfill committed_at = issued_at for pre-V035 invoices so the next
-- commit's window has a known lower bound. opening/closing stay 0 on
-- legacy rows; the statement page surfaces a "balance figures
-- unavailable — invoice predates snapshot capture" note for those.
UPDATE invoices SET committed_at = issued_at WHERE committed_at IS NULL;

-- Lookup index used by InvoiceSnapshotService.findPriorInvoice. The
-- prior is the one with the largest committed_at < this commit's instant
-- for the same (holder, currency). COALESCE collapses group_id /
-- member_id into one indexable column — exactly one is non-null per
-- invoice (the existing routing-key invariant).
CREATE INDEX IF NOT EXISTS idx_invoices_holder_currency_committed
    ON invoices(currency_code, COALESCE(group_id, member_id), committed_at DESC);

-- ── 2. PDF pointer table ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS invoice_pdfs (
    invoice_id  UUID         PRIMARY KEY REFERENCES invoices(id) ON DELETE CASCADE,
    bucket      VARCHAR(255) NOT NULL,
    object_key  VARCHAR(512) NOT NULL,
    rendered_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_invoice_pdfs_rendered_at
    ON invoice_pdfs(rendered_at DESC);
