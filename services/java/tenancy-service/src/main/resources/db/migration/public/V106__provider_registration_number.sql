-- Rename practice_number → registration_number.
-- The field is now a generic registration/licence/AHFOZ number that works
-- across all insurance types. The label shown to users is driven by each
-- tenant's settings.providerRegLabel, not the column name.
ALTER TABLE public.providers RENAME COLUMN practice_number TO registration_number;
