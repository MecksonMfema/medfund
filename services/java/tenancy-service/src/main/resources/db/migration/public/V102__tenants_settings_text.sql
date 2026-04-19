-- settings and branding are stored as JSON strings by the service layer.
-- R2DBC cannot implicitly cast character varying → jsonb, so use TEXT instead.
ALTER TABLE public.tenants
    ALTER COLUMN settings TYPE TEXT USING settings::TEXT,
    ALTER COLUMN branding TYPE TEXT USING branding::TEXT;
