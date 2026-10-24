---
date: 2026-10-23
git_commit: e5051ef3a1cccc79fcebb62668ea589f2dcb98c0
branch: main
ticket: none — grounded in research + interactive scope decisions
research:
  - thoughts/shared/research/2026-10-22-kyc-pilot-horizontal.md
  - thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md
steer: "reconcile toward a standalone, line-agnostic, sellable KYC horizontal: party-neutral subject, build the entitlement seam, decide kyc-service vs user-service module"
services_touched: [shared, tenancy-service, finance-service, user-service, kyc-service, file-service, ai-service, flutter]
status: updated (2026-10-23) — Plan A grilling cascade: F1 migration bump + G2 biometric SKU gating
kind: plan
program: thoughts/shared/plans/2026-10-23-kyc-horizontal-program-overview.md
sequence: C (of A→B→C)
depends_on: thoughts/shared/plans/2026-10-23-kyc-plan-b-verification-end-to-end.md
---

# KYC Plan C — Self-Service + Biometrics

> **Updated 2026-10-23 from Plan A's grilling.** Two cascades: **F1** — `tenant_crypto_config` is public
> **V188** (not V187), because Plan A's entitlements migration took V187 (V186 was already present).
> **G2** — biometrics is a **separate SKU**: the `/api/v1/ai/biometric/*` endpoints and kyc-service biometric
> routes gate on the **`KYC_BIOMETRICS`** entitlement (not `KYC`). See `scratchpad/plan-a-grilling-log.md`.

## Overview

Complete the horizontal with member self-service and biometric assurance, on an **encrypted-at-rest
platform**. Ordered deliberately: **repo-wide KMS first** (so no biometric template or ID field is ever
written cleartext), then the Flutter member foundation (the scaffold has no working auth/API wiring today),
then Flutter self-upload KYC, then biometric face-match + liveness.

This is the largest and riskiest plan — KMS is effectively its own sub-project, and biometrics is a net-new
computer-vision pipeline. Each phase is independently verifiable; KMS and the Flutter foundation each carry a
hard human-confirmation pause because they are high-blast-radius (encryption correctness; mobile auth).

Prerequisites (from Plan B): working document-KYC for members + providers, OCR + screening, the Angular
staff surface. Biometrics extends the same `ai_predictions_store` pattern with a `biometric_match`
`prediction_type`.

## Current State Analysis (entering Plan C)

- **No encryption exists.** AES-256-GCM + per-tenant AWS KMS is documented intent only
  (`.claude/payments.md:591,610`, `.claude/infrastructure.md:289-292`). Bank details are cleartext:
  `finance-service TenantBankAccount.accountNumber/swiftCode` (plain String, DDL `tenant/V075`),
  `user-service Provider.bankingDetails` (`public/V105:19` TEXT), `finance-service Producer` banking (jsonb).
  **No `Cipher`/jasypt/KMS/AWS-SDK anywhere** in any stack; MinIO is the S3 stand-in with **no SSE** configured
  (`file-service/internal/storage/minio.go:60`). The one symmetric-key precedent is HMAC token signing
  (`ScheduledDownloadTokenIssuer`, `HmacSHA256`, config-injected secret).
- **R2DBC converter precedent exists in exactly one place** — `tenancy-service/.../config/R2dbcConfig.java:31-53`
  (a `@WritingConverter`/`@ReadingConverter` pair mapping `jsonb ⇄ JsonString`). **Critical limitation:** Spring
  Data R2DBC converters are stateless container beans — they get **no per-row/per-tenant context**, so a
  converter cannot select a tenant's KMS key. Field encryption must therefore hook at the **service layer**
  (where `tenantId` is in hand via `TenantContext`), not via a transparent converter.
- **Tenant→key mapping has no home yet** — the `Tenant*Config` family (one `public` row per tenant, FK
  `tenant_id`, e.g. `TenantCurrencyConfig`) is the pattern to mirror for a `tenant_crypto_config`.
- **Flutter is a bare scaffold** (`clients/flutter`, `medfund_mobile`): five screens render hardcoded mock
  data; `ApiClient` (dio), `AuthService`, `flutter_appauth` (Keycloak PKCE), Riverpod, `mobile_scanner` are
  dependencies with **zero wiring**. No file picker, no Documents screen, no self-registration, no `member_id`
  resolution. One `GoRouter` in `lib/router.dart`.

## Desired End State

- A per-tenant envelope-encryption service (AWS KMS → per-tenant data key → AES-256-GCM), with a
  `tenant_crypto_config` key mapping, wired as a **service-layer** encrypt/decrypt helper in `shared`.
- Existing cleartext PII (bank details, provider/producer banking) encrypted at rest; MinIO objects written
  with SSE; KYC ID-document bytes and biometric templates stored encrypted.
- A Flutter member app that authenticates via Keycloak PKCE, resolves its `member_id`, talks to the gateway,
  and can capture/upload ID documents and see verification status.
- Member self-registration landing `pending_verification` on verification-required tenants.
- Biometric face-match + liveness: a selfie is captured, liveness-checked, embedded, and matched against the
  ID-document photo; the result writes an auditable `biometric_match` prediction and feeds the
  `VerificationFact` (Rule #3); the biometric template is stored encrypted (never cleartext).

### Key Discoveries
- **Encrypt at the service layer, not via an R2DBC converter** — the converter cannot see tenant context
  (`R2dbcConfig.java` limitation). Introduce a `shared` `FieldCipher` injected into each PII-owning service
  (`TenantBankAccountService`, `ProviderService`, `KycDocumentService`, the biometric store) that encrypts
  before `insert`/`save` and decrypts on read.
- **Envelope encryption** — KMS wraps a per-tenant data key (DEK) stored (wrapped) in `tenant_crypto_config`;
  the service decrypts the DEK via KMS (cached briefly) and does AES-256-GCM locally — avoids a KMS call per field.
- **AWS SDK is net-new in all three stacks** — add `software.amazon.awssdk:kms` (Java), and for local/dev use
  MinIO-KMS or LocalStack behind the same seam; gate real KMS behind a config property so dev stays
  cleartext-free via a `LocalFieldCipher` (random local master key) — mirror the `StubPdfGenerator`/`NoopScanner`
  stub-vs-real swap.
- **Deterministic-encryption caveat** — `TenantBankAccount` has `UNIQUE (account_number, currency_code)`
  (`V075:33`) on a value to be encrypted. Either drop/replace the uniqueness guard, or store a separate
  blind-index (HMAC) column for uniqueness. Flagged below.
- Flutter needs an enabling foundation phase before any KYC screen — auth is the real risk.

## What We're NOT Doing
- Not migrating every String column to encrypted — only genuine PII (bank/banking details, ID-document bytes,
  biometric templates). Non-PII stays plain.
- No change to Keycloak as IdP; Flutter uses the existing PKCE realm config.
- No external biometric vendor — in-house embedding + liveness; the `ScreeningProvider`-style seam leaves room
  for a vendor swap behind the `biometric_match` prediction type.
- No offline/edge biometric inference in this plan (server-side match; the device captures + liveness-signals only).

## Implementation Approach
KMS first and foremost (Phases 1–2): the `shared` cipher + key mapping, then retrofit existing cleartext PII
and MinIO SSE. Only once encryption-at-rest exists do the Flutter foundation (Phase 3), Flutter self-upload
(Phase 4), and biometrics (Phase 5) land — biometrics writes an encrypted template, which is the whole reason
KMS is sequenced ahead of it.

**Contracts fixed up front:**
- `shared` `FieldCipher` interface: `Mono<String> encrypt(UUID tenantId, String plaintext)` /
  `Mono<String> decrypt(UUID tenantId, String ciphertext)` (base64 envelope: keyId + IV + ciphertext + tag).
  Real `KmsFieldCipher` (AWS KMS) vs dev `LocalFieldCipher`, selected by `crypto.kms.enabled`.
- `tenant_crypto_config` (public, one row per tenant): `tenant_id`, `kms_key_arn`, `wrapped_dek`, rotation cols.
- No Kafka contract changes; biometrics reuses `ai_predictions_store` + `medfund.kyc.verification-changed`.

---

## Phase 1: Platform encryption foundation — per-tenant KMS envelope cipher (shared + tenancy-service)

### Overview
A `shared` field-encryption service backed by per-tenant KMS envelope keys, with a dev stub so local/test
runs never need real AWS. No PII retrofit yet (Phase 2) — this phase delivers and proves the cipher.

### Changes Required
- **AWS SDK dep** — root `services/java/build.gradle.kts` / `shared/build.gradle.kts`: add
  `software.amazon.awssdk:kms` (BOM-managed). No other service adds it — they depend on `shared`.
- **Key mapping table** — `tenancy-service/.../db/migration/public/V188__tenant_crypto_config.sql` (new;
  **F1 cascade: V188, not V187** — Plan A's entitlements migration took V187;
  mirror `TenantCurrencyConfig`): `tenant_id UUID PK/FK`, `kms_key_arn VARCHAR(512)`, `wrapped_dek TEXT`,
  `dek_rotated_at TIMESTAMPTZ`, `created_at`. Entity + repo in tenancy-service; a provisioning hook in
  `SchemaProvisioningService` to create/register a per-tenant KMS key (real) or a local DEK (dev) at tenant
  provision time.
- **`FieldCipher`** — `shared/.../crypto/FieldCipher.java` (interface), `KmsFieldCipher.java`
  (`@ConditionalOnProperty("crypto.kms.enabled")`; KMS `GenerateDataKey`/`Decrypt`, AES-256-GCM via
  `javax.crypto.Cipher`, wrapped-DEK cache keyed by tenant with short TTL), `LocalFieldCipher.java` (default;
  per-tenant random master key held in memory + the same AES-GCM path — keeps dev/test free of cleartext
  without AWS). Select via config, mirror the `StubPdfGenerator`/`NoopScanner` swap. Envelope format:
  base64(`version|keyRef|iv|ciphertext|tag`).
- **Tenant key resolution** — a `TenantKeyProvider` reading `tenant_crypto_config` (via `DatabaseClient`,
  tenant-aware) to get the wrapped DEK/key ARN; `FieldCipher` takes `tenantId` and resolves through it.

### Success Criteria
#### Automated Verification:
- [ ] Java compiles: `cd services/java && ./gradlew :shared:build :tenancy-service:build`
- [ ] Unit tests: `make test-java` — `LocalFieldCipher` round-trips (encrypt→decrypt == plaintext); distinct
      tenants produce non-equal ciphertext for the same plaintext; a tampered tag fails decryption (GCM auth);
      ciphertext is not the plaintext (no accidental passthrough)
- [ ] Integration test: `make test-integration` — `TenantCryptoConfigIT`: provisioning a tenant creates a
      crypto-config row; `FieldCipher` encrypts/decrypts against it; **V188** applies on a fresh container
- [ ] `crypto.kms.enabled=false` (default) selects `LocalFieldCipher` (config test); no AWS call in tests
#### Manual Verification:
- [ ] With `crypto.kms.enabled=true` against LocalStack/MinIO-KMS: encrypt a value, confirm the stored
      envelope is non-cleartext and decrypts back; confirm a KMS `Decrypt` is issued once per tenant (cache working)

**Implementation Note**: hard pause. Encryption correctness is high-blast-radius — confirm round-trip + tamper-fail + per-tenant isolation before any real data is encrypted in Phase 2.

---

## Phase 2: Encrypt existing PII at rest + MinIO SSE (finance-service, user-service, file-service)

### Overview
Retrofit the cleartext PII the research found, at the service layer, and enable MinIO server-side encryption
for document objects. Backfill existing rows.

### Changes Required
- **Service-layer encrypt/decrypt** — inject `FieldCipher` into `TenantBankAccountService` (encrypt
  `accountNumber`/`swiftCode` before `insert`/`save`, decrypt on read), `ProviderService` (encrypt
  `bankingDetails`), the `Producer` banking path, and `KycDocumentService` where any extracted field is held.
  Columns stay `VARCHAR`/`TEXT` but now hold the base64 envelope (widen length if needed via a new migration).
- **Uniqueness caveat** — `TenantBankAccount` `UNIQUE (account_number, currency_code)` (`V075:33`): add a
  new migration dropping it and adding a `account_number_bidx VARCHAR(64)` blind-index (HMAC via `FieldCipher`'s
  keyed MAC) with `UNIQUE (account_number_bidx, currency_code)`; the service computes the blind index on write.
- **MinIO SSE** — `file-service/internal/storage/minio.go`: pass `ServerSideEncryption` on `PutObject` for the
  documents bucket; config flag `MINIO_SSE_ENABLED`. (Server-side-at-rest for bytes; app-level envelope remains
  the primary control for structured fields.)
- **Backfill** — a one-shot migration runner / admin job that reads each existing cleartext PII row and
  rewrites it encrypted (idempotent: detect already-enveloped values by the version prefix). Document the
  ordering: deploy the encrypting services first (write encrypted, read-both), then run backfill, then remove
  the read-cleartext fallback.

### Success Criteria
#### Automated Verification:
- [ ] Java compiles + unit tests: `make test-java` — services round-trip encrypted fields transparently;
      blind-index uniqueness still rejects a duplicate account number
- [ ] Integration test: `make test-integration` — a bank account persisted then read back returns the
      plaintext to the caller but the raw column (read via `DatabaseClient`) is non-cleartext; backfill job
      converts a seeded cleartext row and is idempotent on re-run
- [ ] Go tests: `make test-go` — `PutObject` sets the SSE option when enabled
#### Manual Verification:
- [ ] Inspect the DB directly: `account_number`/`banking_details` are ciphertext; the app still shows the
      correct value; MinIO object metadata shows SSE
- [ ] `.claude/payments.md:610`'s documented cleartext gap no longer holds — update that doc line

**Implementation Note**: pause — verify no PII is readable at rest before proceeding to store biometric templates.

---

## Phase 3: Flutter member foundation (Flutter)

### Overview
Make the scaffold talk to the backend: real Keycloak PKCE auth, API-client + Riverpod wiring, `member_id`
resolution, env-configurable base URL, a file picker. **No KYC feature yet — the enabling foundation.**
*(Carried from the 2026-10-21 plan's Flutter foundation phase.)*

### Changes Required
- **Auth** — wire `flutter_appauth` PKCE against the tenant realm; store tokens in `FlutterSecureStorage`
  (reuse `AuthService`); replace the fake login nav.
- **API client** — instantiate `ApiClient` (dio) behind a Riverpod provider; make `baseUrl`
  env/platform-configurable (not the hardcoded emulator alias); the interceptor already attaches
  `Authorization` + `X-Tenant-ID`.
- **Identity** — decode the JWT to resolve `member_id`/tenant; expose via a provider; replace hardcoded
  profile strings.
- **First real fetch** — profile from `/members/me` as the pattern for later screens.
- **Picker** — add `image_picker`/`file_picker` to `pubspec.yaml`.

### Success Criteria
#### Automated Verification:
- [ ] `make test-flutter` — widget tests: login renders; a provider-overridden fake `ApiClient` drives the
      profile screen with injected data (not literals)
- [ ] `flutter analyze` clean
#### Manual Verification:
- [ ] Real Keycloak login against a dev tenant realm; profile shows the logged-in member's data

**Implementation Note**: hard pause — auth is the real risk. Confirm real login works before building the KYC screen.

---

## Phase 4: Flutter member self-upload KYC (Flutter)

### Overview
A Documents screen where a member captures/uploads ID documents and sees verification status; wire the
`pending_verification` self-registration path. *(Carried from the 2026-10-21 plan.)*

### Changes Required
- **Documents screen** `lib/screens/documents_screen.dart` (new) — picker → `POST /api/v1/files/documents`
  (multipart via dio, subjectType=MEMBER + subjectId) → `POST /api/v1/verifications/MEMBER/{id}/documents`
  → `POST /api/v1/verifications/MEMBER/{id}/submit`; list existing documents + statuses. New `/documents`
  route in the `ShellRoute`.
- **Verification status** — surface `verification_status` on dashboard/profile with the same semantics as Angular.
- **Self-registration** — a sign-up path creating the member via the enroll endpoint; on verification-required
  tenants the member lands `pending_verification` and appears in the Angular staff queue (Plan B).

### Success Criteria
#### Automated Verification:
- [ ] `make test-flutter` — widget test with a fake `ApiClient`: picking a file posts to the documents
      endpoint and the row appears; submit flips status to pending
- [ ] `flutter analyze` clean
#### Manual Verification:
- [ ] On device/emulator: capture an ID photo, upload, see it appear and the member go pending; it shows in
      the Angular staff queue and OCR+screening run

---

## Phase 5: Biometric face-match + liveness (ai-service + kyc-service + Flutter)

### Overview
Capture a selfie with liveness, embed it, match against the ID-document photo, write an auditable
`biometric_match` prediction, feed the `VerificationFact`, and store the biometric template **encrypted**
(KMS from Phase 1). The capstone of the horizontal.

### Changes Required
- **Entitlement gating (G2)** — biometrics is a **separate SKU** from base KYC. The kyc-service biometric
  routes carry `@RequiresEntitlement(ModuleKey.KYC_BIOMETRICS)`, and the gateway `prefixToModule` map gates
  the biometric route prefix on **`KYC_BIOMETRICS`** (not `KYC`). A tenant can hold `KYC` without
  `KYC_BIOMETRICS`; base document-KYC never requires the biometrics entitlement. (`ModuleKey.KYC_BIOMETRICS`
  was added in Plan A Phase 1 §1.)
- **Biometric module (ai-service)** — `app/api/biometric.py` (new): `POST /api/v1/ai/biometric/match`
  (`BiometricMatchRequest{subject_type, subject_id, selfie_ref, id_document_ref, liveness_signals}`,
  `X-Tenant-ID`): pull both images (selfie + ID photo) from file-service, run liveness heuristics on the
  provided signals, compute embeddings (an in-house face-embedding model — a `BiometricProvider` protocol
  leaves room for a vendor), score similarity, return `{decision: match|no_match|review, score, liveness:
  pass|fail, model_version}`. Write `try_record_ai_prediction(... prediction_type="biometric_match",
  input_features=anonymize_features({...}) /* no raw image bytes, no template */, output={decision,score,liveness},
  confidence=score)`. The embedding/template is **not** placed in the prediction store; it is persisted
  encrypted (next bullet).
- **Encrypted biometric template store** — `kyc-service`: a `kyc_biometric_template` table (tenant family,
  new migration `V280`) holding `subject_type/subject_id`, `template_ciphertext TEXT` (AES-GCM envelope via
  `FieldCipher`, Phase 1), `model_version`, `created_at`. kyc-service encrypts before insert, decrypts only
  when a re-match is needed. **Hard dependency on Phase 1/2** — this is why KMS is sequenced first.
- **kyc-service orchestration** — extend `KycVerificationService`: after OCR/screening, if the tenant/line
  rules require biometrics (a `VerificationFact` flag), call `/biometric/match`; a `no_match` or failed
  `liveness` forces `requireManualReview` / keeps `pending_verification`; a `match`+`liveness pass` satisfies
  the biometric requirement. Store `biometric_prediction_id` on `kyc_verifications` (new column, migration).
- **Flutter capture** — `documents_screen.dart` / a new `liveness_screen.dart`: use `mobile_scanner`/camera +
  a simple liveness prompt (blink/turn), capture the selfie, upload via file-service (documentType=SELFIE),
  and trigger the match. Surface the biometric outcome in verification status.
- **Rules** — a seed `VERIFICATION` template: "require biometric match for LIFE/line X in jurisdiction Y"
  (authored as rule content — no engine change; Plan A registered the fact/category).

### Success Criteria
#### Automated Verification:
- [ ] Python tests: `make test-python` — `/biometric/match` persists `prediction_type="biometric_match"`;
      **no raw image bytes or embedding** in persisted features; a `no_match` and a failed-liveness path covered;
      model stubbed
- [ ] `/docs` renders the biometric endpoint
- [ ] Java IT: biometric call stubbed; a `no_match` keeps the subject `pending_verification`; the stored
      `kyc_biometric_template.template_ciphertext` is non-cleartext (read via `DatabaseClient`); V280 applies
- [ ] `make test-flutter` — the liveness/selfie capture widget posts and surfaces the outcome (fake ApiClient)
#### Manual Verification:
- [ ] On device: capture a live selfie against a matching ID → `match` + liveness pass → biometric requirement
      satisfied; a photo-of-a-photo → liveness fail → manual review; the template row is encrypted at rest
- [ ] The `biometric_match` prediction is visible in the audit/predictions trail with model version + score (Rule #3)

**Implementation Note**: final pause — biometric PII is irreversible. Confirm templates are encrypted at rest (Phase 1/2 verified) and the audit trail is complete before enabling for any real tenant.

---

## Testing Strategy
- **Unit**: `FieldCipher` round-trip/tamper/per-tenant isolation; blind-index uniqueness; service-layer
  encrypt/decrypt; biometric decision thresholds (match/no_match/review, liveness) with a stubbed model.
- **Integration (Testcontainers)**: `TenantCryptoConfigIT`, PII-at-rest assertions (raw column ciphertext),
  backfill idempotency, `kyc_biometric_template` ciphertext-at-rest; AI calls stubbed at the HTTP boundary.
- **Flutter**: widget tests with fake `ApiClient` for login, document upload, liveness capture.
- **Manual**: real Keycloak login; real encryption against LocalStack/MinIO-KMS; on-device capture + liveness.

## Performance Considerations
- Envelope encryption: KMS `Decrypt` of the wrapped DEK is cached per tenant (short TTL) — AES-GCM is local and
  cheap; avoid a KMS call per field.
- Biometric embedding is the heaviest compute — keep it off the enroll hot path (it runs on the verification
  action, async to member creation) and fail-open to manual review.
- MinIO SSE adds negligible latency; image bytes stream, never fully buffered beyond the size cap.

## Migration Notes
- Public **`V188`** (`tenant_crypto_config`, **F1**); tenant `V280` (`kyc_biometric_template`) + the
  bank-blind-index migration (next free tenant version, **V281**) — all from tenancy-service, tenant/public
  families as appropriate. Never edit applied migrations. *(The member-status-vocab widen is Plan B's `V279`,
  not this plan.)*
- **Encryption rollout ordering is load-bearing**: deploy encrypting services (write-encrypted, read-both) →
  run backfill → remove read-cleartext fallback. Document per PII table.
- Mind `bug_public_prefix_silent_rollback` / `bug_public_flyway_history_load_bearing` for public **V188**.
- No Kafka contract change; no Keycloak change (Flutter uses existing PKCE realms).

## Rollout & Rollback
- **Deploy order:** shared+tenancy (cipher + key config) → PII-owning services (encrypt + backfill) →
  file-service (SSE) → Flutter foundation → Flutter KYC → ai-service biometric + kyc-service template store →
  Flutter liveness.
- **Rollback:** the read-both fallback (during Phase 2) means reverting an encrypting service still reads old
  cleartext rows — do **not** remove the fallback until backfill is confirmed. Reverting biometrics leaves the
  encrypted template table unused (harmless). Reverting Flutter removes the member surface (staff path, Plan B,
  unaffected). KMS-disabled dev stays on `LocalFieldCipher` — never writes real-KMS-wrapped data it can't read back.

## References
- Program overview: `thoughts/shared/plans/2026-10-23-kyc-horizontal-program-overview.md`
- Plan B (prereq): `thoughts/shared/plans/2026-10-23-kyc-plan-b-verification-end-to-end.md`
- Research: `thoughts/shared/research/2026-10-22-kyc-pilot-horizontal.md`
- Intended-design docs: `.claude/payments.md:591,610`, `.claude/infrastructure.md:289-292`, `.claude/CLAUDE.md:145`
- Mirror sources: `tenancy-service/.../config/R2dbcConfig.java` (converter precedent + its limitation),
  `tenancy-service/.../entity/TenantCurrencyConfig.java` (per-tenant config table), `finance-service/.../entity/TenantBankAccount.java`
  + `tenant/V075`, `user-service/.../entity/Provider.java` + `public/V105`, `file-service/internal/storage/minio.go`,
  `finance-service/.../report/schedule/download/ScheduledDownloadTokenIssuer.java` (symmetric-key precedent),
  `ai-service/app/api/{ocr,predictions}.py` + `app/core/anonymize.py`, `clients/flutter/` scaffold,
  the `StubPdfGenerator`/`NoopScanner` stub-vs-real swap pattern
