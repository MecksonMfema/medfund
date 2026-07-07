-- =====================================================================
-- V126: Tenant-configurable member-number issuance shape
-- =====================================================================
-- Companion to V120 (member_number_scheme) and V125 (group_number_*).
-- V120 chose the STRATEGY (INDEPENDENT vs SHARED_WITH_SUFFIX);
-- this one lets tenants configure the SHAPE — prefix, dependant
-- prefix, random-block length, suffix separator, suffix padding,
-- suffix start.
--
-- Defaults match the values MemberNumberService hard-coded before this
-- migration so byte-for-byte compatibility is preserved for tenants
-- who never touch these knobs:
--     MBR-<6 random digits>      (INDEPENDENT members)
--     DEP-<6 random digits>      (INDEPENDENT dependants)
--     MBR-<6 random digits>-01   (SHARED_WITH_SUFFIX principal)
--     MBR-<6 random digits>-02   (first shared-suffix dependant)
--
-- Sample custom config: `member_number_prefix='MED-'`,
-- `member_number_random_length=6`, `member_number_suffix_separator='_'`,
-- `member_number_suffix_padding=4` produces `MED-483012_0001`.
--
-- Bounds:
--   random_length ∈ [3,12] — mirrors V125's group_number knob range.
--     Below 3 collides trivially; above 12 risks BIGINT overflow when
--     the app treats the block as a numeric.
--   suffix_padding ∈ [1,4] — 4 = up to 9999 dependants per household,
--     more than any realistic policy.
--   suffix_start ≥ 0 — 0 lets a tenant number the principal `-00`
--     if that matches their existing paper records.

ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS member_number_prefix           VARCHAR(20) NOT NULL DEFAULT 'MBR-',
    ADD COLUMN IF NOT EXISTS dependant_number_prefix        VARCHAR(20) NOT NULL DEFAULT 'DEP-',
    ADD COLUMN IF NOT EXISTS member_number_random_length    INTEGER     NOT NULL DEFAULT 6
        CHECK (member_number_random_length BETWEEN 3 AND 12),
    ADD COLUMN IF NOT EXISTS member_number_suffix_separator VARCHAR(4)  NOT NULL DEFAULT '-',
    ADD COLUMN IF NOT EXISTS member_number_suffix_padding   INTEGER     NOT NULL DEFAULT 2
        CHECK (member_number_suffix_padding BETWEEN 1 AND 4),
    ADD COLUMN IF NOT EXISTS member_number_suffix_start     INTEGER     NOT NULL DEFAULT 1
        CHECK (member_number_suffix_start >= 0);

COMMENT ON COLUMN public.tenants.member_number_prefix IS
    'Static text prepended to every auto-generated member.member_number under this tenant. Default MBR-.';
COMMENT ON COLUMN public.tenants.dependant_number_prefix IS
    'Static text prepended to every INDEPENDENT-scheme dependant.member_number under this tenant. Ignored for SHARED_WITH_SUFFIX which inherits from the parent. Default DEP-.';
COMMENT ON COLUMN public.tenants.member_number_random_length IS
    'Digit count for the random block. Constrained to [3, 12] — three digits collides trivially, twelve risks BIGINT overflow.';
COMMENT ON COLUMN public.tenants.member_number_suffix_separator IS
    'Separator between the parent number and the dependant suffix under SHARED_WITH_SUFFIX. Default hyphen.';
COMMENT ON COLUMN public.tenants.member_number_suffix_padding IS
    'Zero-padding width of the dependant suffix under SHARED_WITH_SUFFIX. 2 → -01,-02,...,-99; 4 → -0001,...,-9999.';
COMMENT ON COLUMN public.tenants.member_number_suffix_start IS
    'Starting suffix number for the principal under SHARED_WITH_SUFFIX. 1 → principal is -01, first dependant -02; 0 → principal is -00, first dependant -01.';
