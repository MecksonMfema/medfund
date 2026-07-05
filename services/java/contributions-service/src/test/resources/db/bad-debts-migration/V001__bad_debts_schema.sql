-- Minimal schema for the BalanceQueryRepository bad-debts IT. Mirrors the
-- columns the production tenant-side V001__baseline (members, groups,
-- balances) plus V038 (groups.email fallback), V043 (group liaison
-- kinds), and the platform-wide public.staff_users table that the group
-- half of the union LEFT JOINs. Lives in `public` because the IT
-- doesn't spin a tenant schema — the filter under test isn't tenant-
-- aware at the SQL level (tenant scoping happens above via schema
-- routing), so a single-schema fixture is sufficient.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Platform-wide staff table. Only the columns the balance query touches.
CREATE TABLE staff_users (
    id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email  VARCHAR(200)
);

CREATE TABLE members (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name     VARCHAR(120),
    last_name      VARCHAR(120),
    email          VARCHAR(200),
    member_number  VARCHAR(50),
    -- Null for ungrouped individuals; FK'd to groups for grouped members.
    -- The bad-debts MEMBER half requires `group_id IS NULL` so a grouped
    -- member never appears (memory: feedback_grouped_members_cannot_pay).
    group_id       UUID,
    -- 'active' / 'suspended' / 'deactivated' / 'terminated'. Bad-debts
    -- filter is the last two.
    status         VARCHAR(20)  NOT NULL DEFAULT 'active'
);

CREATE TABLE groups (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(200) NOT NULL,
    registration_number   VARCHAR(50),
    email                 VARCHAR(200),
    status                VARCHAR(20)  NOT NULL DEFAULT 'active',
    -- V043 liaison-kind routing — the group half of the union LEFT JOINs
    -- three source tables keyed by this discriminator. The IT doesn't
    -- exercise the liaison-email fallback chain (that belongs in a
    -- separate test focused on subject_email COALESCE order); we just
    -- need the columns present so the LEFT JOINs parse.
    liaison_kind          VARCHAR(20),
    liaison_user_id       UUID
);

-- Referenced by the group half's LEFT JOIN when liaison_kind = 'LIAISON'.
CREATE TABLE group_liaisons (
    id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email  VARCHAR(200)
);

-- Running-balance tables the query joins to filter by
-- (currency_code, balance) and read the last_charge_at / last_payment_at
-- projections. UNIQUE constraint mirrors the production V034 index that
-- guarantees one balance row per (subject, currency).
CREATE TABLE member_running_balance (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id        UUID         NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    currency_code    VARCHAR(3)   NOT NULL,
    balance          NUMERIC(18,2) NOT NULL DEFAULT 0,
    last_charge_at   TIMESTAMPTZ,
    last_payment_at  TIMESTAMPTZ,
    UNIQUE (member_id, currency_code)
);

CREATE TABLE group_running_balance (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id         UUID         NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    currency_code    VARCHAR(3)   NOT NULL,
    balance          NUMERIC(18,2) NOT NULL DEFAULT 0,
    last_charge_at   TIMESTAMPTZ,
    last_payment_at  TIMESTAMPTZ,
    UNIQUE (group_id, currency_code)
);
