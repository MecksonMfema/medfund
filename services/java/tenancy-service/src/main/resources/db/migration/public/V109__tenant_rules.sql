-- Per-tenant adjudication rules. The Drools-backed runtime
-- (services/java/rules-engine) compiles these into a KieSession per tenant.
-- Tenant admins author rules through the Rules Engine UI; this table is the
-- single source of truth for what the engine evaluates.
--
-- definition  — structured RuleDefinition JSON (conditions + action) consumed
--               by DrlCompiler when no DRL override is supplied.
-- drl_override — optional raw DRL string. When non-null it bypasses the
--               structured definition for advanced authors who need DRL
--               features the visual editor doesn't expose.
-- enabled     — soft toggle. Disabled rules stay in the table (preserving
--               history + version) but are excluded when the tenant's
--               KieSession is rebuilt.
-- version     — bumped on every UPDATE so consumers can detect drift.

CREATE TABLE IF NOT EXISTS public.tenant_rules (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    rule_key      VARCHAR(150) NOT NULL,
    name          VARCHAR(200) NOT NULL,
    description   TEXT,
    category      VARCHAR(50)  NOT NULL,
    template_id   VARCHAR(100),
    priority      INTEGER      NOT NULL DEFAULT 50,
    definition    JSONB        NOT NULL,
    drl_override  TEXT,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    version       INTEGER      NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    UNIQUE (tenant_id, rule_key)
);

CREATE INDEX IF NOT EXISTS idx_tenant_rules_tenant   ON public.tenant_rules(tenant_id);
CREATE INDEX IF NOT EXISTS idx_tenant_rules_category ON public.tenant_rules(tenant_id, category);
CREATE INDEX IF NOT EXISTS idx_tenant_rules_enabled  ON public.tenant_rules(tenant_id, enabled);
