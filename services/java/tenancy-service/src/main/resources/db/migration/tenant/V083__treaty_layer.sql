-- =====================================================================
-- V083: Treaty layers (Phase 10 §A)
-- =====================================================================
-- Only XoL / StopLoss treaties carry layers. Each layer is a
-- (retention, layer_limit) band at a fixed rate; layer_order gives
-- the deterministic tower stacking.
--
-- reinstatement_count is informational for now — no consumption tracking
-- or reinstatement-premium math per plan §"What We're NOT Doing".

CREATE TABLE treaty_layer (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id           UUID          NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    layer_order         INT           NOT NULL,
    retention           DECIMAL(19,4) NOT NULL,
    layer_limit         DECIMAL(19,4) NOT NULL,
    layer_currency      CHAR(3)       NOT NULL,
    rate                DECIMAL(9,6)  NOT NULL,
    reinstatement_count INT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT treaty_layer_ordered_uq UNIQUE (treaty_id, layer_order),
    CONSTRAINT treaty_layer_amounts_ck CHECK (retention >= 0 AND layer_limit > 0 AND rate >= 0)
);

CREATE INDEX ix_treaty_layer_treaty ON treaty_layer (treaty_id, layer_order);
