-- Tenant-level configuration for auto-generated group registration
-- numbers. Every new group gets a number of the form
-- <prefix><N random digits><suffix> where the three knobs live per-
-- tenant. The random block is uniform in [10^(N-1), 10^N - 1] so its
-- printed width is always exactly N — no leading-zero-loss when a
-- number happens to start with 0.
--
-- Defaults: "GRP-" prefix, no suffix, 6-digit random block. Matches
-- the shape MemberNumberService uses for member_number so operators
-- switching between the two see a familiar pattern.

ALTER TABLE public.tenants
    ADD COLUMN group_number_prefix        VARCHAR(20)  NOT NULL DEFAULT 'GRP-',
    ADD COLUMN group_number_suffix        VARCHAR(20)  NOT NULL DEFAULT '',
    ADD COLUMN group_number_random_length INTEGER      NOT NULL DEFAULT 6
        CHECK (group_number_random_length BETWEEN 3 AND 12);

COMMENT ON COLUMN public.tenants.group_number_prefix IS
    'Static text prepended to every auto-generated group registration_number.';
COMMENT ON COLUMN public.tenants.group_number_suffix IS
    'Static text appended to every auto-generated group registration_number.';
-- COMMENT ON COLUMN accepts only a string literal, not an expression —
-- so this is one long line with an embedded space, not multiple
-- lines glued with ||.
COMMENT ON COLUMN public.tenants.group_number_random_length IS
    'Digit count for the random block. Constrained to [3, 12] — three digits is the smallest that gives enough room to avoid collisions in trivial tenants; twelve is the largest before the BIGINT overflow risk.';
