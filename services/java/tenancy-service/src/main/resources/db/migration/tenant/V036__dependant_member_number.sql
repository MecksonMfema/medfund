-- =====================================================================
-- V036: dependants.member_number — give dependants their own number
-- =====================================================================
-- Pre-V036 dependants were identified by UUID only. Operators in
-- this domain expect a structured number (see public.V036 for the
-- per-tenant scheme that drives the format).
--
-- NULL allowed because pre-V036 rows have none. New dependants
-- created via DependantService.create always populate it.
--
-- Cross-table uniqueness (a dependant's number can't collide with a
-- member's number) is enforced at the application level in
-- MemberNumberService — the partial UNIQUE index below only protects
-- against duplicates WITHIN the dependants table.
-- =====================================================================

ALTER TABLE dependants
    ADD COLUMN IF NOT EXISTS member_number VARCHAR(50) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_dependants_member_number
    ON dependants(member_number)
    WHERE member_number IS NOT NULL;
