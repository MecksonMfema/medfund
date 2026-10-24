---
date: 2026-10-23
git_commit: e5051ef3a1cccc79fcebb62668ea589f2dcb98c0
branch: main
ticket: none — grounded in research + interactive scope decisions
research:
  - thoughts/shared/research/2026-10-22-kyc-pilot-horizontal.md
  - thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md
steer: "reconcile toward a standalone, line-agnostic, sellable KYC horizontal: party-neutral subject, build the entitlement seam, decide kyc-service vs user-service module"
services_touched: [tenancy-service, user-service, shared, rules-engine, kyc-service, file-service, gateway, ai-service, finance-service, angular, flutter]
status: draft
kind: program-overview
plans:
  - thoughts/shared/plans/2026-10-23-kyc-plan-a-horizontal-foundation.md
  - thoughts/shared/plans/2026-10-23-kyc-plan-b-verification-end-to-end.md
  - thoughts/shared/plans/2026-10-23-kyc-plan-c-self-service-biometrics.md
---

# KYC Horizontal + Platform Encryption — Program Overview

## What this program is

Build **KYC (identity verification) as InsureFlow's first horizontal product** — a line-agnostic
capability that verifies any party (member, dependant, provider, policyholder) across any insurance line,
gated by a **new per-tenant module-entitlement seam** so it can be sold and switched on independently of
which product line a tenant runs. KYC is the pilot that proves the whole horizontal-packaging thesis: if
a capability this self-contained can be built line-neutral, sold standalone, and enforced per tenant,
every later horizontal (billing, claims, underwriting…) follows the same frame.

Because the agreed scope includes **biometric verification** and **repo-wide KMS encryption-at-rest**,
this is not a single feature — it is a multi-month program spanning the entitlement seam, a new service,
the rules engine, two AI capabilities, platform encryption, the Angular admin, and the Flutter member app.
It is therefore structured as **three sequenced, independently-shippable plans** under one overview.

This program **reconciles and supersedes** the member-coupled
`thoughts/shared/plans/2026-10-21-member-layers-and-kyc-integration.md`, which modelled KYC as a
`member_id`-bound sub-resource of user-service. See *Reconciliation* below.

## Settled decisions (interactive, binding)

Every fork from the research was resolved with the user before planning. Recorded verbatim as binding
scope. **Plan A was additionally grilled on 2026-10-23 (decisions G1–G8, fixes F1–F2); refinements are folded
in below and detailed in `scratchpad/plan-a-grilling-log.md`.**

1. **Service boundary** → **Dedicated `kyc-service`** (new Java service, port 8087, route prefix
   `/api/v1/verifications/*`). Not a user-service module. (It still does *not* own its DDL — tenancy-service
   owns all tenant-schema migrations; see Constraint 3.)
2. **Entitlement seam** → **Reusable per-tenant module-entitlement** (`tenant_module_entitlements` +
   `TenantEntitlementRegistry` + gateway gate + `@RequiresEntitlement`). KYC is the first consumer; the
   seam is the Phase-0 unlock for all future horizontals. **(G3)** fails **closed** (uncertain → 403; gateway
   logs-and-continues, doesn't crash). **(G4)** enforced at **both** the gateway (edge) and the aspect
   (safety net vs hand-maintained prefix-map drift). **(G5)** a toggle emits an audit **and** a security event.
3. **Per-line policy** → **Rules-engine from day one** (`VerificationFact` + `VERIFICATION`
   `RuleCategory`). Per-line/per-jurisdiction verification policy is rule content, not engine code
   (Critical Rule #5).
4. **First subjects** → **Members + Providers**, to prove the `(subject_type, subject_id)` abstraction
   across both the tenant schema (member) and the public schema (provider).
5. **Capture model** → **Staff-mediated (Angular) AND member self-upload (Flutter)**. Providers are
   admin-verified; members can be captured by staff *or* self-serve on mobile.
6. **Biometrics** → **In scope** (face-match + liveness), as a follow-on phase on the proven Flutter +
   prediction-store rails. **(G2)** sold as a **separate SKU** — a distinct `KYC_BIOMETRICS` entitlement, so
   base document-KYC can be sold without biometrics.
7. **Encryption / KMS** → **Full repo-wide KMS wiring** (per-tenant AWS KMS, AES-256-GCM) — closes the
   documented cleartext gap for bank details + KYC PII + biometric templates. Ordered **before** any
   biometric/PII store lands.

## The three plans

| Plan | Scope | Proves / ships | Depends on |
|---|---|---|---|
| **A — Horizontal foundation** (3 phases, G1) | Reusable entitlement seam · kyc-service skeleton + party-neutral schema + gated routing · rules-engine `VerificationFact`/`VERIFICATION`. *(file-service pipeline moved to B, G1.)* | The sellable-horizontal + entitlement thesis. An entitled tenant's `/api/v1/verifications/*` passes the gate; an un-entitled one is 403'd at the gateway. | — |
| **B — KYC verification end-to-end** | **file-service inbound pipeline (Phase 0, from G1)** · verification core (party-neutral state machine, MEMBER + PROVIDER) · OCR ID-extraction · sanctions/PEP screening · Angular staff capture + review UI + entitlement toggle | A working, **sellable document-KYC product** — staff capture, OCR, screening, MFA-gated human decision, full audit. | A |
| **C — Self-service + biometrics** | Repo-wide KMS (first) · Flutter member foundation (auth/API/routing) · Flutter self-upload KYC · biometric face-match + liveness | Member self-service onboarding and biometric assurance, on an encrypted-at-rest platform. | B |

Each plan ends by handing off to `implement-plan`. Implement A fully (and ideally ship it) before B is
built; B before C. KMS is the **first phase of C** specifically so no biometric or ID-document PII store
is populated before encryption-at-rest exists.

## Reconciliation with the 2026-10-21 member-KYC plan

| Dimension | 2026-10-21 plan (superseded) | This program |
|---|---|---|
| Subject | `member_id`-bound (`member_documents`, `member_verification`) | Party-neutral `(subject_type, subject_id, insurance_line?)` |
| Boundary | user-service module | Dedicated `kyc-service` (:8087) |
| Entitlement | none (not sellable standalone) | Reusable `tenant_module_entitlements` seam |
| Per-line policy | static per-tenant config, rules "later" | rules-engine `VerificationFact` from day one |
| Encryption | ship on documented cleartext gap | repo-wide KMS before PII/biometric stores |
| Capture | staff (Angular) + Flutter | same, **plus biometrics** |

The 2026-10-21 plan's *other five member layers* (contacts, consent, beneficiary, next-of-kin, risk
profile) are **not part of this program** — they are genuine member sub-resources, orthogonal to the KYC
horizontal, and remain available to pick up separately.

## Program-wide constraints (the 9 Critical Rules as they bind here)

1. **Rule #2 — tenant scoping / party-neutrality.** MEMBER subjects live in the tenant schema
   (auto-scoped by `TenantAwareConnectionFactory`); PROVIDER subjects live in `public.providers` and any
   per-tenant read must join `public.provider_tenants`. The `(subject_type, subject_id)` resolver must
   branch on schema per type.
2. **Rule #3 — AI auditability.** OCR, screening, and biometric match each write `ai_predictions_store`
   (model version, anonymised features, confidence, output) with a human-reviewable trail. The
   `prediction_type` discriminator (`ocr_id_document`, `sanctions_pep_screening`, `biometric_match`)
   keeps them in one auditable pattern.
3. **Rule #4 — PII/PHI.** ID documents and biometric templates are PII (biometrics is heightened,
   irreversible PII). Repo-wide KMS (Plan C, first) closes the at-rest gap. The anonymiser
   (`ai-service/app/core/anonymize.py`) must strip identity keys *before* any ID field reaches the
   prediction store.
4. **Rule #5 — per-tenant rules.** Verification requirements (required documents per line/country,
   verification tier, screening thresholds) are `RuleDefinition` content under the new `VERIFICATION`
   category — not kyc-service code.
5. **Rule #6 — Kafka.** State changes emit `medfund.kyc.verification-changed`; document uploads emit
   `medfund.documents.uploaded`; entitlement changes emit `platform.module-entitlements.v1`. OCR/
   screening/biometric calls are synchronous read/compute (allowed), not side-effect calls.
6. **Rule #7 — Swagger.** Every new endpoint fully documented at `/swagger-ui` (Java) / `/docs` (Python).
7. **Rule #8 — audit.** Every verification mutation emits `medfund.audit.events` via `AuditPublisher`
   with friendly `entityName` + dual actorId/actorEmail.
8. **Rule #9 — security events.** A module-entitlement toggle emits a security event (`ENTITLEMENT_CHANGED`),
   not only an audit event (G5) — it's a privilege-level change. Bulk KYC-PII access trips mass-data-access alerting; entitlement/
   permission denials log to `security_events`.
9. **Framing rule.** Line-neutral vocabulary throughout — "subject", "verification", "document",
   "party" — never "patient"/"clinical".

## Key cross-cutting constraints (from research)

- **Constraint 1 — no per-tenant entitlement exists today.** RBAC permissions are the only genuine
  per-tenant gate; `Plan.features` is inert; platform flags are global-by-design; the gateway does no
  entitlement gating (the flag plumbing is wired but unused). Plan A builds the missing seam.
- **Constraint 2 — party-neutrality is idiomatic but cross-schema.** The `(kind, id)` discriminator
  pattern already exists (`Group.liaison_kind`, `claims.payee_type`, `provider_tenants`); the only common
  identity handle across party types is the `UUID id` (not `national_id`). Resolution is per-subject-type.
- **Constraint 3 — tenancy-service owns ALL tenant-schema DDL.** kyc-service does not own its tables; its
  DDL ships as `V###__kyc_*.sql` under `tenancy-service/.../db/migration/tenant/` (next free version
  **V278**) and is applied to every tenant schema by `SchemaProvisioningService` / `TenantMigrationRunner`.
- **Constraint 4 — file-service inbound path is a stub.** Today it's MockStorage with no virus scan; the
  real multipart → scan → MinIO pipeline is a hard prerequisite (Plan A, Phase 2).
- **Constraint 5 — OCR is healthcare-coupled.** The live prompt is a "healthcare document data
  extractor"; KYC needs a separate ID-document variant (Plan B) or it inherits a medical bias (framing-rule
  violation).
- **Constraint 6 — naming collisions.** "Member Verification" (provider portal) = eligibility;
  `AmlAlertService` = transaction-monitoring STR; `FraudTriage` "watchlist" = provider IDs. KYC must carve
  distinct, line-neutral vocabulary and not be mistaken for these.

## What the whole program is NOT doing

- The other five member layers from the 2026-10-21 plan (contacts, consent, beneficiary, next-of-kin,
  risk profile).
- An external sanctions/PEP vendor — screening is an in-house LLM classifier behind a `ScreeningProvider`
  seam a real vendor can later slot into.
- Any change to Keycloak as the identity provider (no custom auth).
- Rules-engine authoring UI changes beyond registering the new category/templates.
- **Re-verification episode-grouping (G7).** Verification is one current-state row per subject + a status
  history trail + the immutable prediction store; cycles are reconstructable but not grouped into explicit
  episodes. `expired` is set by a later scheduled job (named, not built in the pilot).

## References

- Research: `thoughts/shared/research/2026-10-22-kyc-pilot-horizontal.md`,
  `thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md`
- Superseded plan: `thoughts/shared/plans/2026-10-21-member-layers-and-kyc-integration.md`
- Architecture: `.claude/multi-tenancy.md`, `.claude/rules-engine.md`, `.claude/ai-integration.md`,
  `.claude/portals.md`, `.claude/payments.md` (KMS/cleartext gap), `.claude/design-system.md`
- Plans: A `…kyc-plan-a-horizontal-foundation.md` · B `…kyc-plan-b-verification-end-to-end.md` ·
  C `…kyc-plan-c-self-service-biometrics.md`
