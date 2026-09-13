-- =====================================================================
-- V181: Drop the US_NAIC tenant identity table and re-comment the
--       jurisdiction_code column to reflect the SADC-only regulator
--       matrix. The platform now targets IPEC (ZW) as the sole active
--       prudential regulator; CMS (ZA) is retained on the backend for
--       existing rows but hidden from every UI surface, and NAIC (US)
--       is gone entirely (source deleted in the same change).
-- Idempotent: the DROP + COMMENT re-runs cleanly on databases that
-- never provisioned V171.
-- =====================================================================

DROP TABLE IF EXISTS public.us_tenant_naic_config;

COMMENT ON COLUMN public.tenants.jurisdiction_code IS
    'Regulator jurisdiction (e.g. ZW_IPEC_SHORT_TERM, ZA_CMS_MEDICAL_SCHEME). SADC-only platform; NAIC removed. Gates regulator-templated reports. NULL = no regulator-format reports available.';
