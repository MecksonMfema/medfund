-- =====================================================================
-- V085: Treaty applicable insurance lines (Phase 10 §A)
-- =====================================================================
-- Which InsuranceLine values the treaty covers. Enum values mirror
-- services/java/shared/.../InsuranceLine.java. The reinsurance
-- consumers use this table to short-circuit — an adjudicated claim
-- for a line no treaty covers writes zero cessions.

CREATE TABLE treaty_applicable_line (
    treaty_id      UUID        NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    insurance_line VARCHAR(20) NOT NULL,
    PRIMARY KEY (treaty_id, insurance_line),
    CONSTRAINT treaty_applicable_line_ck CHECK (insurance_line IN
        ('HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY'))
);

CREATE INDEX ix_treaty_applicable_line_line ON treaty_applicable_line (insurance_line);
