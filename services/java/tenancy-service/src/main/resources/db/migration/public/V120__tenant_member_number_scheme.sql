-- =====================================================================
-- V036: Per-tenant member-number issuance scheme
-- =====================================================================
-- Two enum values:
--   INDEPENDENT          → members get "MBR-XXXXXX", dependants get
--                          "DEP-XXXXXX". Unique numbers each.
--   SHARED_WITH_SUFFIX   → members get "MBR-XXXXXX-01"; the member's
--                          first dependant gets "MBR-XXXXXX-02", the
--                          next "-03", etc. Suffixes are monotonically
--                          increasing — never reused even after a
--                          dependant is soft-deleted.
-- Default INDEPENDENT keeps existing tenants byte-identical (the
-- legacy generator produced "MBR-XXXXXX" without suffix; INDEPENDENT
-- preserves that prefix for members + adds DEP- for new dependants).
--
-- See plan §2A in /home/methuseli-mfema/.claude/plans/create-a-plan-for-luminous-tulip.md
-- =====================================================================

ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS member_number_scheme VARCHAR(30) NOT NULL
        DEFAULT 'INDEPENDENT'
        CHECK (member_number_scheme IN ('INDEPENDENT', 'SHARED_WITH_SUFFIX'));
