-- Align the schema with the new API-level requirements on the Member +
-- Dependant DTOs: gender, national_id, email, and scheme_id are required
-- to enrol a member; gender and national_id are required to register a
-- dependant. This migration moves those rules from "API enforces" to
-- "DB also enforces" so any write path (other services, direct SQL,
-- replays from outbox tables) can't sneak NULL through.
--
-- Side effects:
--   - members.national_id and members.email are now UNIQUE per tenant —
--     two members can't share either.
--   - members.scheme_id finally has its foreign key to schemes(id) (was
--     a bare UUID column in V001).
--   - members.enrollment_date is constrained to the 1st of a month,
--     matching `CreateMemberRequest.enrollmentDateOrDefault()`. The
--     `DEFAULT CURRENT_DATE` is dropped because CURRENT_DATE is almost
--     never the 1st — letting it default silently writes a broken row.
--
-- Greenfield assumption: there are no existing member or dependant rows
-- that violate the new constraints. If a dev tenant has one, this
-- migration will fail loudly and the offending row must be cleaned up
-- before retrying — that's by design.

-- ── Members ────────────────────────────────────────────────────────────
ALTER TABLE members ALTER COLUMN gender      SET NOT NULL;
ALTER TABLE members ALTER COLUMN national_id SET NOT NULL;
ALTER TABLE members ALTER COLUMN email       SET NOT NULL;
ALTER TABLE members ALTER COLUMN scheme_id   SET NOT NULL;

-- Restrict gender vocabulary at the DB level too — the API uses a select
-- with these three options, but a future service-to-service call could
-- still POST something else.
ALTER TABLE members
    ADD CONSTRAINT members_gender_valid
        CHECK (gender IN ('male', 'female', 'other'));

-- Add the FK on scheme_id that V001 forgot to declare.
ALTER TABLE members
    ADD CONSTRAINT members_scheme_id_fkey
        FOREIGN KEY (scheme_id) REFERENCES schemes(id);

-- Case-insensitive uniqueness on email (Keycloak treats email this way too)
-- and exact uniqueness on national_id.
CREATE UNIQUE INDEX IF NOT EXISTS uq_members_email_lower
    ON members (LOWER(email));
CREATE UNIQUE INDEX IF NOT EXISTS uq_members_national_id
    ON members (national_id);

-- Enrolment is always anchored to the 1st of a month. Drop the default
-- (today is rarely the 1st) and enforce the day-of-month rule.
ALTER TABLE members ALTER COLUMN enrollment_date DROP DEFAULT;
ALTER TABLE members
    ADD CONSTRAINT members_enrollment_date_first_of_month
        CHECK (EXTRACT(DAY FROM enrollment_date) = 1);

-- ── Dependants ─────────────────────────────────────────────────────────
ALTER TABLE dependants ALTER COLUMN gender      SET NOT NULL;
ALTER TABLE dependants ALTER COLUMN national_id SET NOT NULL;

ALTER TABLE dependants
    ADD CONSTRAINT dependants_gender_valid
        CHECK (gender IN ('male', 'female', 'other'));
