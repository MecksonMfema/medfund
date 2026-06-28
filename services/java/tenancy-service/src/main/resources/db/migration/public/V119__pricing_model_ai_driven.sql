-- =====================================================================
-- V119: Add AI_DRIVEN to the pricing_model enum
-- =====================================================================
-- Phase C ships a risk-score endpoint in ai-service. Tenants opt into
-- it by setting pricing_model = 'AI_DRIVEN'. BillingService then:
--   1. Resolves the scheme-default price exactly as STANDARD would.
--   2. Calls POST /api/v1/pricing/score with the per-member fact
--      (chronic conditions, smoking status, BMI, etc.) projected from
--      members.medical_history (V031).
--   3. Multiplies the base price by the returned risk multiplier.
--   4. Persists the multiplier + rationale on the contribution row
--      via the existing audit trail.
--
-- INDIVIDUAL still wins over AI_DRIVEN: if a member has an explicit
-- billing_override_amount set + effective, that's the authoritative
-- price regardless of mode. The AI scorer is a *baseline modifier*;
-- it doesn't override hand-curated per-member prices.
-- =====================================================================

ALTER TABLE public.tenants
    DROP CONSTRAINT IF EXISTS tenants_pricing_model_check;

ALTER TABLE public.tenants
    ADD CONSTRAINT tenants_pricing_model_check
    CHECK (pricing_model IN ('STANDARD', 'INDIVIDUAL', 'AI_DRIVEN'));
