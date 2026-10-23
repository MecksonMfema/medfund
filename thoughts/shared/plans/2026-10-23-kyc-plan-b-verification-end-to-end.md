---
date: 2026-10-23
git_commit: e5051ef3a1cccc79fcebb62668ea589f2dcb98c0
branch: main
ticket: none — grounded in research + interactive scope decisions
research:
  - thoughts/shared/research/2026-10-22-kyc-pilot-horizontal.md
  - thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md
steer: "reconcile toward a standalone, line-agnostic, sellable KYC horizontal: party-neutral subject, build the entitlement seam, decide kyc-service vs user-service module"
services_touched: [file-service, kyc-service, ai-service, user-service, tenancy-service, angular]
status: updated (2026-10-23) — received the file-service pipeline from Plan A's grilling (G1); G8 resolver guard added
kind: plan
program: thoughts/shared/plans/2026-10-23-kyc-horizontal-program-overview.md
sequence: B (of A→B→C)
depends_on: thoughts/shared/plans/2026-10-23-kyc-plan-a-horizontal-foundation.md
---

# KYC Plan B — Verification End-to-End

> **Updated 2026-10-23 from Plan A's grilling.** The **file-service inbound document pipeline moved here from
> Plan A (G1)** as new **Phase 0** (it enables document capture, which Plan A didn't need). Also: kyc-service's
> `SubjectResolverRegistry` must reject un-resolvable subject types with 400 (**G8** compensating control), and
> `insurance_line` is contextual-only per **G6**. See `scratchpad/plan-a-grilling-log.md`.

## Overview

On Plan A's foundation, build the **working, sellable document-KYC product**: the party-neutral verification
state machine (verifying MEMBER *and* PROVIDER subjects), ID-document OCR, in-house sanctions/PEP screening,
and the Angular staff capture + MFA-gated review UI with the platform-admin entitlement toggle. At the end of
Plan B a tenant entitled to KYC can capture a subject's documents, have them OCR-extracted and screened, and a
staff member can approve/reject with a full audit trail.

Prerequisites (from Plan A): the entitlement gate, `kyc_verifications`/`kyc_documents` tables, and
`VerificationFact`/`VERIFICATION` in the rules engine. ~~the file-service upload pipeline~~ → **now Plan B
Phase 0** (moved from Plan A by G1; it's a prerequisite for Phase 1's document registration).

## Current State Analysis (entering Plan B)

- kyc-service exists (:8087) with entity/repo for `kyc_verifications`/`kyc_documents` and a read-only
  `VerificationController` gated by `@RequiresEntitlement(ModuleKey.KYC)`.
- Provider verification precedent lives in **user-service** `ProviderService.onboard()/verifyAhfoz()` —
  status `pending_verification → active`, audit via `AuditEvent.create(...)`, Kafka `providerOnboarded`.
  Provider status is a free-text column on `public.providers` (no CHECK); provider is platform-shared with
  `public.provider_tenants` membership.
- Member status machine is `MemberStatusTransitionService` + `StatusTransitionRecorder` (history row THEN
  save, one tx; reason codes; idempotent no-op); member status CHECK (`V242`) has **no** `pending_verification`.
- OCR: `ai-service/app/api/ocr.py` + `ocr_service.py` — prompt hardcoded to a "healthcare document data
  extractor". `ai_predictions_store` + `predictions.py` review queue exist. `anonymize._STRIPPED_KEYS` strips
  claim PII but **not** identity PII.
- Angular: member area under `pages/tenant/members/`; detail is a vertical `.form-card` stack (tab pattern
  available via `member-balance-detail`); `ApiService.postMultipart` exists; no file-service client; `badge-verified`/
  `badge-pending_verification` exist; Playwright harness `signInAs`+`apiMocks`.

## Desired End State

- `kyc-service` resolves a subject `(subject_type, subject_id)` across schemas: MEMBER from the tenant
  schema, PROVIDER from `public.providers` **with the `public.provider_tenants` membership join** (Critical
  Rule #2). A verification can be opened, documents registered, OCR + screening pulled, rules evaluated, and
  a decision recorded — MFA-gated — emitting audit + `medfund.kyc.verification-changed`.
- If a tenant's config gates activation, approving a MEMBER verification drives the member
  `pending_verification → active` transition via user-service; approving a PROVIDER drives
  `pending_verification → active` via the existing provider path.
- OCR has an ID-document variant writing an auditable `ocr_id_document` prediction (identity PII stripped
  pre-persist); screening writes `sanctions_pep_screening`; a `hit`/`review` forces manual review.
- Angular: a KYC capture + review surface for members **and** providers, a `/tenant/members/pending` (and
  provider equivalent) review queue, and a platform-admin entitlement toggle — all gated by permission *and*
  the KYC entitlement.

### Key Discoveries
- Model the kyc-service state machine on `MemberStatusTransitionService` (history-then-save, reason codes,
  idempotent no-op) but over `kyc_verifications`; record history in a `kyc_status_history` table via a
  `StatusTransitionRecorder.recordKyc(...)` sibling (mirror `recordMember`).
- Cross-service activation is a **side effect → Kafka** (Rule #6): kyc-service emits
  `medfund.kyc.verification-changed`; user-service consumes it to drive member/provider activation. Do *not*
  have kyc-service call user-service synchronously to mutate status.
- OCR/screening are **read/compute → synchronous REST** (Rule #6 allows this): kyc-service pulls them.
- Reuse `predictions.py` review queue for AI artifacts; the human *decision of record* is the kyc-service
  MFA-gated endpoint (not the AI queue).
- Add `pending_verification` to the member status CHECK via a **new** migration (never edit `V242`).

## What We're NOT Doing
- No Flutter, no biometrics, no KMS (Plan C). Plan B stores document handles + OCR/screening prediction
  *ids* on `kyc_verifications`; it does **not** persist raw extracted identity fields on the KYC row (they
  live only in the anonymised prediction store) — so Plan B adds no new cleartext-PII column awaiting KMS.
- No external sanctions vendor (in-house LLM classifier behind a `ScreeningProvider` seam).
- No new member *layers* beyond the verification/activation gate.

## Implementation Approach
File-service pipeline first (**Phase 0**, moved from Plan A by G1) — it produces the document refs Phase 1
registers. Then verification core (Phase 1) — the state machine + subject resolution + activation events,
verifiable via API for both subject types. Then OCR (Phase 2) and screening (Phase 3) as synchronous
enrichments. Then the Angular surface (Phase 4) once the backend contracts are stable.

**Contracts fixed up front:**
- Kafka: `medfund.kyc.verification-changed` (producer kyc-service; consumer user-service for activation).
- Sync REST reads: kyc-service → file-service (`GET /files/documents/stream`), kyc-service → ai-service
  (`/ocr/extract`, `/screening/sanctions-pep`). All fail-open (a slow/failed AI call leaves the subject
  `pending_verification` for manual review — it never blocks).
- Subject resolution contract: a `SubjectResolver` in kyc-service with a per-type strategy
  (`MemberSubjectResolver` → tenant schema; `ProviderSubjectResolver` → `public.providers` ⋈ `provider_tenants`).

---

## Phase 0: file-service inbound document pipeline (Go) — moved from Plan A (G1)

### Overview
Real `multipart → virus-scan → MinIO` upload emitting `medfund.documents.uploaded`, plus a byte-stream read
path for the OCR pull (Phase 2). file-service stays stateless (no DB); handles are persisted by kyc-service in
Phase 1. *(Was Plan A Phase 2; moved here by grilling decision G1 — it's not needed to prove the entitlement
thesis and its only consumers are this plan's Phases 1–2.)*

### Changes Required
- **Scanner interface + stub + ClamAV** — `internal/scan/scanner.go` (new: `Scanner` iface, `NoopScanner`
  clean-by-default) + `internal/scan/clamav.go` (new: `ClamAVScanner` over `clamd` INSTREAM, built when
  `cfg.ClamAVAddr != ""`). Mirror the `PdfGenerator` stub-vs-real swap at `cmd/main.go:96-99`.
- **MinIO documents bucket + key helper** — `internal/storage/minio.go`: a tenant-scoped unguessable
  `DocumentKey(tenantID, subjectId, suffix, filename)` and `PutObject` reuse; new `MinIODocumentsBucket`.
- **Config** — `internal/config/config.go`: `MinIODocumentsBucket` (`MINIO_DOCUMENTS_BUCKET`, default
  `medfund-documents`), `ClamAVAddr` (`CLAMAV_ADDR`, default ""), parse `MaxUploadSizeMB` to int.
- **Upload handler** — `internal/handler/documents.go` (new): `POST /api/v1/files/documents` (multipart
  `file` + form `subjectType`, `subjectId`, `documentType`), size + mime allowlist (pdf/jpeg/png), synchronous
  scan gate (422 on threat), `PutObject`, emit event, return `{fileServiceRef, bucket, sizeBytes, contentType,
  sha256}`. Plus `GET /api/v1/files/documents/stream?ref=...` (tenant-scoped by key prefix) for the OCR pull.
  Mirror `handler.go:9-16` struct/`RegisterRoutes` + the `X-Tenant-ID` read. Add a `BodyLimit`.
  Form fields are `subjectType`/`subjectId` (party-neutral), not `memberId` — aligns with the kyc schema.
- **Event** — `internal/events/publisher.go`: `TopicDocumentUploaded = "medfund.documents.uploaded"`, a
  `DocumentUploaded` struct (camelCase tags + `Event` discriminator, carrying `subjectType`/`subjectId`),
  a third `kafka.Writer`, `PublishDocumentUploaded(...)`, closed in `Close()`. Keyed by `subjectId`.
- **main.go** — wire the handler with the **real** `MinIOStore` + selected `Scanner` + publisher.

### Success Criteria
#### Automated Verification:
- [ ] Go builds + vets: `cd services/go/file-service && go build ./... && go vet ./...`
- [ ] Go tests: `make test-go` — `documents_test.go`: 201 clean upload, 413 oversize, 422 seeded threat (fake Scanner), 400 missing fields (httptest + fake Scanner + fake MinIO, mirror `handler_test.go`)
- [ ] `events` round-trips `DocumentUploaded` with the `Event` discriminator
- [ ] Config test: `NoopScanner` selected when `CLAMAV_ADDR` empty
#### Manual Verification:
- [ ] `make infra`; `curl -F file=@sample.pdf -F subjectType=MEMBER -F subjectId=<uuid> -F documentType=NATIONAL_ID -H 'X-Tenant-ID: <uuid>' localhost:3003/api/v1/files/documents` → `fileServiceRef`
- [ ] Object visible in MinIO console under `medfund-documents/documents/<tenant>/<subject>/`; `medfund.documents.uploaded` lands on the topic

**Implementation Note**: pause for human confirmation of the manual upload before Phase 1 registers refs.

---

## Phase 1: Verification core — party-neutral state machine (kyc-service + user-service consumer)

### Overview
Open/submit/decide a verification for any subject type; resolve subject identity per type; gate activation;
emit audit + lifecycle events; back a pending queue.

### Changes Required

#### 1. Status-history table + member status widen (tenancy-owned)
**File**: `services/java/tenancy-service/.../db/migration/tenant/V279__kyc_status_history_and_member_pending.sql` (new)
```sql
-- Append-only KYC status trail (mirror member_status_history), and widen the member status
-- vocabulary to include pending_verification. New file — never edit V242
-- (feedback_never_edit_applied_migrations).
CREATE TABLE IF NOT EXISTS kyc_status_history (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type  VARCHAR(24) NOT NULL,
    subject_id    UUID NOT NULL,
    from_status   VARCHAR(24),
    to_status     VARCHAR(24) NOT NULL,
    effective_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id      UUID,
    actor_email   VARCHAR(255) NOT NULL,     -- feedback_audit_actor_email
    reason_code   VARCHAR(40),
    reason_note   TEXT
);
CREATE INDEX IF NOT EXISTS kyc_status_history_subject_idx ON kyc_status_history (subject_type, subject_id);

ALTER TABLE members DROP CONSTRAINT IF EXISTS members_status_vocab;
ALTER TABLE members ADD CONSTRAINT members_status_vocab CHECK (status IN
  ('enrolled','active','suspended','terminated','deactivated','lapsed','deceased','pending_verification'));
ALTER TABLE members DROP CONSTRAINT IF EXISTS members_scheduled_status_vocab;
ALTER TABLE members ADD CONSTRAINT members_scheduled_status_vocab CHECK (scheduled_status IS NULL OR scheduled_status IN
  ('enrolled','active','suspended','terminated','deactivated','lapsed','deceased','pending_verification'));
ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_reason;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_reason CHECK (reason_code IS NULL OR reason_code IN
  ('initial_backfill','backfill_from_termination_date','backfill_from_suspend_reason','admin_activate',
   'admin_suspend','admin_terminate','admin_deactivate','admin_lapse','arrears_lapse','arrears_clear',
   'scheduled_change','group_cascade','dependant_swap','enrolment','auto_termination','member_death','other',
   'kyc_submitted','kyc_verified','kyc_rejected'));
```

#### 2. Subject resolution (kyc-service)
**Files** (new): `subject/SubjectResolver.java` (interface `Mono<SubjectRef> resolve(String type, UUID id)`
returning a `{displayName, exists, insuranceLine?}` record used for audit `entityName`), `MemberSubjectResolver.java`
(reads the tenant-schema `members` via `DatabaseClient`), `ProviderSubjectResolver.java` (reads
`public.providers` **joined to `public.provider_tenants`** on the current tenant — Rule #2 membership check;
mirror the join discipline in `.claude/CLAUDE.md:136-143`). A `SubjectResolverRegistry` dispatches by `subject_type`.
**G8 compensating control:** the DB CHECK allows all four subject types, but only MEMBER + PROVIDER have
resolvers here. `SubjectResolverRegistry` **must reject a verification request for a subject_type with no
registered resolver (HTTP 400)** — so a `DEPENDANT`/`POLICYHOLDER` request can't create an orphan row the
decision flow can't action. The invariant the CHECK doesn't enforce lives at this service layer instead.

#### 3. Document register + verification service (kyc-service)
**Files** (new): `service/KycDocumentService.java` (register a file-service ref after upload — mirror
`DependantService.create` tenantId-via-`deferContextual` + best-effort audit+event), `service/KycVerificationService.java`.
`KycVerificationService` (`@Slf4j @Service @RequiredArgsConstructor`; deps `KycVerificationRepository`,
`KycDocumentRepository`, `StatusTransitionRecorder`, `SubjectResolverRegistry`, `AuditPublisher`,
`KycEventPublisher`, `MfaStepUpGuard`, `TenantRuleLoader`+`RuleEvaluationService`):
- `submit(subjectType, subjectId, actorId, actorEmail)` — upsert `kyc_verifications` to `pending_verification`,
  record `kyc_status_history` (reason `kyc_submitted`), audit, emit `medfund.kyc.verification-changed`.
- `evaluate(verification)` — build a `VerificationFact` (subjectType, insuranceLine, country, document types
  present, screening result) and run `ruleEvaluationService` after `tenantRuleLoader.ensureLoaded(tenantId)`;
  read back `requireManualReview`/`gateActivation`.
- `decide(subjectType, subjectId, approve, reason, jwt)` — **`mfaGuard.requireStepUp(jwt).then(...)`** (mirror
  `RegulatorySubmissionService`); on approve → verification `verified` + verified_by/email/at + history
  (`kyc_verified`); on reject → `rejected` + reason (`kyc_rejected`); audit (`entityType="KYC_VERIFICATION"`,
  `entityName` from the resolver); emit `medfund.kyc.verification-changed` with the new status + whether
  activation is gated.
- History-then-save ordering + idempotent same-status no-op, mirroring `MemberStatusTransitionService.applyTransition`.

#### 4. KYC event publisher (kyc-service)
**File**: `service/KycEventPublisher.java` (new, mirror `UserEventPublisher.publishEvent` best-effort sink):
topic `medfund.kyc.verification-changed`, flat `LinkedHashMap` + `"event":"KYC_VERIFICATION_CHANGED"`
discriminator, fields `subjectType, subjectId, oldStatus, newStatus, level, gateActivation, actorEmail`,
keyed by `subjectId`, `onErrorResume → Mono.empty()`.

#### 5. Controller (kyc-service)
**File**: `controller/VerificationController.java` (extend the Plan A skeleton) — `@RequiresEntitlement(ModuleKey.KYC)`
on the class, plus `@RequiresPermission` per method:
- `POST /api/v1/verifications/{subjectType}/{subjectId}/documents` (register a ref; body = `RegisterDocumentRequest`)
- `POST /api/v1/verifications/{subjectType}/{subjectId}/submit`
- `POST /api/v1/verifications/{subjectType}/{subjectId}/decision` (body `{approve, reason}`) — threads `Jwt`
  into the service for MFA step-up (mirror `RegulatorySubmissionController:68-87`)
- `GET /api/v1/verifications/{subjectType}/{subjectId}`
- `GET /api/v1/verifications/pending` (paged) — backs the review queue
Full Swagger incl. a `401` note for the MFA step-up on `/decision`.

#### 6. Activation consumer (user-service)
**File**: `services/java/user-service/.../consumer/KycVerificationChangedConsumer.java` (new; mirror an existing
reactor-kafka consumer — ack on `doOnSuccess`, full-cause logging, **not** `doOnTerminate`). On a `verified`
event with `gateActivation=true`: for MEMBER → `MemberStatusTransitionService.transition(id, "active",
"kyc_verified", ...)`; for PROVIDER → call the existing provider activation path. Idempotent (no-op if already active).

### Success Criteria

#### Automated Verification:
- [ ] Java compiles: `cd services/java && ./gradlew :kyc-service:build :user-service:build`
- [ ] Integration test: `make test-integration` — `KycVerificationIT` (kyc-service): submit → status
      `pending_verification` + history row; decide-approve **without** MFA → rejected (401); with MFA (amr+fresh
      auth_time in the JWT stub) → `verified`, history reason `kyc_verified`, `medfund.kyc.verification-changed`
      asserted via `@MockBean`/Kafka; PROVIDER subject resolves via `provider_tenants` and a non-member tenant is
      denied; **a verification request for an unresolved subject_type (e.g. DEPENDANT) is rejected with 400 (G8)**;
      V278/V279 apply on a fresh container
- [ ] `KycActivationConsumerIT` (user-service): a `verified`+`gateActivation` event drives member → `active`
- [ ] Swagger renders the five verification endpoints; `/decision` documents 401 MFA

#### Manual Verification:
- [ ] Open + submit a MEMBER verification via API → member/verification goes `pending_verification`; approve
      with a real MFA step-up token → member `active`; audit shows dual actor id+email
- [ ] A PROVIDER subject in a tenant that has the provider linked resolves; one that doesn't → 403/not-found

**Implementation Note**: pause for human confirmation of both subject types before wiring AI.

---

## Phase 2: OCR ID-document extraction (ai-service)

### Overview
An ID-document OCR variant extracting identity fields and writing an auditable prediction, pulled
synchronously by kyc-service. *(Mirrors the 2026-10-21 plan's OCR phase, re-pointed at the party-neutral subject.)*

### Changes Required
- **Anonymiser FIRST (Rule #4 gate)** — `app/core/anonymize.py`: add `national_id, passport_number,
  drivers_license, date_of_birth, full_name, first_name, last_name, address, document_number` to
  `_STRIPPED_KEYS`. Without this, ID fields persist verbatim in `ai_predictions_store`.
- **ID prompt path** — `app/services/ocr_service.py`: add `doc_type: str | None = None` to
  `extract_structured_data(...)`; when it names an ID doc, use an ID-extraction prompt (fields: full_name,
  national_id, passport_number, date_of_birth, sex, nationality, document_number, expiry_date,
  issuing_authority) + system prompt "You are an identity-document data extractor." Healthcare path unchanged
  when `doc_type` absent.
- **Endpoint** — `app/api/ocr.py`: add `doc_type: str | None = Form(None)` + `subject_type`/`subject_id` form
  fields; when present, call the ID path and `try_record_ai_prediction(... entity_type=<subject_type>,
  entity_id=<subject_id>, prediction_type="ocr_id_document", input_features=anonymize_features({...}),
  output=anonymize_features(result), confidence=...)`. Existing claim path unchanged.
- **kyc-service pull** — `KycVerificationService`: when an ID-type document is registered/submitted, stream
  bytes from file-service (`GET /files/documents/stream?ref=...`), POST to ai-service `/api/v1/ai/ocr/extract`
  (`doc_type`, subject, `X-Tenant-ID`), store `ocr_prediction_id` + `method='ocr'`. Sync read (Rule #6);
  fail-open.

### Success Criteria
#### Automated Verification:
- [ ] Python tests: `make test-python` — POST multipart `doc_type=national_id` + subject + tenant → a row with
      `prediction_type="ocr_id_document"`, `entity_type="MEMBER"`; assert `national_id`/`date_of_birth` **absent**
      from persisted `input_features`/`output` (anonymiser test); LLM stubbed
- [ ] ai-service `/docs` renders `doc_type`
- [ ] Java IT: OCR client stubbed (WireMock/`@MockBean`), `ocr_prediction_id` persisted
#### Manual Verification:
- [ ] Upload a real ID image → OCR fields surface on the verification record (via the Phase 4 UI or API)

---

## Phase 3: Sanctions/PEP in-house screening classifier (ai-service)

### Overview
An in-house LLM classifier scoring a subject's identity against watchlist context, writing an auditable
prediction; a `hit`/`review` forces manual review. *(Mirrors the 2026-10-21 plan's screening phase.)*

### Changes Required
- **Screening module** — `app/api/screening.py` (new): `POST /api/v1/ai/screening/sanctions-pep`
  (`ScreeningRequest{full_name, dob, nationality, national_id, subject_type, subject_id}`, `X-Tenant-ID`), calls
  `ScreeningService.screen(req)` (injected LLM via the `resolve_llm` pattern; classifier prompt returns
  `{decision: clear|review|hit, matched_lists[], rationale, confidence}`), writes
  `try_record_ai_prediction(... prediction_type="sanctions_pep_screening", input_features/output anonymised)`.
  A `ScreeningProvider` protocol leaves room for a vendor adapter behind the same `prediction_type`.
  Register in `app/main.py`.
- **kyc-service call** — after OCR, POST identity fields to `/screening/sanctions-pep`; store
  `screening_prediction_id` + `screening_result`; a `hit`/`review` sets the `VerificationFact` so rules force
  `requireManualReview` and the subject stays `pending_verification` regardless of OCR confidence.

### Success Criteria
#### Automated Verification:
- [ ] Python tests: `make test-python` — screening persists `prediction_type="sanctions_pep_screening"`;
      identity PII absent from persisted features; LLM-unavailable fallback path covered
- [ ] `/docs` renders the screening endpoint
- [ ] Java IT: screening stubbed; a `hit` keeps the subject `pending_verification`
#### Manual Verification:
- [ ] A deliberately "flagged" name routes to `review` and appears in the predictions review queue

---

## Phase 4: Angular entitlement-aware KYC surface (Angular)

### Overview
Staff capture + review for members and providers, review queues, and the platform-admin entitlement toggle —
gated by permission and the KYC entitlement.

### Changes Required
- **File-service + KYC clients** — `core/services/file.service.ts` (new; `uploadDocument(subjectType,
  subjectId, documentType, file)` via `ApiService.postMultipart('/files/documents', form)`) and
  `core/services/kyc.service.ts` (new; `getVerification`, `registerDocument`, `submit`, `decide`,
  `getPending` against `/api/v1/verifications/*`). Add `/files/*` + `/api/v1/verifications/*` to the gateway
  permission/route map if not already.
- **Entitlement-aware UI gating** — a `core/services/entitlement.service.ts` (new; reads the tenant's module
  entitlements from a new `/api/v1/tenants/{id}/module-entitlements` read endpoint or the platform list for
  super admins) + an `*ifEntitled="'KYC'"` structural directive (mirror `feature-gate.directive.ts`). KYC nav/
  routes are hidden when the tenant lacks the entitlement (defence in depth with the gateway gate).
- **Verification panel + document capture** — a Verification `.form-card`/tab on member detail and on provider
  detail (mirror the `member-balance-detail` tabbed pattern): status chip
  (`badge-pending_verification`/`badge-verified`/`badge-rejected`), verification level, OCR fields (read-only),
  screening result, a document uploader (parameterized `DocumentUploaderComponent` reusing `LogoUploaderComponent`'s
  drag-drop; document type via `app-select`), and Approve/Reject actions opening a decision modal that performs
  MFA step-up when prompted (shared MFA flow).
- **Pending-verification queues** — `pages/tenant/members/pending/` (route already specced in
  `portals.md:178`) and a provider equivalent, each an `<app-data-table>` over `GET /verifications/pending`
  filtered by subject type, `permissionGuard(['kyc:view'])`.
- **Platform-admin entitlement toggle** — a screen under `/platform/...` listing tenants × modules with a
  toggle calling `PUT /api/v1/platform/module-entitlements/{tenantId}/{moduleKey}` (super-admin only).
- **Permissions** — add the `kyc` domain via the four-edit contract (`permissions.yaml`, `Permissions.java`
  + `ALL`, `PermissionCatalogue.java`, Angular `permissions.ts`): `kyc:view`, `kyc:verify`, `kyc:configure`.

### Success Criteria
#### Automated Verification:
- [ ] Angular unit tests: `make test-angular`
- [ ] Playwright: `make test-e2e` — `kyc-member.spec.ts` + `kyc-provider.spec.ts` (mirror the
      `signInAs`+`apiMocks` harness): stub the verification + pending + files endpoints; upload a fixture,
      submit, open the queue, approve → chip flips to verified; a read-only user sees no Approve button; a tenant
      without the KYC entitlement does not see the KYC nav
- [ ] `verify` on `/tenant/members/:id`, `/tenant/members/pending`, the provider detail, and the platform
      entitlement screen: no console errors, panels/queues render, upload + decision interactions fire
#### Manual Verification:
- [ ] End-to-end (staff): upload a member ID → submit → OCR+screening populate → approve with real MFA → member
      active; repeat for a provider; audit + security events recorded
- [ ] Toggling the tenant's KYC entitlement off in the platform screen removes KYC access (UI gone + gateway 403)

**Implementation Note**: this completes staff KYC end-to-end for both subject types. Pause for sign-off before Plan C.

---

## Testing Strategy
- **Unit**: kyc-service state transitions (submit/approve/reject, idempotent no-op, MFA-required); subject
  resolvers (member tenant-schema, provider membership-join); OCR ID path + anonymiser; screening classifier (stubbed LLM).
- **Integration (Testcontainers)**: `KycVerificationIT`, `KycActivationConsumerIT` — tenant family V278/V279,
  `@MockBean` publishers, audit-shape assertions; OCR/screening stubbed at the HTTP-client boundary.
- **E2E (Playwright)**: `kyc-member.spec.ts`, `kyc-provider.spec.ts` — capture, submit, review, approve/reject;
  entitlement-off hides KYC; read-only hides actions.

## Performance Considerations
- OCR/screening are synchronous reads on the verification action (not the hot enroll path) and fail-open.
- `kyc_verifications (subject_type, subject_id)` unique + `verification_status` indexed for the per-subject
  lookup and the pending queue; `kyc_status_history (subject_type, subject_id)` indexed.
- Provider resolution does a `providers ⋈ provider_tenants` read — index-covered by the composite PK.
- Pending-queue counts are server-aggregated (report-envelope convention), never summed in Angular.

## Migration Notes
- Tenant `V278` (Plan A) → `V279` (this plan) in the tenant family; both from tenancy-service. The member
  status widen is a new DROP+re-ADD file, never an edit of `V242`.
- `pending_verification` is forward-only (new/affected subjects); existing members unaffected; no backfill.
- Kafka: one additive topic (`medfund.kyc.verification-changed`); no recompaction; no rules recompile beyond
  authoring VERIFICATION rules (Plan A registered the category); no Keycloak change.

## Rollout & Rollback
- **Deploy order:** tenancy-service (V279) → kyc-service (producer of the kyc event) → user-service (consumer)
  → ai-service (OCR/screening) → Angular.
- **Rollback:** reverting Angular hides the UI; reverting kyc-service leaves tables intact (handles only, no
  new cleartext PII); the activation consumer is additive (reverting it stops auto-activation — members simply
  stay `pending_verification` for manual action). OCR/screening are fail-open so reverting ai-service degrades
  to manual review, never a block.

## References
- Program overview: `thoughts/shared/plans/2026-10-23-kyc-horizontal-program-overview.md`
- Plan A (prereq): `thoughts/shared/plans/2026-10-23-kyc-plan-a-horizontal-foundation.md`
- Research: `thoughts/shared/research/2026-10-22-kyc-pilot-horizontal.md`,
  `thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md`
- Mirror sources: `user-service/.../ProviderService.java` (onboard/verifyAhfoz), `user-service/.../status/MemberStatusTransitionService.java`
  + `shared/.../lifecycle/StatusTransitionRecorder.java`, `finance-service/.../regulatory/{service,controller}/RegulatorySubmission*.java`
  (MFA threading), `tenancy-service/.../tenant/V272__siu_evidence.sql`, `user-service/.../entity|repository|service|controller/Dependant*.java`,
  `shared/.../audit/{AuditPublisher,AuditEvent,AuditActor}.java`, `user-service/.../service/UserEventPublisher.java`,
  `ai-service/app/api/{ocr,predictions}.py` + `app/services/ocr_service.py` + `app/core/anonymize.py`,
  `clients/angular/.../finance/creditors/member-balance-detail.component.*`, `clients/angular/e2e/` harness
