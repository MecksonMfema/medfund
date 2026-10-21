---
date: 2026-09-13
git_commit: c97eb875bb84409598fa03078b57fd75a33d1304
branch: rename-adjustments-to-notes
ticket: none
research: none — "in-session investigation; the eligibility-quote surface and its backing service were both read fully in the conversation before this plan was written"
steer: "eligibility-quote is member-only; make it accept dependants without breaking the existing memberNumber wire"
services_touched: [claims-service, angular]
status: draft
---

## Deviations

- **2026-09-13** — replaced the planned full-stack `EligibilityQuoteDependantIT`
  with a slice IT (`MemberLookupClientDependantIT`) plus expanded unit-test
  coverage. **Why:** the existing claims-service `test-migration` schema was
  built for reporting (`AbstractClaimsReportIT`), not adjudication — a full-flow
  IT would need to seed `tariff_codes`, `benefit_categories`,
  `member_cost_share_accumulator`, `scheme_cost_share_config`, and the entire
  adjudication rules stack against the flat `public`-schema harness. The unit
  tests now cover every service-level dependant path (transient claim's
  `dependantId` set, coverage classification takes the more restrictive
  status, audit event carries `dependantId + dependantMemberNumber +
  dependantName`, mismatched sponsor/dependant rejection, unknown-dependant
  404), and the slice IT validates the new SQL against a real Postgres
  `dependants` schema. The remaining end-to-end path (Angular → gateway →
  claims → real accumulator read) is covered by the Phase 2 manual verification.


# Eligibility Quote — Dependant Support Implementation Plan

## Overview

`/tenant/claims/eligibility-quote` today only quotes members. The entity-picker is `kind="member"`, the wire DTO carries `memberNumber` only, and the backend `MemberLookupClient` never touches the `dependants` table. On top of that, the cost-share code path *does* key on `claim.getDependantId()` under the INDIVIDUAL deductible scope but the transient claim it reads from never has `dependantId` set — so even a hand-crafted dependant quote would silently read the sponsor's accumulator, producing wrong numbers.

This plan closes both gaps: adds an optional `dependantId` to the wire, resolves the sponsor + dependant on the backend, sets `dependantId` on the transient claim, and swaps the frontend picker to `kind="beneficiary"` (the same shape submit-claim and pre-auth already use).

## Current State Analysis

- `EligibilityQuoteRequest.java:21-28` — single `@NotBlank String memberNumber`, no dependant field.
- `MemberLookupClient.java:29-53` — SQL is `SELECT ... FROM members WHERE member_number = :memberNumber`. Dependants table is never touched. Passing a dependant's `member_number` returns empty → controller 404s with `MemberNotFoundException`.
- `EligibilityQuoteService.buildTransientClaim` (`EligibilityQuoteService.java:128-139`) sets `memberId`, `providerId`, `schemeId`, service date, amount, currency, `claimType="QUOTE"`, `status="QUOTE"` — never sets `dependantId`.
- `EligibilityQuoteService.assembleResponse` (`EligibilityQuoteService.java:100-125`) *reads* `claim.getDependantId()` at line 101 to pick between family-vs-individual accumulators. With `dependantId=null` it always reads the family pot (member-level), which is wrong for dependants under INDIVIDUAL scope.
- `EligibilityQuoteService.classifyCoverage` (`EligibilityQuoteService.java:161-177`) only looks at `member.status()`. A dependant terminated on an active member's policy would report `ACTIVE` — incorrect.
- Angular: `eligibility-quote.component.html:27` uses `kind="member"`; `eligibility-quote.component.ts:126-146` handles member picks only; `eligibility-quote.component.ts:210-217` builds a payload with `memberNumber` only.
- Reference implementations for the dependant flow are already in the repo:
  - `SubmitClaimRequest.java:24` — `@NotNull UUID memberId, UUID dependantId` alongside each other.
  - `PreAuthRequest.java:20-30` — same shape; the Javadoc explicitly documents "member is always the sponsoring member; dependantId is optional."
  - `submit-claim.component.ts:99-111,261-281` — canonical frontend beneficiary-picker wiring: unpacks `sel.beneficiary.memberId` + `sel.beneficiary.dependantId`, then `resolveSchemeForMember(sponsorId)`.

Confirmed no test today asserts anything about the dependant path on eligibility-quote (grep for `dependantId` under `services/java/claims-service/src/test/java/**/EligibilityQuote*` returns zero hits).

## Desired End State

- The entity-picker on the eligibility-quote page is `kind="beneficiary"` — operators can search across members and dependants uniformly.
- `EligibilityQuoteRequest` accepts an optional `dependantId` (UUID). The `memberNumber` field keeps its meaning: sponsor's member number when a dependant is quoted, own member number when a member is quoted.
- Backend resolves the sponsor via existing `findByMemberNumber`, resolves the dependant via a new dependant lookup, sets `claim.setDependantId(...)` on the transient claim, uses the *dependant's* status (not just the member's) when classifying coverage, and the accumulator lookup at `EligibilityQuoteService.java:100-104` reads the right pot for INDIVIDUAL scope.
- Audit event includes dependant identity in `newValue` and in the friendly name — never null when a dependant was involved (per `feedback_audit_actor_email` / `feedback_audit_entity_name`).
- One IT that seeds a dependant + sponsor and asserts the quote resolves via the dependant path, plus assertions that the accumulator lookup is keyed on the dependant.

Verification: on a demo tenant with at least one member holding a dependant, opening the page, picking the dependant, and hitting **Get quote** returns a 200 with a coverage classification driven by the dependant's status.

### Key Discoveries

- The `beneficiary` picker kind already threads `memberId + dependantId` through `BeneficiaryPick` (`entity-picker.component.ts:34-44,322-328`). No new frontend picker plumbing needed — it's a straight swap.
- Dependants have their own `member_number` since V036, but the plan does *not* use it on the wire. `memberNumber` on the DTO stays "sponsor's number" so the audit event and the existing member lookup keep their semantics. The dependant is identified by UUID.
- `Claim.dependantId` already exists as a field (`Claim.java:27,171-172`) — no entity change needed.
- `MemberCostShareAccumulatorReader.findFor(memberId, dependantId, schemeId, policyYear)` already handles `dependantId != null` (`MemberCostShareAccumulatorReader.java:28,45-46`) — no change needed there either.
- `feedback_no_raw_id_inputs` applies to *UI inputs*, not wire format; adding `dependantId: UUID` to the DTO is consistent with `SubmitClaimRequest` and `PreAuthRequest` which already do exactly this.

## What We're NOT Doing

- **Not** changing `memberNumber` on the DTO to a UUID or renaming it. Backwards-compatible additive change.
- **Not** adding a dependant search endpoint. The beneficiary search already unifies members + dependants.
- **Not** adding a `dependantNumber` (string) field. Cost of parsing the picker's sublabel or extending `BeneficiaryPick` with a memberNumber field outweighs the payoff — UUID is available for free.
- **Not** touching the N+1 sponsor enrichment in `BeneficiaryController.withSponsor` (`BeneficiaryController.java:76-86`) — flagged in the search research doc as a separate follow-up.
- **Not** broadening the member/dependant search WHERE clauses (a separate follow-up, tracked in `thoughts/shared/research/2026-09-13-entity-picker-search-fields.md`).
- **Not** adding relevance ranking or trigram to the search.

## Implementation Approach

Backend first, additive DTO field so the existing member-only flow keeps working. Frontend second, once the backend accepts the new field. Kafka is not involved — this is a synchronous REST endpoint with an audit-event side-effect.

Rollout: backend can deploy alone (additive, backwards-compatible). Frontend deploys after.

## Phase 1: Backend accepts + resolves dependant

### Overview

Add optional `dependantId` to the request DTO, resolve the dependant from a new lightweight lookup, thread it into `buildTransientClaim`, tighten coverage classification, and enrich the audit event. One IT to guard the wiring.

### Changes Required

#### 1. Request DTO — optional `dependantId`

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/dto/EligibilityQuoteRequest.java`
**Changes**: Add optional `UUID dependantId` after `memberNumber`. No `@NotNull` — nullable is the point.

```java
public record EligibilityQuoteRequest(
        @NotBlank String memberNumber,
        // Optional. When present, the quote is scoped to this dependant of
        // the member named by memberNumber (matches SubmitClaimRequest /
        // PreAuthRequest). Kept as UUID so the wire stays consistent with
        // those DTOs; the beneficiary picker exposes it directly.
        UUID dependantId,
        @NotBlank String serviceCategory,
        @NotEmpty List<@NotBlank String> tariffCodes,
        @NotNull @DecimalMin("0.01") BigDecimal billedAmount,
        @NotBlank @Size(min = 3, max = 3) String currencyCode,
        @NotNull LocalDate dateOfService
) {
}
```

#### 2. Dependant lookup on `MemberLookupClient`

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/client/MemberLookupClient.java`
**Changes**: Add a `findDependantById(UUID)` method returning a lean `DependantSummary` (id, memberId, memberNumber, first/last name, status, dateOfBirth). Reuses the `DatabaseClient` pattern already in the class.

```java
public Mono<DependantSummary> findDependantById(UUID dependantId) {
    if (dependantId == null) return Mono.empty();
    return databaseClient.sql("""
            SELECT id, member_id, member_number, first_name, last_name,
                   status, date_of_birth
              FROM dependants
             WHERE id = :dependantId
             LIMIT 1
            """)
            .bind("dependantId", dependantId)
            .map((row, meta) -> new DependantSummary(
                    row.get("id", UUID.class),
                    row.get("member_id", UUID.class),
                    row.get("member_number", String.class),
                    row.get("first_name", String.class),
                    row.get("last_name", String.class),
                    row.get("status", String.class),
                    row.get("date_of_birth", LocalDate.class)))
            .one();
}

public record DependantSummary(
        UUID id,
        UUID memberId,
        String memberNumber,
        String firstName,
        String lastName,
        String status,
        LocalDate dateOfBirth) {}
```

Do NOT prefix `dependants` with `public.` — this is a tenant-schema table, so unqualified reference is the load-bearing form (per `bug_public_prefix_silent_rollback`).

#### 3. Service — dependant-aware quote flow

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/EligibilityQuoteService.java`
**Changes**:

- `quote(...)` at line 54: branch on `request.dependantId() != null`. When present, resolve the dependant first, validate that its `memberId` matches the sponsor found via `memberNumber`, then thread the dependant summary into `runQuote`.
- Add an overload `runQuote(MemberSummary member, DependantSummary dep, ...)` that carries the dependant into `buildTransientClaim`. Keep the existing `runQuote(MemberSummary member, ...)` as the member-only path.
- `buildTransientClaim` — add a dependant-aware variant that also calls `claim.setDependantId(dep.id())`.
- `classifyCoverage` — when a dependant is present, take the more restrictive of (sponsor status, dependant status). A dependant whose `status ∈ {deactivated, removed, swapped, deceased}` reports `TERMINATED` regardless of the sponsor's `ACTIVE`.
- `publishQuoteAudit` — add `dependantId`, `dependantMemberNumber`, `dependantName` to `newValue`. Friendly name becomes `"Eligibility quote for {sponsor member number} dep {dependant name} ({dateOfService})"` when a dependant is set.

Sketch (new bits only):

```java
public Mono<EligibilityQuoteResponse> quote(EligibilityQuoteRequest request,
                                             UUID providerId,
                                             String actorId,
                                             String actorEmail) {
    return memberLookupClient.findByMemberNumber(request.memberNumber())
            .switchIfEmpty(Mono.error(new MemberNotFoundException(request.memberNumber())))
            .flatMap(member -> resolveDependant(request, member)
                    .flatMap(dep -> runQuote(member, dep, request, providerId))
                    .switchIfEmpty(Mono.defer(() -> runQuote(member, request, providerId))))
            .flatMap(response -> publishQuoteAudit(request, providerId, response, actorId, actorEmail)
                    .thenReturn(response));
}

private Mono<DependantSummary> resolveDependant(EligibilityQuoteRequest request, MemberSummary member) {
    if (request.dependantId() == null) return Mono.empty();
    return memberLookupClient.findDependantById(request.dependantId())
            .switchIfEmpty(Mono.error(new DependantNotFoundException(request.dependantId())))
            .flatMap(dep -> {
                if (!member.id().equals(dep.memberId())) {
                    return Mono.error(new IllegalArgumentException(
                            "Dependant " + dep.id() + " does not belong to member " + member.memberNumber()));
                }
                return Mono.just(dep);
            });
}
```

The `switchIfEmpty(Mono.defer(...))` guarantees the member-only path still runs when `dependantId` is null. `DependantNotFoundException` extends `NoSuchElementException` (same pattern as `MemberNotFoundException` at line 246-250) so the existing 404 mapping picks it up.

#### 4. Controller — no change needed

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/controller/EligibilityQuoteController.java`
**Changes**: none — the controller passes the DTO straight to the service and Spring already binds the new optional field.

#### 5. Swagger

**File**: `EligibilityQuoteRequest.java` (annotations) + any OpenAPI docs the controller emits.
**Changes**: `@Schema(description = "...")` on `dependantId` so `/swagger-ui` reflects the new optional field. No new endpoint, so no route-level changes.

### Success Criteria

#### Automated Verification

- [x] `cd services/java && ./gradlew :claims-service:build` — compiles clean.
- [x] `make test-java` — no regressions on the existing `EligibilityQuoteServiceTest` (member-only path). 10 tests pass (5 pre-existing + 5 new dependant tests).
- [x] `make test-integration` — new slice IT `MemberLookupClientDependantIT` passes (3 tests, replaces the originally-planned full-stack IT — see Deviations). Full stack coverage is provided by unit tests + Phase 2 manual verification.
- [x] Existing member-only IT unchanged and passing (full claims-service suite green — 53 test classes, no failures).
- [ ] `/swagger-ui` on port 8083 renders `dependantId` as optional on `EligibilityQuoteRequest`.

#### Manual Verification

- [ ] `curl -X POST http://localhost:8083/api/v1/eligibility-quote` with `{ memberNumber, dependantId, ... }` returns a JSON quote whose `coverage` reflects the dependant status.
- [ ] Same curl without `dependantId` still returns the member-level quote (no regression).
- [ ] `curl` with a mismatched pair (dependant belongs to a different member) returns a 400 with a legible detail message.

**Implementation Note**: after this phase's automated verification passes, pause for manual verification before starting Phase 2.

---

## Phase 2: Frontend swaps picker to beneficiary + wires dependant

### Overview

Change the entity-picker on the eligibility-quote page to `kind="beneficiary"`, track `sponsorMemberNumber + dependantId` in the component state, and include `dependantId` in the payload. Reuses the exact wiring shape submit-claim already has.

### Changes Required

#### 1. TS service DTO

**File**: `clients/angular/src/app/core/services/eligibility-quote.service.ts`
**Changes**: Add optional `dependantId?: string` to `EligibilityQuoteRequest` (line 10-18). No shape change on the response.

```ts
export interface EligibilityQuoteRequest {
  memberNumber: string;
  /** Optional. When present, the quote is scoped to this dependant of
   *  the member named by memberNumber. UUID (matches backend). */
  dependantId?: string;
  serviceCategory: string;
  tariffCodes: string[];
  billedAmount: string;
  currencyCode: string;
  dateOfService: string;
}
```

#### 2. Component state + picker swap

**File**: `clients/angular/src/app/pages/tenant/claims/eligibility-quote/eligibility-quote.component.{ts,html}`
**Changes**:

- Rename `memberId` state to `beneficiaryId` (matches submit-claim's naming, `submit-claim.component.ts:109`).
- Add:
  - `sponsorMemberId: string | null = null`
  - `sponsorMemberNumber: string | null = null`
  - `dependantId: string | null = null`
  - `pickedLabel: string | null = null` (for display below the picker)
  - `pickedKind: 'MEMBER' | 'DEPENDANT' | null = null`
- Replace `onMemberPicked(sel)` with `onBeneficiaryPicked(sel: EntityPickerSelection | null)`. When `sel.beneficiary?.kind === 'DEPENDANT'`, set `sponsorMemberId = sel.beneficiary.memberId`, `sponsorMemberNumber = sel.beneficiary.sponsorMemberNumber`, `dependantId = sel.beneficiary.dependantId`, `pickedLabel = "{first last}"`. When `MEMBER`, set the sponsor fields to the member's own id/number and clear `dependantId`. See `submit-claim.component.ts:261-281` for the exact shape.
- HTML at line 27 — change `kind="member"` to `kind="beneficiary"`, change the placeholder to `"Search member or dependant…"` (or leave the picker's default which is already that), swap `(selected)` handler name.
- Add a small "picked" line below the picker showing e.g. `Sponsor {sponsorMemberNumber} · Dependant {dependantName}` when `pickedKind === 'DEPENDANT'`, or `Member {memberNumber}` when `pickedKind === 'MEMBER'`.
- `submit()` at line 200: use `sponsorMemberNumber` for `memberNumber` and thread `dependantId` when set:

```ts
const request: EligibilityQuoteRequest = {
  memberNumber: this.sponsorMemberNumber!,
  dependantId: this.dependantId ?? undefined,
  serviceCategory: this.form.serviceCategory,
  tariffCodes: codes,
  billedAmount: this.form.billedAmount,
  currencyCode: this.form.currencyCode,
  dateOfService: this.form.dateOfService,
};
```

- Validation at line 202: `if (!this.sponsorMemberNumber) { this.toast.warning('Pick a member or dependant'); return; }`.

#### 3. Response header copy

**File**: `eligibility-quote.component.html`
**Changes**: The Quote result heading currently says only "Quote result". No changes required — the picked-line above already tells the operator whether the quote is for the member or a dependant.

### Success Criteria

#### Automated Verification

- [x] `cd clients/angular && npx tsc -p tsconfig.app.json --noEmit` — clean.
- [x] `cd clients/angular && ng build --configuration development` — no template errors on the eligibility-quote surface (pre-existing NG8107 warnings elsewhere untouched).
- [ ] If a component spec exists (grep confirms no spec today) skip; otherwise add one with `provideHttpClientTesting()` and mock the beneficiary picker's `(selected)` output.

#### Manual Verification

- [ ] `make web` (port 5100), navigate to `/tenant/claims/eligibility-quote`. Pick a member — verify the quote runs. Pick a dependant — verify the quote runs and the `coverage` chip reflects dependant status.
- [ ] Network tab: for a dependant pick, request body contains `dependantId` (UUID). For a member pick, request body has no `dependantId` (either absent or null).
- [ ] `curl` the deployed backend with the exact payload the Angular sent — same response as the browser (sanity check).
- [ ] Cross-check on a member who has both an active and a terminated dependant: quoting the terminated dependant reports `TERMINATED`; quoting the active one reports `ACTIVE`; quoting the member reports `ACTIVE`.

---

## Testing Strategy

### Unit Tests
- `EligibilityQuoteServiceTest` — extend with two new test methods: `quoteResolvesDependantAndSetsDependantIdOnTransientClaim`, `quoteRejectsMismatchedDependantMember` (400 path).
- Cover `classifyCoverage` for the dependant-terminated / sponsor-active combination.

### Integration Tests (Testcontainers slice)
- `EligibilityQuoteDependantIT` under `services/java/claims-service/src/test/java/...` — Testcontainers Postgres, Flyway applies tenant migrations, seeds a member + dependant + scheme + cost-share config, POSTs the quote, asserts response + audit event + (optionally) that the accumulator row read carries `dependant_id`.
- Reuse the existing quote IT harness (find `EligibilityQuote*IT` under the test root; if none, follow the shape used by `AbstractClaimsReportIT`).
- Testcontainers pitfalls apply: BOM override to 1.21.4, `flyway-database-postgresql`, stub `ReactiveJwtDecoder` (per `infra_testcontainers_pitfalls`).

### Manual Testing Steps
1. Boot infra + claims-service + user-service + gateway + angular (`make infra && make claims && make user && make gateway && make web`).
2. Log in as a tenant admin who has at least one member with a dependant.
3. Visit `/tenant/claims/eligibility-quote`. Pick a member; verify quote runs. Pick a dependant; verify quote runs.
4. Verify audit tail (`/tenant/admin/audit` or the Kafka topic) shows the quote-issued event with dependant fields populated on the DEPENDANT run.

## Performance Considerations

The dependant lookup adds one more `SELECT ... LIMIT 1` to the hot path when `dependantId` is present. Same shape as `findByMemberNumber` — negligible. No N+1 introduced.

## Migration Notes

- No Flyway migration. Purely additive DTO + service change on claims-service.
- The wire is backwards-compatible: existing consumers that don't send `dependantId` see no behavior change.

## Rollout & Rollback

- Deploy claims-service first (additive). No consumer breakage: nothing consumes this endpoint from another service today.
- Deploy Angular after. If the frontend is rolled back independently, the backend still works — old frontend sends `memberNumber` only, gets the member quote path.

## References

- Research: `thoughts/shared/research/2026-09-13-entity-picker-search-fields.md` (adjacent search-broadening research, not implemented by this plan).
- Wire pattern references: `services/java/claims-service/src/main/java/com/medfund/claims/dto/SubmitClaimRequest.java:24`, `PreAuthRequest.java:20-30`.
- Frontend wiring reference: `clients/angular/src/app/pages/tenant/claims/submit/submit-claim.component.ts:99-111,261-281`.
- Auto-memory bugs to honour: `bug_public_prefix_silent_rollback` (unqualified tenant tables), `bug_r2dbc_pre_populated_id_update_mode` (n/a here — no INSERT), `feedback_audit_actor_email`, `feedback_audit_entity_name` (both apply to the audit-event enrichment).
