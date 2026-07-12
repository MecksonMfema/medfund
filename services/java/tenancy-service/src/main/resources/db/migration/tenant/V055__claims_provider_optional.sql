-- =====================================================================
-- V055: claims.provider_id becomes nullable
-- =====================================================================
-- Not every claim has a provider — LIFE and DISABILITY payouts go
-- straight to the member, FUNERAL can be paid to either the funeral
-- director or the family. The per-line policy is enforced at the
-- application layer (ClaimService.validateProviderPolicy); at the
-- database level we simply drop the blanket NOT NULL constraint.
--
-- HEALTH / GROUP / TRAVEL / VEHICLE / PROPERTY claims still require a
-- provider — the service-layer validator rejects them at capture time
-- if one isn't supplied. AdjudicationPipeline also uses the same policy
-- so a MEMBER-paid claim can progress through eligibility without one.
-- =====================================================================

ALTER TABLE claims
    ALTER COLUMN provider_id DROP NOT NULL;
