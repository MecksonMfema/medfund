---
date: 2026-10-21T10:45:00+02:00
researcher: methuseli
git_commit: e5051ef3a1cccc79fcebb62668ea589f2dcb98c0
branch: main
repository: medfund
topic: "Layers we can add for members, and how to integrate KYC"
tags: [research, codebase, user-service, file-service, ai-service, audit-service, keycloak, angular, kyc, members]
status: complete
last_updated: 2026-10-21
last_updated_by: methuseli
---

# Research: Layers we can add for members, and how to integrate KYC

**Date**: 2026-10-21T10:45:00+02:00 · **Researcher**: methuseli · **Commit**: `e5051ef3` · **Branch**: main

## Research Question
Check the layers that we can add for members. Include how we can integrate KYC.

## Summary

A "member" in InsureFlow is today a **thin identity + lifecycle + billing record** with almost nothing attached beyond dependants, policies, status history, and billing overrides. There is a wide-open greenfield for additional **member layers** — all of which have *no existing home* and would be new `member_*` tables (next free tenant-schema migration is **V278**).

The layers worth adding, roughly in dependency order:

1. **Member documents / attachments** (no member-level document store exists today — only `claims_attachments`)
2. **KYC / identity-verification layer** (verification status + ID-document capture + screening) — the subject of this request
3. **Structured contacts + communication preferences** (contact info is flat scalar columns today)
4. **Consent / data-subject-rights ledger** (nothing exists; Critical Rule #4 implies a need)
5. **Beneficiary / payout nomination** (today "beneficiary" is only a member+dependant *search* abstraction, no nomination record)
6. **Next-of-kin / emergency contact** (absent)
7. **Risk / underwriting profile** (partially present as `members.medical_history` JSONB; could be generalised line-neutrally)

**KYC specifically is entirely greenfield.** There is no `verification_status`, no ID-document parsing, no sanctions/PEP/watchlist *identity* screening, no liveness/biometrics, and no persisted member-document upload path anywhere in the stack. But the platform already ships the five primitives a KYC layer needs to stand on: an OCR engine, an auditable AI-decision store with a human-review queue, two immutable audit pipelines, MFA step-up, and MinIO byte storage. The build is "assemble + add a verification state machine," not "invent from scratch."

Two notable drifts surfaced: the portals doc designs a member self-registration **`PENDING_VERIFICATION`** gate that has **no backing member status** in the DB (the member status CHECK omits it — it only exists for *providers*), and the member self-service portal the doc describes does **not exist in Angular** (members live in the Flutter app; all Angular member work is staff-operated).

## Findings

### Member data model — what a member is today (Java user-service + tenancy-service)

The `members`/`dependants` **tables are not defined in user-service** — they live in tenancy-service tenant-schema Flyway migrations applied to every tenant schema. user-service holds only the R2DBC entities/services. **Latest tenant migration: `V277__widen_report_job_retention_class.sql`; a new member table slots in at `V278`.**

- `Member` — `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:14`. Identity (`member_number`, `first_name`, `last_name`, `date_of_birth`, `gender`, `national_id`, `email`, `phone`, `address`), linkage (`group_id`, `scheme_id`, `keycloak_user_id`), lifecycle (`status`, `enrollment_date`, `termination_date`, `death_date`/`cause_of_death`, scheduled-status trio, `suspend_reason`), pricing (`age_group_id`, `billing_age_group_id`, billing-override trio), audit columns.
- `Dependant` — `.../entity/Dependant.java:14`. Links via `member_id`; mirrors member identity + age-group/billing fields.
- Member status vocabulary (V242 CHECK): `enrolled, active, suspended, terminated, deactivated, lapsed, deceased`. **No `pending_verification`.**
- Base DDL: `V001__baseline.sql:21` (`members`), `:44` (`dependants`), with later ALTERs V026–V242. The only member-side "document-ish" fields are `members.medical_history` JSONB (V031) and `funeral_policies.health_declaration` JSONB (V032).

**What currently hangs off a member** (child tables keyed by `member_id`): `dependants` (V001), `member_status_history` (V212, append-only status trail), `pending_group_changes` + `member_dependant_swaps` (V048), `member_producer_assignment` (V093), `member_cost_share_liability`/`_settlement` (V078), `member_contribution_presence` (V214), plus `contributions`/`claims` FKs.

**Absent at the member level** (confirmed by grep across user-service + tenant migrations): no `kyc`, `verification`, member `document`/`attachment`, `consent`, `next_of_kin`, `communication_pref`, or normalised `member_contact` table/column anywhere. The only "attachment" is `claims_attachments` (V054), claims-scoped. **This is the clean greenfield for the new layers.**

Member CRUD + lifecycle: `MemberController.java` (`/api/v1/members`) — enroll (`POST /`), unified status endpoint `POST /{id}/actions/{action}`, `record-death`. `MemberService.enroll` stamps member number, derives status, and **provisions a Keycloak user** (realm `tenant-<id>`, role `member`, only when email present; best-effort, swallows failures) — `MemberService.java:198`. Audit via `AuditPublisher.publishAudit(...)`; Kafka lifecycle via `UserEventPublisher` (`medfund.users.member-enrolled`, `.member-lifecycle`, `.member-changed`).

"Beneficiary" today = **search abstraction only**: `BeneficiaryController` (`/api/v1/beneficiaries/search`) returns members + dependants flattened with a `kind` tag for claim capture. There is **no beneficiary/payout-nomination table**.

### Existing KYC-adjacent primitives — what exists vs. absent

**Absent (must be built):** member `verification_status`/`kyc_status` model, persisted member-document upload path, document-type/owner-entity metadata on files, virus/malware scanning, ID-document/passport/proof-of-address parsing, face match/liveness/biometrics, sanctions/PEP/watchlist *identity* screening, document classification, identity-proofing in Keycloak.

**Exists (reusable foundations):**

- **OCR** — `services/python/ai-service/app/api/ocr.py:31` (`POST /api/v1/ai/ocr/extract`): `UploadFile` → `{extracted_text, structured_data, confidence, model_version}`. Engine `app/services/ocr_service.py:37` (Tesseract) + `:52` (LLM structured extraction). **Limitation:** the extraction prompt is hardcoded to healthcare claim docs (`ocr_service.py:59`, system prompt "You are a healthcare document data extractor") — a KYC build needs an **ID-document extraction variant**.
- **Auditable AI-decision store** — `ai_predictions_store` (`app/models/db_models.py:10`): `tenant_id, insurance_line, entity_type, entity_id, prediction_type, model_version, input_features, output, confidence, accepted, reviewed_by, reviewed_at`. Written best-effort via `app/services/prediction_repository.py:21`; features anonymised through `app/core/anonymize.py`. OCR already writes rows with `entity_type="document"` (`ocr.py:57`). Satisfies Critical Rule #3 for any AI-assisted KYC step.
- **Human-review queue** — `app/api/predictions.py:96` (`/predictions`, `/review-queue`, `PUT /{id}/decision`). A KYC verification decision queue can reuse this directly.
- **MinIO byte storage** — `services/go/file-service/internal/storage/minio.go`. But today it only drives the outbound invoice/receipt PDF pipeline (`cmd/main.go:204`). The inbound member-document upload path is **MockStorage stubs** (`internal/storage/s3.go:20`, presigned-URL fakes); `MaxUploadSizeMB` exists but is never enforced; no virus scanning anywhere.
- **MFA step-up** — `services/java/shared/.../security/MfaStepUpGuard.java:32` (requires fresh ≤5-min MFA factor via JWT `amr`; used to gate high-privilege mutations). Authentication assurance, not identity-proofing.
- **Keycloak auth-event capture** — `services/java/keycloak-event-listener/.../MedfundEventListenerProvider.java:40` → `medfund.security.events`. `VERIFY_EMAIL` here = email confirmation, not document verification.
- **Two immutable audit pipelines** — business `medfund.audit.events` (`services/go/audit-service/internal/audit/event.go:5`) and security `medfund.security.events` (`:21`), tenant-scoped query API (`internal/handler/handler.go:78`).
- **Document-linked-to-entity reference pattern** — `siu_evidence` (`V272`): `file_service_ref` + `evidence_type` enum linked to a case. The closest existing "typed document attached to an entity" model to mirror for a `member_documents` table.

**Adjacent-but-not-KYC features** (do not confuse): `finance-service/.../regulatory/aml/AmlAlertService.java:53` is a manual **transaction-monitoring STR** workflow (RAISED→REVIEWED→FILED), not customer sanctions screening. "watchlist" in `FraudTriageTemplates.java:37` matches *provider* IDs, not identity sanctions lists. Fraud check (`ai-service/app/api/fraud.py:34`) is claim fraud, not identity fraud.

### Angular UI — where member layers render (staff-operated)

- Member area lives under the **operational/tenant portal**, not a feature module: `clients/angular/src/app/pages/tenant/members/members.routes.ts:30` (list / `add` / `:id` detail; `:id/history` + `:id/waivers` are ComingSoon stubs).
- **Member detail is NOT tabbed** — it is a vertical stack of `.form-card` blocks: header + Profile (Identity/Contact/Enrolment) + Custom-premium + Dependants (`member-detail.component.html`). A new layer is either a **new `.form-card` block** or a refactor into a `.scheme-tabs` tab shell (templates: `member-balance-detail`, `siu-case-detail`, `payment-run-detail`).
- **No member self-service portal in Angular.** Root dispatch routes everyone non-super-admin to `/tenant/dashboard` (`auth.guard.ts:52`); the only `member` role ref is a test. The member portal is the Flutter app (`clients/flutter`). So KYC work in Angular is **staff capturing/reviewing** a member's documents.
- Frontend `Member` model (`core/services/members.service.ts:24`) omits `nationalId` and `address` — the detail reads them via `(m as any)` casts (`member-detail.component.ts:209`). A KYC layer would add typed verification fields here.
- Reusable upload UI: `shared/components/logo-uploader/logo-uploader.component.ts` (generic drag-drop, hardcoded to logo mimes — would need generalising); claim attachments stage **metadata only** in memory awaiting a future file-service presigned flow (`submit-claim.component.ts:51`). **No file-service Angular client exists yet.**
- **Verification status badges already exist with zero new CSS**: `.badge-pending_verification` (amber) at `styles.scss` / `.claude/design-system.md:92` and `.badge-verified` (blue/info) at `design-system.md:95`.

## Cross-service flow (a KYC verification, as it would move)

A member-KYC verification would flow:

1. **Angular/Flutter** → `POST /api/v1/members/{id}/documents` (new user-service endpoint) requesting a presigned upload URL from **file-service** (`/api/v1/files/upload-url` — needs real storage + metadata + scanning first).
2. Upload emits `medfund.documents.uploaded` → **file-service** triggers virus scan + OCR (per `.claude/architecture.md:254`; scan is currently absent, must be built).
3. **AI service** OCR (`/api/v1/ai/ocr/extract`, ID-document variant) extracts ID fields + confidence, writes `ai_predictions_store` with `entity_type="member"`, `prediction_type="kyc_ocr"`, model version (Critical Rule #3).
4. Optional **sanctions/PEP screening** (new AI or external adapter) → another `ai_predictions_store` row.
5. **user-service** `MemberKycService` persists a `member_verification` row, transitions verification status, emits an `AuditEvent` (`medfund.audit.events`, friendly entityName + dual actor) and a new `medfund.users.member-verification-changed` Kafka event.
6. Staff review via a queue (mirror `ai-service/app/api/predictions.py` review pattern or a new user-service endpoint); decision logged to audit + security pipelines.

## Architecture doc vs. code

- **`pending_verification` member gate — doc promises, DB omits.** `.claude/portals.md:405` designs individual self-registration ending in `PENDING_VERIFICATION or ACTIVE per tenant config`, with a `/tenant/members/pending` review queue (`portals.md:178`). But the member status CHECK (V242) has no `pending_verification` value — it exists only as the **provider** status default (`V105__providers.sql:21`, `ProviderService.java:136`). A member-KYC build must add the status value (and the review queue is unbuilt).
- **Member self-service portal — doc vs. Angular.** `portals.md:412` specs a rich member portal (Profile, Documents "upload ID copies"), but that is the **Flutter** app; Angular has no member role area. Staff-operated capture is the only Angular path today.
- **File-service document pipeline — doc vs. stub.** `.claude/architecture.md:254` (`medfund.documents.uploaded → OCR processing, virus scan`) describes a pipeline the code doesn't implement: file-service inbound uploads are MockStorage, no scan, no metadata model. The pipeline is aspirational.
- **OCR "ID verification" — doc vs. code.** `.claude/ai-integration.md:22` lists "ID verification" under the OCR box, but `ocr_service.py` only has a healthcare-claim extraction prompt. The ID path is unwired.
- **KYC/AML/sanctions as a named feature** — appears in **no** `.claude/*.md` doc and **no** code; only in `docs/appendices/*` and `proposals/AI4I_Track3_Proposal_Draft.md:21` ("AI-1 Biometric KYC … doc parser + Zim-ID adapter + MobileFaceNet") as roadmap aspiration. Entirely greenfield.

## Proposed member layers (answer to "what layers can we add")

Each is a new tenant-schema migration starting at **V278**, a user-service entity/service/controller, Swagger, audit emission, and (optionally) an Angular `.form-card`/tab. Dependency-ordered:

| Layer | New table(s) | Builds on | Notes |
|---|---|---|---|
| **Member documents** | `member_documents` (type, file_service_ref, status, uploaded_by) | `siu_evidence` pattern (`V272`); file-service (needs real storage + scan) | Foundation for KYC; line-neutral |
| **KYC / verification** | `member_verification` (status, method, level, verified_at/by, screening results) | member_documents, OCR, `ai_predictions_store`, predictions review queue | Add `pending_verification` to member status CHECK; reuse `badge-verified`/`badge-pending_verification` |
| **Structured contacts + comms prefs** | `member_contacts`, `member_communication_preferences` | flat `email`/`phone`/`address` today | Multi-channel, preferred-channel, opt-in/out |
| **Consent / data-subject rights** | `member_consent` ledger | audit pipeline; GDPR export at `/admin/data-export` | Critical Rule #4; no erasure flow exists today |
| **Beneficiary / payout nomination** | `member_beneficiaries` | reuse `/api/v1/beneficiaries/search` naming | Distinct from the search abstraction |
| **Next-of-kin / emergency contact** | `member_next_of_kin` | — | Absent today |
| **Risk / underwriting profile** | generalise `members.medical_history` → `member_risk_profile` | V031 JSONB, line-neutral facts in rules-engine | Keep line-neutral per CLAUDE framing rule |

### How to integrate KYC concretely

- **Model**: a `member_verification` row per member (1:1 or history) with `verification_status` (`unverified | pending_verification | verified | rejected | expired`), `verification_level` (e.g. tier 0/1/2 — basic ID vs. full due diligence), `method` (`manual | ocr | biometric`), `verified_at`/`verified_by`, plus screening columns. Add `pending_verification` to the member status CHECK (V242 successor) if KYC gates activation.
- **Documents**: `member_documents` typed on an enum (`NATIONAL_ID | PASSPORT | PROOF_OF_ADDRESS | SELFIE | OTHER`), each `file_service_ref` pointing at real MinIO storage — **prerequisite: file-service must move off MockStorage, add metadata + virus scan** (the single biggest infra dependency).
- **Extraction**: add an ID-document prompt variant to `ocr_service.py` (or a Claude-Vision path — latest models are Opus 4.8 / Sonnet 5); write results to `ai_predictions_store` with `prediction_type="kyc_ocr"`, `entity_type="member"`, model version + confidence (Critical Rule #3).
- **Screening**: sanctions/PEP is net-new — either an external adapter or an AI classifier; results audited the same way. The Zim-ID adapter + face-embedding path is specced in the proposal as the medium-term target.
- **Review**: staff decision queue mirroring `ai-service/app/api/predictions.py:96`, MFA-gated via `MfaStepUpGuard` (staff-facing), each decision emitting business audit + security events.
- **Tenancy**: `member_verification`/`member_documents` live in the **tenant schema** (like `members`), auto-scoped by `TenantWebFilter` — no `public._tenants` join needed (Critical Rule #2).

## Code References

- `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:14` — member identity/lifecycle/billing; no verification fields
- `services/java/user-service/src/main/java/com/medfund/user/entity/Dependant.java:14` — dependant model
- `services/java/user-service/src/main/java/com/medfund/user/service/MemberService.java:198` — enroll + Keycloak provisioning (best-effort)
- `services/java/user-service/src/main/java/com/medfund/user/controller/BeneficiaryController.java` — "beneficiary" = search abstraction only
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:21` — `members` base DDL (next free version V278)
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V272__siu_evidence.sql:5` — typed-document-linked-to-entity reference pattern
- `services/java/tenancy-service/src/main/resources/db/migration/public/V105__providers.sql:21` — `pending_verification` exists for providers, not members
- `services/python/ai-service/app/api/ocr.py:31` + `app/services/ocr_service.py:59` — OCR (healthcare-claim prompt; needs ID variant)
- `services/python/ai-service/app/models/db_models.py:10` — `ai_predictions_store` AI-decision audit spine
- `services/python/ai-service/app/api/predictions.py:96` — reusable human-review queue
- `services/go/file-service/internal/storage/s3.go:20` — MockStorage stub (inbound upload path deferred)
- `services/go/file-service/internal/storage/minio.go` — real byte storage (invoice pipeline only)
- `services/go/audit-service/internal/audit/event.go:5` — business + security audit event shapes
- `services/java/shared/src/main/java/com/medfund/shared/security/MfaStepUpGuard.java:32` — MFA step-up
- `clients/angular/src/app/pages/tenant/members/members.routes.ts:30` — member routes (not tabbed)
- `clients/angular/src/app/core/services/members.service.ts:24` — frontend Member model (no nationalId/verification)
- `.claude/portals.md:405` — designed `PENDING_VERIFICATION` onboarding gate (no member status backing)
- `.claude/design-system.md:92` / `:95` — `badge-pending_verification` / `badge-verified` exist
- `.claude/ai-integration.md:22` — "ID verification" OCR bullet (unwired)
- `proposals/AI4I_Track3_Proposal_Draft.md:21` — Biometric KYC roadmap (not built)

## Architecture Insights (vs. the 9 Critical Rules)

- **Rule #2 (tenant scoping):** new `member_*` tables belong in the tenant schema; auto-scoped by `TenantWebFilter`/`TenantAwareConnectionFactory`. No `public._tenants` join needed (members aren't platform-shared).
- **Rule #3 (AI auditability):** OCR/screening steps must write `ai_predictions_store` with model version, input features (anonymised), confidence, output — the pattern already exists and OCR already uses it.
- **Rule #4 (PII/PHI):** ID documents are PII → must be encrypted at rest + in transit. Repo standard is AES-256-GCM + per-tenant AWS KMS key (`.claude/payments.md:591`), but **KMS is not yet wired** (bank details cleartext today, `payments.md:610`) — a KYC store inherits the same gap and should flag it. Retention per tenant jurisdiction; audit/security events retained ≥7 years. No consent/erasure flow exists → a gap the consent layer closes. Staff review screens are MFA-gated.
- **Rule #6 (Kafka):** verification state changes emit a new `medfund.users.member-verification-changed` topic; cross-service side effects async only.
- **Rule #7 (Swagger):** every new endpoint fully documented (`@Operation`/`@ApiResponses`/`@Schema`), CI enforces no-undocumented-endpoints.
- **Rule #8 (audit):** every verification mutation emits `medfund.audit.events` via `AuditPublisher` with **friendly entityName** (extend the entity-name table) and **dual actorId + actorEmail**.
- **Rule #9 (security events):** access to sensitive KYC data at volume trips the mass-data-access alert; permission denials log to `security_events`.
- **Framing rule:** keep the layers line-neutral ("verification", "document", "beneficiary") — KYC applies across HEALTH/LIFE/FUNERAL/etc., not just the medical vertical.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/research/2026-09-15-comprehensive-seed-data-two-tenants-health-life.md` — definitive member/dependant/group/policy data-model + enrollment-flow map. Best starting reference.
- `thoughts/shared/research/2026-09-15-tenant-provisioning-rbac-model.md` — member↔Keycloak linkage, the (provider) `pending_verification` status, `claim_documents` table, provisioning order.
- `thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md` (+ plan) — the nearest existing design for a **self-service + verify + documents + `pending_verification`** flow; specced for providers but explicitly deferred. The template to mirror for members.
- `thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md` + `2026-09-15-ai-tranche-1-gaps.md` — OCR (Tesseract/LLM, no Vision yet), fraud model, `member_context_client` pattern.
- `thoughts/shared/plans/2026-09-15-ai-service-pilot-readiness.md` + `thoughts/shared/audit/2026-09-15-ai-training-corpus-anonymization.md` — auditable AI request shape + PII anonymization gate (Rule #4).
- `thoughts/shared/plans/2026-09-13-eligibility-quote-dependant-support.md` + `research/2026-09-13-entity-picker-search-fields.md` — member/dependant wire + search patterns; `national_id` is a cheap search add.
- `thoughts/shared/notes/2026-08-30-phase16-regulatory-grill.md` (AML/STR) + `plans/2026-09-05-fraud-siu-report.md` — adjacent compliance/fraud rails (transaction monitoring, not member KYC).

**No prior research/plan/ticket/spec exists on member KYC, identity verification, sanctions/PEP, or member self-service onboarding** — all greenfield.

## Open Questions

1. **Who captures KYC — staff or member?** Angular has no member portal; Flutter does. Is the first build staff-operated capture (Angular) or member self-upload (Flutter), or both?
2. **Does KYC gate member activation?** If yes, add `pending_verification` to the member status CHECK and wire the enroll flow + `/tenant/members/pending` review queue (both currently unbuilt).
3. **File-service prerequisite:** the inbound upload path is MockStorage with no scanning. Is moving file-service to real MinIO + metadata + virus scan in scope, or a blocking dependency to sequence first?
4. **Sanctions/PEP screening:** external vendor adapter vs. in-house AI classifier? Zim-ID adapter + face-embedding (per the proposal) is the medium-term target — in scope now?
5. **Encryption:** KMS is not wired repo-wide. Does the KYC PII store wait for KMS, or ship with the same documented cleartext gap as bank details?
6. **Per-line / per-tenant KYC policy:** verification level/requirements vary by line and jurisdiction — does this become rules-engine content (Rule #5) or static config?

## Related Research
- `thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md`
- `thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md`
- `thoughts/shared/research/2026-09-15-tenant-provisioning-rbac-model.md`
