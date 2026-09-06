-- Section keys REPORTING_FACULTATIVE_BROWSE / _QUEUE and
-- REPORTING_ADJUSTMENTS_DRAFT / _APPROVE were retired when the
-- corresponding operations-portal pages were merged in 2026-09.
-- Delete any tenant rows referencing them so the admin
-- sidebar-visibility grid stays consistent with the SidebarSectionKey
-- enum (which no longer contains these values).
--
-- Tenants who had disabled one of the retired entries will see the new
-- merged entry (REPORTING_FACULTATIVE or REPORTING_COMMISSION_CORRECTIONS)
-- by default: "absent row = enabled" applies. This is intentional -- the
-- merged entry is a new sidebar item, so the previous per-half disable
-- does not carry over.

DELETE FROM public.tenant_sidebar_section_config
 WHERE section_key IN (
   'REPORTING_FACULTATIVE_BROWSE',
   'REPORTING_FACULTATIVE_QUEUE',
   'REPORTING_ADJUSTMENTS_DRAFT',
   'REPORTING_ADJUSTMENTS_APPROVE'
 );
