-- Adds the Phase 15 §24 market-data-config table to the shared
-- test-migration schema. Mirrors production V167.

CREATE TABLE tenant_market_data_config (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    currency          VARCHAR(3)     NOT NULL,
    auto_fetch_enabled BOOLEAN       NOT NULL DEFAULT TRUE,
    source            VARCHAR(20)    NOT NULL
        CHECK (source IN ('RBZ_AUTO', 'SARB_AUTO')),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by        UUID,
    updated_by_email  VARCHAR(255),
    CONSTRAINT uq_tenant_market_data_config UNIQUE (tenant_id, currency)
);
CREATE INDEX idx_tenant_market_data_config_lookup
    ON tenant_market_data_config (tenant_id, auto_fetch_enabled);

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON tenant_market_data_config
    TO public_role;
