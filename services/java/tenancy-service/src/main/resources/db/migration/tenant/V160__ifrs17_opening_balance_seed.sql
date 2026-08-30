-- Phase 15 §7 (I29): tenant admin overrides on auto-derived opening
-- balances for IFRS 17 LRC/LIC reconciliation. Auto-derive path lives
-- in §17 Ifrs17ReportController shaping — this table is the override
-- store consulted first (per portfolio × cohort × currency × balance_type
-- × effective_from). When no seed row exists for the tuple, shaping
-- falls back to the earning_schedule (LRC) or claims_reserve_history
-- (LIC) auto-derived value.
--
-- Numbering deviation: plan cites V147 but V143..V147 have all been
-- consumed by concurrent Phase 3/4/5/6 renumbers. V160 is the next free
-- slot in the shared Flyway history (per bug_public_flyway_history_load_bearing
-- + the plan's "bump forward" rule).
--
-- actor_id / actor_email are NOT NULL per plan §7 spec — the seed is
-- treated as an audit-of-record for the manual override; the reason_note
-- likewise is compulsory for the same reason.

CREATE TABLE IF NOT EXISTS ifrs17_opening_balance_seed (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id   UUID          NOT NULL,
    cohort_id      UUID          NOT NULL REFERENCES ifrs17_cohort(id) ON DELETE CASCADE,
    currency       VARCHAR(3)    NOT NULL,
    balance_type   VARCHAR(3)    NOT NULL CHECK (balance_type IN ('LRC', 'LIC')),
    amount         NUMERIC(18,2) NOT NULL,
    effective_from DATE          NOT NULL,
    reason_note    TEXT          NOT NULL,
    actor_id       UUID          NOT NULL,
    actor_email    VARCHAR(200)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ifrs17_opening_balance_seed_uq
        UNIQUE (portfolio_id, cohort_id, currency, balance_type, effective_from)
);

CREATE INDEX IF NOT EXISTS ix_ifrs17_opening_balance_seed_lookup
    ON ifrs17_opening_balance_seed (cohort_id, currency, balance_type, effective_from DESC);
