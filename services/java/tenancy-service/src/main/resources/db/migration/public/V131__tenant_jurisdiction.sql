-- =====================================================================
-- V131: Tenant regulator jurisdiction code.
--
-- Gates regulator-templated reports (Phase 16 of the financial-reporting
-- suite): IPEC quarterly return, CMS ASR, NAIC Schedule P/F, PMB spend,
-- AML/STR, VAT return, etc. A report is only visible to a tenant whose
-- jurisdiction_code matches the report's declared regulator.
--
-- Nullable — tenants that don't file with a supported regulator can leave
-- it blank and simply won't see regulator-format reports. The catalogue
-- of accepted codes is a plain enum in the Java tenant-admin form
-- (com.medfund.tenancy.TenantJurisdiction) so no CHECK constraint here;
-- the enum evolves faster than the migration cadence.
-- =====================================================================

ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS jurisdiction_code VARCHAR(40);

COMMENT ON COLUMN public.tenants.jurisdiction_code IS
    'Regulator jurisdiction (e.g. ZW_IPEC_SHORT_TERM, ZA_CMS_MEDICAL_SCHEME, US_NAIC). Gates regulator-templated reports. NULL = no regulator-format reports available.';
