---
date: 2026-10-21
git_commit: e5051ef3a1cccc79fcebb62668ea589f2dcb98c0
branch: main
ticket: none — grounded in research only
research:
  - thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md
steer: "none at invocation — scope was settled interactively (see Scope Decisions below)"
services_touched: [file-service, user-service, tenancy-service, ai-service, angular, flutter]
status: draft
---

# Member Layers & KYC Integration — Implementation Plan

## Overview

Build the full set of member "layers" the research identified, with **KYC (identity
verification) front-loaded** as the headline capability. A member today is a thin
identity/lifecycle/billing record with nothing attached but dependants, policies, status
history, and billing overrides. This plan adds seven new tenant-schema `member_*` layers,
a real inbound document-upload pipeline, an OCR + in-house-AI identity-screening path, staff
capture/review UI in Angular, and member self-upload in Flutter.

The build is **assemble + add a verification state machine**, not invent-from-scratch: it
mirrors the shipped `Dependant` CRUD quartet, the `siu_evidence` document-handle pattern, the
`ai_predictions_store` auditable-AI spine, and the `predictions.py` review queue.

## Scope Decisions (resolved research open questions)

The research closed with six open questions. All were settled interactively before this plan
was written — recorded verbatim as the binding scope:

1. **Who captures KYC?** → **Both surfaces.** Staff-operated capture/review in Angular *and*
   member self-upload in Flutter.
2. **Does KYC gate activation?** → **Yes.** Add `pending_verification` to the member status
   vocabulary and gate activation on it.
3. **File-service prerequisite?** → **In scope, Phase 1.** MinIO already works for the outbound
   PDF pipeline; this plan wires a real **inbound** path (multipart upload → scan → store).
4. **Sanctions/PEP mechanism?** → **In-house AI classifier** (existing Claude/Gemini LLM against
   provided watchlist context). No external vendor, no credentials/contract.
5. **Encryption / KMS?** → **Ship with the documented cleartext gap** that already applies to
   bank details (`.claude/payments.md:610`), plus MinIO server-side encryption. KMS wiring is a
   known repo-wide gap flagged here, not blocked on.
6. **Per-line/per-tenant KYC policy?** → **Static per-tenant config** now; per-line/jurisdiction
   variation migrates to the rules-engine later (see *What We're NOT Doing*).

Two further design forks were settled the same way:

- **Upload mechanism** → **multipart through file-service** (bytes transit the service so virus
  scanning is a synchronous gate), not presigned-PUT direct to MinIO.
- **Phase ordering** → **KYC first (1–7), Flutter mid (8–9), the other five layers last (10–14).**

## Current State Analysis

What a member is today and what's absent is mapped in the research doc
(`thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md`). The load-bearing
facts this plan builds on, each verified against HEAD:

- **Java child-entity template** — `Dependant` quartet:
  `services/java/user-service/.../entity/Dependant.java` (R2DBC `@Table`, `@Id` no generator,
  manual getters/setters, **no `tenant_id` column**),
  `.../repository/DependantRepository.java` (`R2dbcRepository` + explicit `@Query` with
  `:memberId` binds), `.../service/DependantService.java`
  (`@Slf4j @Service @RequiredArgsConstructor`, `r2dbcTemplate.insert(...)` for PG id-gen,
  `actorId`/`actorEmail` passed from controller, tenant id read via
  `Mono.deferContextual(ctx -> TenantContext.get(ctx))`, best-effort audit+Kafka),
  `.../controller/DependantController.java` (`@RestController`, Swagger `@Operation`/`@ApiResponses`,
  `@AuthenticationPrincipal Jwt` → `AuditActor.id/email`, DTO records with static `from(...)`).
- **Tenant isolation is physical** — `TenantAwareConnectionFactory` does `SET search_path` +
  `SET ROLE` per connection; new `member_*` tables live in the **tenant schema** and carry no
  `tenant_id` column. Next free tenant migration: **V278** (highest is
  `V277__widen_report_job_retention_class.sql`).
- **Member status vocabulary** — `V242__member_death_status_and_reason.sql` holds the current
  CHECKs: constraint `members_status_vocab` =
  `('enrolled','active','suspended','terminated','deactivated','lapsed','deceased')`,
  `members_scheduled_status_vocab` (same set, nullable), and
  `chk_member_status_history_reason` (reason-code allowlist). **No `pending_verification`.**
  Canonical status path is `MemberStatusTransitionService` + `StatusTransitionRecorder`
  (writes `member_status_history` atomically) with reason codes in `defaultReasonCodeFor(...)`.
- **Audit** — `services/java/shared/.../audit/AuditPublisher.publish(AuditEvent)` → topic
  `medfund.audit.events`; `AuditEvent.create(tenantId, entityType, entityId, entityName, action,
  actorId, actorEmail, oldValue, newValue, changedFields, correlationId)`. `entityType`/`entityName`
  are **inline string literals** per call site (no central registry). `AuditActor.id(jwt)`/`.email(jwt)`.
- **Lifecycle Kafka** — `UserEventPublisher` publishes flat `LinkedHashMap` payloads with an
  `"event"` discriminator to `medfund.users.*`, keyed by `memberId`, best-effort
  (`.onErrorResume(e -> Mono.empty())`).
- **Document-handle pattern** — `V272__siu_evidence.sql`: `file_service_ref VARCHAR(255)`,
  typed `evidence_type` CHECK, `uploaded_by UUID` + `uploaded_by_email VARCHAR(255) NOT NULL`
  (the `feedback_audit_actor_email` invariant baked into the schema). File bytes live in
  file-service; the tenant table stores only the handle.
- **file-service** — `internal/storage/s3.go` `Storage` interface
  (`GenerateUploadURL`/`GenerateDownloadURL`/`Delete`) backed by `MockStorage`;
  `internal/storage/minio.go` `MinIOStore` (`PutObject`/`GetObject`/`PresignedGet`/`RemoveObject`/
  `Bucket`) is a **separate concrete type** that does NOT satisfy `Storage` and has **no
  presigned-PUT**. `cmd/main.go:42-44` wires the mock into the handler; MinIO drives only the
  invoice/receipt PDF pipelines. **No virus scan, no auth middleware (trusts gateway), no request
  body ingest anywhere.** Config (`internal/config/config.go`) has `MinIO*` fields and an unused
  `MaxUploadSizeMB`. Scanner/PDF-generator both already use a stub-vs-real swap pattern
  (`MockStorage`, `StubPdfGenerator` vs `wkhtmltopdf`).
- **ai-service** — add a module = new `app/api/<name>.py` with `router`, imported + `include_router`'d
  in `app/main.py` (lines 11-21 / 151-161). OCR endpoint `app/api/ocr.py` (`POST /api/v1/ai/ocr/extract`,
  `UploadFile` + `X-Tenant-ID`, writes `try_record_ai_prediction(... entity_type="document",
  prediction_type="ocr")`); `ocr_service.py` prompt is hardcoded to healthcare claims.
  `app/services/prediction_repository.py` `try_record_ai_prediction(...)` is fail-open (session
  `None` → no-op). `app/core/anonymize.py` `_STRIPPED_KEYS` strips claim PII **but not identity
  PII** (`national_id`, `passport_number`, `date_of_birth`, `full_name`, `address`). External-call
  precedent: `app/services/member_context_client.py` (`httpx.AsyncClient`, timeout, fail-open).
  Review reuse: `app/api/predictions.py` `list` / `review-queue` / `PUT /{id}/decision` already
  filter by `prediction_type` and carry `X-Actor-Email`.
- **Angular** — member area under `pages/tenant/members/` (`members.routes.ts`:
  list / `add` / `:id` detail; `:id/history`, `:id/waivers` are ComingSoon stubs). Member detail
  is a **vertical `.form-card` stack** (`detail/member-detail.component.*`), reads `nationalId`/
  `address` via `(m as any)` casts because `MembersService.Member` omits them. Tabbing available
  via the `member-balance-detail` `activeTab`/`setTab` + `@if` panel pattern. **No file-service
  Angular client** (claims stage metadata only; SIU pastes a URL handle). `ApiService.postMultipart`
  exists. Drag-drop `LogoUploaderComponent` (hardcoded logo mimes). ID fields use
  `app-entity-picker` (never raw `<input>`). `badge-verified` / `badge-pending_verification`
  classes already exist. Stats are server-aggregated via the `ReportResponse` envelope — never
  client-side. Playwright harness: `signInAs` + `apiMocks` in `clients/angular/e2e/`.
- **Flutter** is a **scaffold** (`clients/flutter`, package `medfund_mobile`): five member screens
  render 100% hardcoded mock data; `ApiClient` (dio) + `AuthService` + `flutter_appauth` (Keycloak
  PKCE) + Riverpod + `mobile_scanner` are all dependencies with **zero wiring/usage**. No file
  picker, no Documents screen, no self-registration, no `member_id` resolution. Routing is one
  `GoRouter` in `lib/router.dart`.

## Desired End State

- A member can have **typed documents** uploaded (staff in Angular, self in Flutter), stored in
  real MinIO with size/mime enforcement and a virus-scan gate, their handle persisted in a
  tenant-schema `member_documents` table.
- A member carries a **`member_verification`** record (status / level / method / OCR extract /
  screening result). Activation is gated on `pending_verification` when the tenant requires it;
  staff work a `/tenant/members/pending` review queue; decisions are MFA-gated and audited.
- **OCR** extracts ID-document fields and **an in-house AI classifier** screens identity against
  sanctions/PEP watchlist context; both write `ai_predictions_store` (model version, anonymised
  features, confidence, output) per Critical Rule #3 and are reviewable in the predictions queue.
- Five further member layers exist end-to-end: **contacts + comms prefs, consent ledger,
  beneficiary nomination, next-of-kin, risk profile.**
- Every mutation emits an audit event with friendly `entityName` + dual actor id/email; every
  verification state change emits `medfund.users.member-verification-changed`; every new endpoint
  renders in Swagger; every query is tenant-scoped.

### Key Discoveries

- Reuse, don't rebuild: `siu_evidence` (document handle), `Dependant` quartet (CRUD), `AuditEvent`
  (audit), `MemberStatusTransitionService` (status machine), `ai_predictions_store` +
  `predictions.py` (auditable AI + review), `app-entity-picker` (ID inputs),
  `badge-verified`/`badge-pending_verification` (status chips).
- `member_documents`/`member_verification` are **tenant-schema** tables (members aren't
  platform-shared) → auto-scoped by `TenantAwareConnectionFactory`, **no `public._tenants` join**
  (Critical Rule #2).
- The one net-new PII hazard: `anonymize._STRIPPED_KEYS` must gain identity keys **before** any
  ID-document field reaches `ai_predictions_store` (Critical Rule #4).

## What We're NOT Doing

- **No KMS wiring.** KYC PII inherits the documented cleartext gap that already applies to bank
  details (`.claude/payments.md:610`); MinIO server-side encryption is enabled as a partial
  measure. Wiring per-tenant AWS KMS is explicitly out of scope (flagged as a Rule #4 exception).
- **No external sanctions/PEP vendor.** Screening is an in-house LLM classifier. A real vendor
  adapter is left as a future swap behind the same `prediction_type`.
- **No rules-engine KYC policy** in this plan. Verification requirements are static per-tenant
  config now; migrating per-line/jurisdiction policy to rules-engine (`RuleDefinition`/Drools) is
  a follow-on (Critical Rule #5 path).
- **No biometrics / liveness / face-match** (the proposal's MobileFaceNet target is out of scope).
- **No Keycloak identity-proofing** change; `MfaStepUpGuard` gates staff review (authentication
  assurance), not member identity proofing.
- **No GDPR erasure execution** — the consent ledger (Phase 11) records consent and data-subject
  requests; the actual erasure/export runner stays with the existing `/admin/data-export`.

## Implementation Approach

KYC is delivered end-to-end first so the headline capability lands early and is verifiable as a
whole: documents (1–3) → verification core + activation gate (4) → OCR (5) → screening (6) →
staff review UI (7). Flutter self-upload (8–9) follows once the backend contracts are stable.
The remaining five layers (10–14) are structurally identical repeats of the Phase 2 quartet and
come last.

**Cross-service contracts, fixed up front:**

- **New Kafka topics** (additive; producer before consumer):
  - `medfund.documents.uploaded` (producer: file-service; consumers: none required for MVP — OCR
    is pulled synchronously, see below).
  - `medfund.users.member-verification-changed` (producer: user-service; consumers: optional —
    live dashboard / notifications later).
- **Sync REST is used only for reads/compute** (Rule #6 allows this): user-service → file-service
  (fetch document bytes by ref) and user-service → ai-service (`/ocr/extract`, `/screening/...`).
  OCR and screening are compute-reads, not side effects, so they are synchronous and each writes
  its own `ai_predictions_store` row (Rule #3).
- **Backwards-compatibility:** all audit/lifecycle event payloads add fields, never remove.
  The member status CHECK is widened via DROP+re-ADD in a **new** migration (never edit an applied
  one — `feedback_never_edit_applied_migrations`).

**Java entity convention note:** per `.claude/CLAUDE.md` the plan writes new R2DBC entities with
Lombok `@Getter @Setter` (never `@Data`). Existing `Member`/`Dependant` use manual
getters/setters; new entities follow the documented standard. If the implementer prefers to match
the shipped manual style for consistency, either is acceptable — Lombok is on the classpath.

---

## Phase 1: File-service inbound document pipeline (Go)

### Overview
Turn the stubbed upload path into a real multipart → scan → MinIO pipeline for member documents,
emitting `medfund.documents.uploaded`. file-service stays stateless (no DB); the document handle
is persisted by user-service in Phase 2.

### Changes Required

#### 1. Virus-scan interface + dev stub + ClamAV impl
**File**: `services/go/file-service/internal/scan/scanner.go` (new)
```go
package scan

import "context"

// Result is the outcome of scanning one object's bytes.
type Result struct {
	Clean  bool
	Signature string // non-empty when a threat matched
}

// Scanner gates uploaded bytes before they reach storage. Mirrors the
// MockStorage / StubPdfGenerator stub-vs-real swap pattern used elsewhere
// in file-service.
type Scanner interface {
	Scan(ctx context.Context, data []byte) (Result, error)
}

// NoopScanner always reports clean — the dev/default impl, logged so an
// operator can see scanning is disabled. Selected when CLAMAV_ADDR is empty.
type NoopScanner struct{}

func (NoopScanner) Scan(context.Context, []byte) (Result, error) {
	return Result{Clean: true}, nil
}
```
**File**: `services/go/file-service/internal/scan/clamav.go` (new) — `ClamAVScanner` dialing
`clamd` over TCP (`INSTREAM`), constructed when `cfg.ClamAVAddr != ""`. Returns `Clean:false` +
signature on an `FOUND` reply.

#### 2. Extend MinIOStore with an upload (documents live in their own bucket)
**File**: `services/go/file-service/internal/storage/minio.go`
**Changes**: add a documents bucket + reuse `PutObject`. The existing `PutObject` already suffices
for storing clean bytes; add a small helper to build a tenant-scoped, unguessable key:
```go
// DocumentKey builds a tenant-scoped object key for a member document.
// Random suffix (caller-supplied, since Math.random is unavailable in the
// shared helper) prevents guessing other members' refs.
func DocumentKey(tenantID, memberID, suffix, filename string) string {
	return fmt.Sprintf("documents/%s/%s/%s-%s", tenantID, memberID, suffix, filename)
}
```

#### 3. Config — documents bucket, size limit, ClamAV
**File**: `services/go/file-service/internal/config/config.go`
**Changes**: add `MinIODocumentsBucket` (`MINIO_DOCUMENTS_BUCKET`, default `medfund-documents`),
`ClamAVAddr` (`CLAMAV_ADDR`, default `""`), and parse `MaxUploadSizeMB` to an int (today it's an
unused string). MinIO server-side encryption header is set on `PutObject` for the documents bucket.

#### 4. Documents upload handler (bytes transit → scan → store → emit)
**File**: `services/go/file-service/internal/handler/documents.go` (new)
```go
// POST /api/v1/files/documents  (multipart: file; form: memberId, documentType)
// Bytes transit the service so the virus scan is a synchronous gate.
func (h *DocumentHandler) Upload(c *fiber.Ctx) error {
	tenantID := c.Get("X-Tenant-ID")
	memberID := c.FormValue("memberId")
	docType := c.FormValue("documentType")
	if tenantID == "" || memberID == "" || docType == "" {
		return fiber.NewError(fiber.StatusBadRequest, "tenantId, memberId, documentType required")
	}
	fh, err := c.FormFile("file")
	if err != nil { return fiber.NewError(fiber.StatusBadRequest, "file required") }
	if fh.Size > h.maxBytes {
		return fiber.NewError(fiber.StatusRequestEntityTooLarge, "file exceeds limit")
	}
	data, contentType, err := readMultipart(fh) // + mime allowlist (pdf/jpeg/png)
	if err != nil { return err }

	res, err := h.scanner.Scan(c.UserContext(), data)
	if err != nil { return fiber.NewError(fiber.StatusBadGateway, "scan unavailable") }
	if !res.Clean {
		return fiber.NewError(fiber.StatusUnprocessableEntity, "file failed virus scan: "+res.Signature)
	}

	sum := sha256.Sum256(data)
	key := storage.DocumentKey(tenantID, memberID, hex.EncodeToString(sum[:8]), fh.Filename)
	if _, err := h.minio.PutObject(c.UserContext(), key, data, contentType); err != nil {
		return fiber.NewError(fiber.StatusInternalServerError, "store failed")
	}
	h.publisher.PublishDocumentUploaded(c.UserContext(), events.DocumentUploaded{
		Event: "DOCUMENT_UPLOADED", TenantID: tenantID, MemberID: memberID,
		DocumentType: docType, FileServiceRef: key, Bucket: h.bucket,
		ContentType: contentType, SizeBytes: len(data), Sha256: hex.EncodeToString(sum[:]),
	})
	return c.Status(fiber.StatusCreated).JSON(fiber.Map{
		"fileServiceRef": key, "bucket": h.bucket, "sizeBytes": len(data),
		"contentType": contentType, "sha256": hex.EncodeToString(sum[:]),
	})
}

// GET /api/v1/files/documents/stream?ref=...  — streams bytes back for the
// OCR pull path (user-service → file-service). Tenant-scoped by key prefix.
```
Also register `c.App().Use(... BodyLimit)` sized to `MaxUploadSizeMB`, and wire the new handler in
`cmd/main.go` with the **real** `MinIOStore` + selected `Scanner` (not the mock).

#### 5. `medfund.documents.uploaded` event
**File**: `services/go/file-service/internal/events/publisher.go`
**Changes**: add `TopicDocumentUploaded = "medfund.documents.uploaded"`, a `DocumentUploaded`
struct (camelCase JSON tags + `Event` discriminator), a third `kafka.Writer` in `NewPublisher`,
`PublishDocumentUploaded(...)`, and close it in `Close()`. Keyed by `MemberID`.

### Success Criteria

#### Automated Verification:
- [ ] Go builds + vets: `cd services/go/file-service && go build ./... && go vet ./...`
- [ ] Go tests pass: `make test-go` — new `documents_test.go` covers: 201 on clean upload,
      413 over size, 422 on a seeded "threat" via a fake `Scanner`, 400 on missing form fields
      (httptest + a fake Scanner + a fake MinIO, following `handler_test.go` setup)
- [ ] `events` round-trip test: `DocumentUploaded` marshals/parses with the `Event` discriminator
- [ ] `NoopScanner` selected when `CLAMAV_ADDR` empty (config test asserts the wiring)

#### Manual Verification:
- [ ] `make infra` up; `curl -F file=@sample.pdf -F memberId=... -F documentType=NATIONAL_ID
      -H 'X-Tenant-ID: ...' localhost:3003/api/v1/files/documents` returns a `fileServiceRef`
- [ ] The object is visible in MinIO console under `medfund-documents/documents/<tenant>/<member>/`
- [ ] `medfund.documents.uploaded` lands on the topic (console consumer)

**Implementation Note**: pause for human confirmation of the manual upload before Phase 2.

---

## Phase 2: `member_documents` layer (user-service, Java)

### Overview
Persist the document handle in a tenant-schema table and expose typed CRUD, mirroring the
`Dependant` quartet and the `siu_evidence` schema.

### Changes Required

#### 1. Tenant migration — `member_documents`
**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V278__member_documents.sql` (new)
```sql
-- Member document store — typed handles into file-service (bytes live there,
-- per the siu_evidence pattern). Tenant-schema table; no tenant_id column
-- (TenantAwareConnectionFactory scopes by search_path). Foundation for KYC.
CREATE TABLE IF NOT EXISTS member_documents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id         UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    document_type     VARCHAR(32) NOT NULL,
    file_service_ref  VARCHAR(255) NOT NULL,     -- bucket/key handle
    bucket            VARCHAR(128) NOT NULL,
    content_type      VARCHAR(128) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    sha256            VARCHAR(64) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'uploaded',
    uploaded_by       UUID NOT NULL,
    uploaded_by_email VARCHAR(255) NOT NULL,      -- feedback_audit_actor_email
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT member_documents_type_chk CHECK (document_type IN (
        'NATIONAL_ID','PASSPORT','DRIVERS_LICENSE','PROOF_OF_ADDRESS','SELFIE',
        'BIRTH_CERTIFICATE','OTHER')),
    CONSTRAINT member_documents_status_chk CHECK (status IN (
        'uploaded','verified','rejected','expired'))
);
CREATE INDEX IF NOT EXISTS member_documents_member_id_idx ON member_documents (member_id);
```

#### 2. Entity / repository / service / controller / DTOs
- **Entity** `.../entity/MemberDocument.java` — `@Table("member_documents")`, `@Getter @Setter`,
  `@Id UUID id`, `@CreatedDate`/`@LastModifiedDate`, fields mirroring the columns.
- **Repository** `.../repository/MemberDocumentRepository.java` —
  `extends R2dbcRepository<MemberDocument, UUID>`; `Flux<MemberDocument> findByMemberId(UUID)`,
  `Mono<MemberDocument> findByMemberIdAndId(UUID memberId, UUID id)`.
- **Service** `.../service/MemberDocumentService.java` — `@Slf4j @Service @RequiredArgsConstructor`;
  `record(...)` persists a row **after** the file-service upload returns a ref (the Angular/Flutter
  client uploads to file-service, then POSTs the ref here), stamps `uploadedBy`/`uploadedByEmail`,
  `r2dbcTemplate.insert(...)`, then best-effort `AuditPublisher.publish(AuditEvent.create(tenantId,
  "MemberDocument", id, memberLabel, "CREATE", actorId, actorEmail, null, newValue, fields, corr))`
  and `UserEventPublisher` member-changed. `delete(...)` emits a `DELETE` audit.
- **Controller** `.../controller/MemberDocumentController.java` —
  `@RestController @RequestMapping("/api/v1/members/{memberId}/documents")`, full Swagger,
  `@AuthenticationPrincipal Jwt` → `AuditActor`. Endpoints: `GET` (list), `POST` (register a ref),
  `DELETE /{id}`. Request DTO `RegisterDocumentRequest(documentType, fileServiceRef, bucket,
  contentType, sizeBytes, sha256)` with validation; response `MemberDocumentResponse` + `from(...)`.

### Success Criteria

#### Automated Verification:
- [ ] Java compiles: `cd services/java && ./gradlew :user-service:build`
- [ ] Unit tests: `make test-java`
- [ ] Integration test (Testcontainers): `make test-integration` — new
      `MemberDocumentIT` extends `AbstractPostgresIntegrationTest`, migrates the tenant family
      including V278, `@MockBean` `AuditPublisher`/`UserEventPublisher`, asserts register→list→delete
      and that an audit event is published with `entityType="MemberDocument"` + non-blank actorEmail
- [ ] Swagger renders the three endpoints at `http://localhost:8082/swagger-ui`

#### Manual Verification:
- [ ] Upload a file via Phase 1, POST its ref here, confirm it lists for the member

---

## Phase 3: Angular staff document capture/list (Angular)

### Overview
First file-service Angular client + a documents block on member detail: upload (to file-service),
then register the returned ref (user-service).

### Changes Required

#### 1. File-service client
**File**: `clients/angular/src/app/core/services/file.service.ts` (new) — `uploadDocument(memberId,
documentType, file): Observable<UploadResult>` using `ApiService.postMultipart('/files/documents',
form)`. (Base URL is the gateway; add the `/files/*` route to the gateway permission map.)

#### 2. Members service + model
**File**: `clients/angular/src/app/core/services/members.service.ts`
**Changes**: add `nationalId?`/`address?` to the `Member` interface (removing the `(m as any)`
casts in member-detail), and document sub-resource methods on the `/members/{id}/documents`
convention: `getDocuments(memberId)`, `registerDocument(memberId, body)`, `deleteDocument(memberId, id)`.

#### 3. Documents `.form-card` on member detail
**File**: `clients/angular/src/app/pages/tenant/members/detail/member-detail.component.*`
**Changes**: add a Documents `.form-card` block (below Dependants): a parameterized uploader
(fork/extend `LogoUploaderComponent` to accept `accept`/`maxBytes` inputs, or a thin
`DocumentUploaderComponent` reusing its drag-drop markup) wired to `fileService.uploadDocument(...)`
→ on success `members.registerDocument(...)` → refresh the list. Document type chosen via
`app-select` (not raw input). Rows show type, size, status chip, download link.

### Success Criteria

#### Automated Verification:
- [ ] Angular unit tests: `make test-angular`
- [ ] Playwright: `make test-e2e` — new `member-documents.spec.ts` (mirror
      `member-dependant-lifecycle.spec.ts`): `signInAs({permissions:['members:view','members:update']})`,
      stub `POST /files/documents` + `POST/GET /members/:id/documents`, upload a fixture file,
      assert the row renders
- [ ] `verify` on `/tenant/members/:id`: no console errors, uploader renders, upload interaction fires

#### Manual Verification:
- [ ] Real drag-drop upload of a PDF/image shows the row with correct size + type

**Implementation Note**: pause for human confirmation of the UI before Phase 4.

---

## Phase 4: KYC verification core + activation gate (user-service, Java)

### Overview
The `member_verification` record, the status machine, the `pending_verification` gate, and the
pending-review queue endpoint — the heart of KYC.

### Changes Required

#### 1. Migration — widen status vocab + reason codes (new file, never edit V242)
**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V279__member_verification.sql` (new)
```sql
-- Add pending_verification to the member status vocabulary and the verification
-- reason codes. DROP + re-ADD (idempotent), new file — editing V242 would violate
-- feedback_never_edit_applied_migrations.
ALTER TABLE members DROP CONSTRAINT IF EXISTS members_status_vocab;
ALTER TABLE members ADD CONSTRAINT members_status_vocab CHECK (
    status IN ('enrolled','active','suspended','terminated','deactivated',
               'lapsed','deceased','pending_verification'));
ALTER TABLE members DROP CONSTRAINT IF EXISTS members_scheduled_status_vocab;
ALTER TABLE members ADD CONSTRAINT members_scheduled_status_vocab CHECK (
    scheduled_status IS NULL OR scheduled_status IN (
        'enrolled','active','suspended','terminated','deactivated',
        'lapsed','deceased','pending_verification'));
ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_reason;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_reason CHECK (
    reason_code IS NULL OR reason_code IN (
        'initial_backfill','backfill_from_termination_date','backfill_from_suspend_reason',
        'admin_activate','admin_suspend','admin_terminate','admin_deactivate','admin_lapse',
        'arrears_lapse','arrears_clear','scheduled_change','group_cascade','dependant_swap',
        'enrolment','auto_termination','member_death','other',
        'kyc_submitted','kyc_verified','kyc_rejected'));

-- One verification record per member (1:1; history lives in member_status_history + audit).
CREATE TABLE IF NOT EXISTS member_verification (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id          UUID NOT NULL UNIQUE REFERENCES members(id) ON DELETE CASCADE,
    verification_status VARCHAR(24) NOT NULL DEFAULT 'unverified',
    verification_level  SMALLINT NOT NULL DEFAULT 0,          -- 0 none / 1 basic ID / 2 full DD
    method             VARCHAR(16),                            -- manual | ocr | ai
    ocr_prediction_id  UUID,                                   -- ai_predictions_store ref (OCR)
    screening_prediction_id UUID,                              -- ai_predictions_store ref (sanctions/PEP)
    screening_result   VARCHAR(16),                            -- clear | review | hit
    verified_at        TIMESTAMPTZ,
    verified_by        UUID,
    verified_by_email  VARCHAR(255),
    rejected_reason    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by         UUID,
    CONSTRAINT member_verification_status_chk CHECK (verification_status IN (
        'unverified','pending_verification','verified','rejected','expired'))
);
CREATE INDEX IF NOT EXISTS member_verification_status_idx ON member_verification (verification_status);
```

#### 2. Entity / repository / service
- **Entity** `.../entity/MemberVerification.java` (`@Getter @Setter`, as above).
- **Repository** — `findByMemberId`, `Flux<MemberVerification> findByVerificationStatus(String)`
  for the pending queue.
- **Service** `.../service/MemberVerificationService.java` —
  `@Slf4j @Service @RequiredArgsConstructor`, collaborators include `MemberStatusTransitionService`,
  `MemberRepository`, `MemberDocumentRepository`, `AuditPublisher`, `UserEventPublisher`. Methods:
  - `submitForVerification(memberId, actorId, actorEmail)` — sets verification `pending_verification`,
    and if the tenant gates activation, transitions the member via
    `MemberStatusTransitionService.transition(..., newStatus="pending_verification",
    reasonCode="kyc_submitted", actorEmail)`.
  - `decide(memberId, approve, reason, actorId, actorEmail)` — on approve: verification `verified`,
    `verified_by/_email/_at`, transition member to `active` (reason `kyc_verified`); on reject:
    `rejected` + reason (reason `kyc_rejected`). Emits audit (`entityType="MemberVerification"`) and
    `UserEventPublisher.publishMemberVerificationChanged(...)` → new topic.
- **Enroll gate**: in `MemberService.enroll(...)`, when the tenant's membership-model config flags
  verification-required, derive initial status `pending_verification` instead of `active`/`enrolled`
  and create the `member_verification` row.

#### 3. New lifecycle Kafka event
**File**: `.../service/UserEventPublisher.java`
**Changes**: `publishMemberVerificationChanged(memberId, oldStatus, newStatus, level, actorEmail)`
→ topic `medfund.users.member-verification-changed`, flat `LinkedHashMap` + `"event"` discriminator,
keyed by `memberId`, best-effort.

#### 4. Controller — actions + pending queue
**File**: `.../controller/MemberVerificationController.java`
- `POST /api/v1/members/{memberId}/verification/submit`
- `POST /api/v1/members/{memberId}/verification/decision` (body: approve + reason) — **MFA-gated**
  via `MfaStepUpGuard` (staff-facing high-privilege mutation)
- `GET /api/v1/members/{memberId}/verification`
- `GET /api/v1/members/pending-verification` (paged) — backs the `/tenant/members/pending` queue
- Full Swagger; `AuditActor` for actor id/email.

### Success Criteria

#### Automated Verification:
- [ ] Java compiles: `cd services/java && ./gradlew :user-service:build`
- [ ] Integration test: `make test-integration` — `MemberVerificationIT` (extends
      `AbstractPostgresIntegrationTest`): submit → member goes `pending_verification`; approve →
      member `active`, history row with reason `kyc_verified`, `member-verification-changed` event
      asserted via `@MockBean` publisher; reject path; a non-MFA decision is rejected
- [ ] Tenant migrations V278+V279 apply on a fresh testcontainer (IT harness)
- [ ] Swagger renders the four endpoints

#### Manual Verification:
- [ ] Enroll with verification-required tenant config → member lands `pending_verification`
- [ ] Approve via API → member active, audit trail shows dual actor id+email

**Implementation Note**: pause for human confirmation before Phase 5.

---

## Phase 5: OCR ID-document extraction (ai-service, Python)

### Overview
Add an ID-document OCR variant that extracts identity fields and writes an auditable prediction,
pulled synchronously by user-service during verification.

### Changes Required

#### 1. Extend the anonymiser FIRST (Rule #4 gate)
**File**: `services/python/ai-service/app/core/anonymize.py`
**Changes**: add identity keys to `_STRIPPED_KEYS`: `national_id`, `passport_number`,
`drivers_license`, `date_of_birth`, `full_name`, `first_name`, `last_name`, `address`,
`document_number`. Without this, ID fields persist verbatim in `ai_predictions_store`.

#### 2. ID-document prompt path
**File**: `services/python/ai-service/app/services/ocr_service.py`
**Changes**: add `doc_type: str | None = None` to `extract_structured_data(...)`; when it names an
ID document, use an ID-extraction prompt (fields: `full_name, national_id, passport_number,
date_of_birth, sex, nationality, document_number, expiry_date, issuing_authority`) and system
prompt "You are an identity-document data extractor." Healthcare path unchanged when `doc_type` is
absent.

#### 3. OCR endpoint accepts `doc_type`, writes a distinct prediction
**File**: `services/python/ai-service/app/api/ocr.py`
**Changes**: add `doc_type: str | None = Form(None)`; when present, call the ID path and write
`try_record_ai_prediction(... entity_type="member", entity_id=<memberId from form>,
prediction_type="ocr_id_document", input_features=anonymize_features({...}),
output=anonymize_features(result), confidence=...)`. Keep `entity_type="document"` /
`prediction_type="ocr"` for the existing claim path.

#### 4. user-service pulls OCR during verification
**File**: `services/java/user-service/.../service/MemberVerificationService.java`
**Changes**: when a document is submitted for an ID type, stream its bytes from file-service
(`GET /files/documents/stream?ref=...`) and POST to ai-service `/api/v1/ai/ocr/extract`
(`doc_type`, `memberId`, `X-Tenant-ID`), store the returned extract summary + the prediction id on
`member_verification.ocr_prediction_id`, set `method='ocr'`. Sync REST (read/compute) per Rule #6;
fail-open (OCR failure leaves status `pending_verification` for manual review).

### Success Criteria

#### Automated Verification:
- [ ] Python tests: `make test-python` — mirror `tests/test_prediction_audit.py`: POST multipart
      with `doc_type=national_id` + `memberId` + `X-Tenant-ID`, assert a row with
      `prediction_type="ocr_id_document"`, `entity_type="member"`, and that `national_id`/
      `date_of_birth` are **absent** from persisted `input_features`/`output` (anonymiser test)
- [ ] ai-service `/docs` renders the `doc_type` param
- [ ] Java IT: OCR call stubbed (WireMock/`@MockBean` client), `ocr_prediction_id` persisted

#### Manual Verification:
- [ ] Upload a real ID image → OCR fields surface on the verification record

---

## Phase 6: Sanctions/PEP identity screening — in-house AI classifier (ai-service, Python)

### Overview
A new screening module: an in-house LLM classifier scores a member's identity against provided
watchlist context, writing an auditable prediction reviewable in the existing predictions queue.

### Changes Required

#### 1. Screening router + schema
**File**: `services/python/ai-service/app/api/screening.py` (new)
```python
router = APIRouter(prefix="/api/v1/ai/screening", tags=["Identity Screening"])

@router.post("/sanctions-pep", response_model=ScreeningResponse)
async def screen_identity(
    req: ScreeningRequest,                       # full_name, dob, nationality, national_id, member_id
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    session: AsyncSession | None = Depends(get_optional_session),
) -> ScreeningResponse:
    result = await _screening_service.screen(req)         # LLM classifier, fail-open
    try_record_ai_prediction(
        session, tenant_id=x_tenant_id, insurance_line=None,
        entity_type="member", entity_id=req.member_id,
        prediction_type="sanctions_pep_screening",
        model_version=result.model_version,
        input_features=anonymize_features(req.model_dump()),
        output=anonymize_features(result.model_dump()),
        confidence=result.confidence)
    return result
```
`ScreeningService` uses the injected LLM (Claude/Gemini, same `resolve_llm` pattern as OCR) with a
classifier prompt that returns `{decision: clear|review|hit, matched_lists: [...], rationale,
confidence}`. Pluggable: a `ScreeningProvider` protocol leaves room for a real vendor adapter
(modeled on `member_context_client.py`) behind the same `prediction_type` later.

**File**: `app/main.py` — import `screening_router`, add `app.include_router(screening_router)`.

#### 2. user-service calls screening during verification
**File**: `.../service/MemberVerificationService.java`
**Changes**: after OCR, POST identity fields to `/api/v1/ai/screening/sanctions-pep`; store
`screening_prediction_id` + `screening_result` on `member_verification`. A `hit`/`review` forces
the member to stay `pending_verification` for mandatory human review regardless of OCR confidence.

### Success Criteria

#### Automated Verification:
- [ ] Python tests: `make test-python` — screening endpoint persists a
      `prediction_type="sanctions_pep_screening"` row; identity PII absent from persisted features;
      LLM stubbed (`.available=False` fallback path like the OCR audit tests)
- [ ] `/docs` renders the screening endpoint
- [ ] Java IT: screening stubbed, `screening_result` persisted; a `hit` keeps the member pending

#### Manual Verification:
- [ ] A deliberately "flagged" name routes to `review` and appears in the predictions review queue

---

## Phase 7: Angular staff verification review UI (Angular)

### Overview
Surface verification status on member detail, add the `/tenant/members/pending` queue, and the
MFA-gated decision workflow showing OCR + screening results.

### Changes Required

#### 1. Pending-verification queue page
**File**: `clients/angular/src/app/pages/tenant/members/pending/pending-verification.component.*` (new)
**File**: `members.routes.ts` — replace nothing; add `pending` route
(`permissionGuard(['members:view'])`, title "Pending Registrations"). (The sidebar row already
exists per `portals.md:178`.)

#### 2. Verification block + decision modal on member detail
**File**: `member-detail.component.*`
**Changes**: a Verification `.form-card`: status chip (`badge-pending_verification`/`badge-verified`),
`verification_level`, OCR extracted fields (read-only), screening result, and Approve/Reject actions
opening a decision modal (mirrors the existing change-group/death modals). The decision call hits
the MFA-gated endpoint; the app must perform MFA step-up when prompted (the shared MFA flow).

#### 3. Service methods
**File**: `members.service.ts` — `getVerification(memberId)`,
`submitVerification(memberId)`, `decideVerification(memberId, {approve, reason})`,
`getPendingVerifications(page)`.

### Success Criteria

#### Automated Verification:
- [ ] Angular unit tests: `make test-angular`
- [ ] Playwright: `make test-e2e` — `member-verification.spec.ts`: stub the verification endpoints +
      pending list, assert the queue renders, open a member, approve → status chip flips to verified;
      a read-only user sees no Approve button
- [ ] `verify` on `/tenant/members/pending` and `/tenant/members/:id`: no console errors, queue and
      verification block render, decision interaction fires

#### Manual Verification:
- [ ] End-to-end: upload ID (Phase 3) → submit → OCR+screening populate → approve (with real MFA
      step-up) → member active; audit + security events recorded

**Implementation Note**: this completes staff KYC end-to-end. Pause for human sign-off before Flutter.

---

## Phase 8: Flutter member foundation (Flutter)

### Overview
Make the scaffold able to talk to the backend: real Keycloak PKCE auth, API-client + Riverpod
wiring, `member_id` resolution, env-configurable base URL, and a file picker. **No KYC feature yet
— this is the enabling foundation the scaffold lacks.**

### Changes Required
- **Auth** — wire `flutter_appauth` PKCE against the tenant realm; store tokens in
  `FlutterSecureStorage` (reuse `AuthService`); replace the fake login button nav.
- **API client** — instantiate `ApiClient` behind a Riverpod provider; make `baseUrl`
  env/platform-configurable (not the hardcoded emulator alias); the interceptor already attaches
  `Authorization` + `X-Tenant-ID`.
- **Identity** — decode the JWT to resolve `member_id`/tenant; expose via a provider; replace the
  hardcoded profile strings.
- **Providers** — a first real data-fetch example (e.g. profile from `/members/me`) as the pattern
  for later screens.
- **Picker** — add `image_picker` (or `file_picker`) to `pubspec.yaml`.

### Success Criteria

#### Automated Verification:
- [ ] `make test-flutter` — widget tests: login screen renders; a provider-overridden fake
      `ApiClient` drives the profile screen with injected data (not literals)
- [ ] `flutter analyze` clean

#### Manual Verification:
- [ ] Real Keycloak login against a dev tenant realm; profile shows the logged-in member's data

**Implementation Note**: pause — this foundation is a prerequisite for Phase 9 and carries real
risk (auth). Confirm login works before building the KYC screen.

---

## Phase 9: Flutter member self-upload KYC (Flutter)

### Overview
A Documents screen where a member captures/uploads ID documents and sees verification status; wire
the `PENDING_VERIFICATION` self-registration path.

### Changes Required
- **Documents screen** `lib/screens/documents_screen.dart` (new) — picker → `POST /files/documents`
  (multipart via dio) → `POST /members/{id}/documents` → `POST /members/{id}/verification/submit`;
  list existing documents + statuses. New `/documents` route in the `ShellRoute`.
- **Verification status** — surface the member's `verification_status` on dashboard/profile with the
  same semantics as Angular.
- **Self-registration** — a sign-up path that creates the member via the enroll endpoint; on
  verification-required tenants the member lands `pending_verification`.

### Success Criteria

#### Automated Verification:
- [ ] `make test-flutter` — widget test with a fake `ApiClient`: picking a file posts to the
      documents endpoint and the row appears; submit flips status to pending
- [ ] `flutter analyze` clean

#### Manual Verification:
- [ ] On device/emulator: capture an ID photo, upload, see it appear and the member go pending; it
      shows in the Angular staff queue (Phase 7)

---

## Phases 10–14: The remaining five member layers (user-service + Angular)

Each layer is a structural repeat of **Phase 2** (tenant migration → entity → repo → service →
controller + Swagger → audit + Kafka → IT) plus a member-detail `.form-card` block and (optionally)
a Flutter section. Migrations take the next free versions **V280–V284**. All tenant-schema, no
`tenant_id` column, line-neutral wording per the framing rule.

| # | Layer | Table(s) (migration) | Notes |
|---|---|---|---|
| 10 | **Contacts + comms prefs** | `member_contacts`, `member_communication_preferences` (V280) | Multi-channel; preferred-channel; opt-in/out. Supersedes flat `email`/`phone`/`address` (keep those readable for back-compat). |
| 11 | **Consent / data-subject rights** | `member_consent` (V281) | Append-only consent ledger (grant/withdraw + purpose + basis). Links to existing `/admin/data-export`; **records** DSR requests, does not execute erasure (Rule #4). |
| 12 | **Beneficiary / payout nomination** | `member_beneficiaries` (V282) | Distinct from the existing `/beneficiaries/search` abstraction; nomination % must sum ≤100; use `app-entity-picker` for any member/dependant link. |
| 13 | **Next-of-kin / emergency contact** | `member_next_of_kin` (V283) | Name, relationship, contacts. |
| 14 | **Risk / underwriting profile** | `member_risk_profile` (V284) | Generalises `members.medical_history` JSONB into line-neutral facts; keep medical specifics for HEALTH only. Feeds rules-engine facts later. |

### Success Criteria (per layer)

#### Automated Verification:
- [ ] `cd services/java && ./gradlew :user-service:build`
- [ ] `make test-integration` — a `*IT` per layer asserting CRUD + audit (`entityType` = the layer),
      migration applies on a fresh testcontainer
- [ ] Swagger renders each layer's endpoints
- [ ] `make test-angular` + `verify` on the member-detail block
- [ ] Beneficiary layer: a test asserting nomination percentages cannot exceed 100

#### Manual Verification:
- [ ] Each block renders and round-trips on `/tenant/members/:id`

---

## Testing Strategy

### Unit Tests
- Java: service-layer tests per new service (status transitions, enroll-gate derivation,
  nomination-sum guard).
- Go: documents handler (clean/threat/oversize/missing-field) with fake `Scanner` + fake MinIO.
- Python: OCR ID path + anonymiser (identity keys stripped) + screening classifier (stubbed LLM).
- Angular: service + component specs.

### Integration Tests (Testcontainers slices)
- `MemberDocumentIT`, `MemberVerificationIT`, and a `*IT` per layer — each extends
  `AbstractPostgresIntegrationTest`, migrates the tenant family (V278–V284), `@MockBean`s the
  publishers, and asserts the audit event shape (friendly `entityName`, non-blank `actorEmail`).
- OCR/screening calls stubbed at the HTTP-client boundary.

### E2E Tests (Playwright, clients/angular/e2e)
- `member-documents.spec.ts`, `member-verification.spec.ts` — upload, submit, review, approve/reject;
  read-only users see no action buttons.

### Manual Testing Steps
1. `make infra`; bring up file-service, user-service, ai-service, gateway, Angular (`make web`).
2. Staff path: upload ID → submit → OCR + screening populate → approve with real MFA → member active.
3. Member path (Flutter): login, capture ID, upload, see pending; confirm it appears in the staff queue.
4. Edge: oversize file (413), a seeded "threat" (422), a screening `hit` (forced manual review).

## Performance Considerations

- Document bytes transit file-service on upload — enforce `MaxUploadSizeMB` and a `BodyLimit`;
  read fully into memory only up to that cap (documents are small). Stream on the download path.
- `member_documents.member_id` and `member_verification.verification_status` are indexed for the
  per-member list and the pending queue.
- OCR/screening are synchronous reads on the verification action, not on the hot enroll path; they
  are fail-open so a slow AI call never blocks member creation.
- Pending-queue and any KYC stats are server-aggregated (report-envelope convention) — never summed
  in Angular.

## Migration Notes

- **Flyway ordering:** V278 (`member_documents`) → V279 (`member_verification` + status widen) →
  V280–V284 (the five layers), all in the **tenant** family
  (`tenancy-service/.../db/migration/tenant/`). Never edit an applied migration — the status-vocab
  widen is a new DROP+re-ADD file, not an edit of V242 (`feedback_never_edit_applied_migrations`).
- **No public-schema migration** — these tables are tenant-local; no `public._tenants` join
  (Rule #2). (Mind `bug_public_prefix_silent_rollback` / `bug_public_flyway_history_load_bearing`
  only if a public migration is ever added — it isn't here.)
- **Backfill:** `pending_verification` is forward-only (new enrolments on verification-required
  tenants); existing members are unaffected. No `member_verification` backfill — rows are created
  lazily on first submit/enroll.
- **Kafka:** two additive topics (`medfund.documents.uploaded`,
  `medfund.users.member-verification-changed`); no recompaction. No rules-engine recompilation
  (static config). No Keycloak realm change.

## Rollout & Rollback

- **Deploy order (additive, producer before consumer):** file-service (Phase 1) → user-service
  (Phases 2,4,5-caller) → ai-service (Phases 5,6) → Angular (3,7) → Flutter (8,9). The five layers
  (10–14) deploy independently.
- **Rollback:** each phase is behind its own endpoints/UI; reverting a service leaves the new
  (unused) tenant tables in place — harmless (no `tenant_id`, no cross-tenant risk). The status-vocab
  widen is backwards-compatible (only adds a value); rolling back the gate means new enrolments stop
  landing `pending_verification` — no orphaned events because consumers are optional.

## References

- Research: `thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md`
- Architecture: `.claude/portals.md` (member/pending queue, roles), `.claude/ai-integration.md`
  (OCR/prediction store), `.claude/multi-tenancy.md` (schema-per-tenant), `.claude/payments.md:610`
  (KMS/cleartext gap)
- Patterns to mirror:
  - `services/java/tenancy-service/.../tenant/V272__siu_evidence.sql` (document handle)
  - `services/java/user-service/.../entity/Dependant.java` + repository/service/controller (CRUD quartet)
  - `services/java/user-service/.../status/MemberStatusTransitionService.java` (status machine)
  - `services/java/tenancy-service/.../tenant/V242__member_death_status_and_reason.sql` (status CHECK)
  - `services/python/ai-service/app/api/ocr.py` + `app/api/predictions.py` (OCR + review queue)
  - `services/go/file-service/internal/handler/handler.go` + `cmd/main.go` (handler wiring)
  - `clients/angular/.../finance/creditors/member-balance-detail.component.ts` (tabbed detail)
  - `clients/angular/.../shared/components/entity-picker/entity-picker.component.ts` (ID inputs)
  - `clients/angular/e2e/tests/member-dependant-lifecycle.spec.ts` (Playwright harness)
