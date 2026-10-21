-- Test schema for MemberLookupClientDependantIT. Mirrors the columns
-- MemberLookupClient.findDependantById projects (V001 shape + V036
-- member_number). Additive on top of V001 — every earlier IT is
-- unaffected because none of them reads or writes dependants.
--
-- Deliberately omits the members FK to keep AbstractClaimsReportIT's
-- TRUNCATE on `members` working (adding an inbound FK breaks the plain
-- TRUNCATE — the report IT would need TRUNCATE ... CASCADE otherwise).
-- The MemberLookupClient IT seeds a real member first anyway, so
-- data-integrity is enforced at the test level.

CREATE TABLE IF NOT EXISTS dependants (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id     UUID          NOT NULL,
    member_number VARCHAR(50),
    first_name    VARCHAR(200)  NOT NULL,
    last_name     VARCHAR(200)  NOT NULL,
    date_of_birth DATE          NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_dependants_member_number
    ON dependants(member_number)
    WHERE member_number IS NOT NULL;

-- The tenant role established in V001 was granted CRUD on all existing
-- tables at that time. New tables need an explicit grant so the
-- TenantTestContext.put() reactor context (which SETs ROLE public_role)
-- can read them.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON dependants TO public_role;
