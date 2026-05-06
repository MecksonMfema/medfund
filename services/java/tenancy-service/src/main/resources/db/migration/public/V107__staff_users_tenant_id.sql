-- Link tenant-scoped staff users to their tenant.
-- super_admin users remain NULL (platform-level, not tied to any tenant).
-- All other roles (tenant_admin, claims_clerk, etc.) will have this set on creation.

ALTER TABLE public.staff_users
    ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES public.tenants(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_staff_users_tenant_id ON public.staff_users(tenant_id);
