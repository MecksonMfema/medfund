-- =====================================================================
-- V070: role_permissions seed for finance:configure_auto_ctc
--
-- Companion to public.V129 (tenant_ctc_auto_config). Grants the new
-- configure-auto-CTC permission to the built-in tenant_admin role so the
-- feature is usable out of the box; tenants can carve it out via the
-- role editor if they want a narrower audience.
--
-- Split from V069 so Phase 4 lands additively — V069 stays as the CTC
-- lifecycle seed (finance:reverse_ctc_payment, finance:view_member_payables)
-- and doesn't retro-couple to Phase 4's auto-drafting feature.
-- =====================================================================

INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), r.id, 'finance:configure_auto_ctc', 'full'
  FROM roles r
 WHERE r.name = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;
