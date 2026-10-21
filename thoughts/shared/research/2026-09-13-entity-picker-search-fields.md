---
date: 2026-09-13T15:21:27+02:00
researcher: Methuseli
git_commit: c97eb875bb84409598fa03078b57fd75a33d1304
branch: rename-adjustments-to-notes
repository: medfund
topic: "Broaden entity-picker searches beyond name — support member/dependant numbers and provider identifiers (AHFOZ etc.)"
tags: [research, codebase, angular, user-service, entity-picker, search]
status: complete
last_updated: 2026-09-13
last_updated_by: Methuseli
---

# Research: Broaden entity-picker searches beyond name

**Date**: 2026-09-13T15:21:27+02:00 · **Researcher**: Methuseli · **Commit**: c97eb875 · **Branch**: rename-adjustments-to-notes

## Research Question

The provider, member and dependant searches should not only be by name. We should be able to search by member or dependant number, and by provider identifiers like AHFOZ number.

## Summary

**Most of what the user is asking for is already implemented in the backend — the UI just doesn't advertise it.** The Angular entity-picker sends a single free-text `q` to the same three endpoints today, but the SQL on each of those endpoints already ORs the query against a number-like column:

| Kind | Backend SQL matches | Not matched today |
|---|---|---|
| Member (`/api/v1/members/search`) | `first_name`, `last_name`, `member_number` | `national_id`, `email`, `phone` |
| Beneficiary — member half (`/api/v1/beneficiaries/search`) | same as above | same as above |
| Beneficiary — dependant half | `first_name`, `last_name`, `member_number` on `dependants` | `national_id` |
| Provider (`/api/v1/providers?q=` and `/api/v1/providers/search`) | `name`, `registration_number` | everything else (see AHFOZ note below) |

The user's ask breaks into three pieces:

1. **Member / dependant number search** — already works end-to-end. Fix is UI-only: change the entity-picker's `'member'` and `'beneficiary'` placeholders to advertise the number (`"Search by name or member number…"`). Two callers already do this by hand (CTC pages) but the shared default doesn't. See [Findings § Angular](#angular--entity-picker).
2. **Provider identifiers (AHFOZ etc.)** — already works because AHFOZ numbers are deliberately stored in the same `registration_number` column that the query hits. Documented in `Provider.java:29-34`. Again, UI-only fix. **But** there's an orphaned column (see next point) worth surfacing as a follow-up.
3. **Data-integrity landmine — the orphan `ahfoz_number` column.** `public.providers.ahfoz_number` still exists physically (V105) but was superseded by `registration_number` in V106. Nothing in Java reads or writes it, and no migration copied data from the old column to the new one. Any tenant that seeded providers between V105 apply and V106 apply — or any external system that still writes to the old column — has AHFOZ numbers stranded in a column the search will never hit. Recommended as a separate follow-up plan.

Optional stretch: `national_id` on the member search WHERE clause. Not asked for by name in the ticket, but common ask; cheap to add if we're already there.

## Findings

### Angular — entity picker

Component: `clients/angular/src/app/shared/components/entity-picker/entity-picker.component.ts`.

- `search(term)` dispatches per kind at lines 256-333. Every branch collapses the operator's typed text into a single `term` string and passes it as the sole `q`.
- Kind → service call:
  - `'provider'` → `providersService.query({ q: term, size: 10 })` (`entity-picker.component.ts:259`; service at `providers.service.ts:49-95`, hits `GET /api/v1/providers?q=&size=10`)
  - `'member'` → `membersService.searchByName(term)` (`entity-picker.component.ts:269`; service at `members.service.ts:98-99`, hits `GET /api/v1/members/search?q=`)
  - `'beneficiary'` → `membersService.searchBeneficiaries(term)` (`entity-picker.component.ts:309`; service at `members.service.ts:108-109`, hits `GET /api/v1/beneficiaries/search?q=`)
- Placeholder defaults live in `defaultPlaceholder()` at `entity-picker.component.ts:335-344`: `"Search by member name…"`, `"Search by provider name…"`, `"Search member or dependant…"`. None mention numbers.
- Two callers already override the copy manually — `clients/angular/src/app/pages/tenant/claims/ctc/ctc-add.component.html:26` and `clients/angular/src/app/pages/tenant/finance/ctc/ctc-payment-form.component.html:26` both use `"Search by member name or number…"`. So the UX expectation exists in one corner of the app but isn't the default.
- The stand-alone member-lookup and tenant-users list inputs (not the entity-picker) already show `"Search by name or member number…"` on `tenant-users.component.html:106` and `users.component.html:107`.

Full list of member-picker callers that would inherit any placeholder change:

- `clients/angular/src/app/shared/components/entity-picker/entity-picker.component.ts:269` (default)
- `clients/angular/src/app/shared/components/liaison-picker/liaison-picker.component.ts:229`
- `clients/angular/src/app/pages/tenant/claims/lookups/member-lookup.component.ts:43`
- `clients/angular/src/app/pages/tenant/billing/transactions/transaction-form.component.ts:150`
- `clients/angular/src/app/pages/tenant/billing/charge-preview/charge-preview.component.ts:261`
- `clients/angular/src/app/pages/tenant/billing/ledger/ledger.component.ts:159`

Providers.service has a stale client-side fallback (`providers.service.ts:63-95`) that filters an array by `name || registrationNumber` — this still fires when the backend returns a plain array (which it no longer does for `?q=`, but the code hasn't been cleaned up). Independently worth pruning but not in scope.

### Backend — `/api/v1/members/search`

- Controller: `services/java/user-service/src/main/java/com/medfund/user/controller/MemberController.java:98-102` — bare `@RequestParam String q`, class-level `@RequestMapping("/api/v1/members")` at line 28. Trivially extensible without breaking callers.
- Service: `MemberService.search(String query)` at `services/java/user-service/src/main/java/com/medfund/user/service/MemberService.java:124-126` — pure passthrough (no trim, no min-length, no status filter, no LIMIT).
- Repository: `MemberRepository.search` at `services/java/user-service/src/main/java/com/medfund/user/repository/MemberRepository.java:46-47`:
  ```sql
  SELECT * FROM members
   WHERE LOWER(first_name)  LIKE LOWER(CONCAT('%', :query, '%'))
      OR LOWER(last_name)   LIKE LOWER(CONCAT('%', :query, '%'))
      OR member_number      LIKE CONCAT('%', :query, '%')
   ORDER BY last_name, first_name
  ```
  Case-insensitive on names; case-sensitive on `member_number`. `national_id`, `email`, `phone` not touched.
- Paginated cousin at `MemberRepository.java:78-97` (used by `GET /api/v1/members?q=`) — same WHERE clause.

### Backend — `/api/v1/beneficiaries/search` (unified members + dependants)

- Controller: `services/java/user-service/src/main/java/com/medfund/user/controller/BeneficiaryController.java:47-67`. Blank-`q` guard at line 53 (`return Flux.empty()`). Cap `MAX_RESULTS = 20` (line 36); each half `.take(20)` then the concat `.take(20)` again — so if 20 members already match, no dependants surface at all. Members come first because of `Flux.concat(memberFlux, dependantFlux)`.
- No service layer — controller talks straight to `MemberRepository` and `DependantRepository` (constructor-injected at 41-45).
- Members side: reuses `MemberRepository.search` above.
- Dependants side: `DependantRepository.search` at `services/java/user-service/src/main/java/com/medfund/user/repository/DependantRepository.java:25-32`:
  ```sql
  SELECT * FROM dependants
   WHERE LOWER(first_name)  LIKE LOWER(CONCAT('%', :query, '%'))
      OR LOWER(last_name)   LIKE LOWER(CONCAT('%', :query, '%'))
      OR member_number      LIKE CONCAT('%', :query, '%')
   ORDER BY last_name, first_name
  ```
  Same shape. `dependants.member_number` was made mandatory (`NOT NULL`) in V036, so this is a load-bearing search column now.
- N+1 enrichment: for each dependant hit, `BeneficiaryController.withSponsor` (lines 76-86) issues a `memberRepository.findById(d.getMemberId())` to attach sponsor name / member number. Not in scope but worth noting for a later perf follow-up.
- No dedicated `/api/v1/dependants/search` exists — `DependantController` only exposes `member/{memberId}`, id-lookup, and CRUD (`services/java/user-service/src/main/java/com/medfund/user/controller/DependantController.java:35-95`).

Entity fields:

- `Member` — `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java`: `member_number` (19-20), `first_name`/`last_name` (22-26), `national_id` (33-34), `email` (36), `phone` (38), `keycloak_user_id` (48-49).
- `Dependant` — `services/java/user-service/src/main/java/com/medfund/user/entity/Dependant.java`: `member_number` (34-35), `first_name`/`last_name` (37-41), `national_id` (50-51), `member_id` FK (19-20). No email/phone on dependants.

### Backend — `/api/v1/providers?q=` and `/api/v1/providers/search`

Both endpoints live on `services/java/user-service/src/main/java/com/medfund/user/controller/ProviderController.java`:

- `GET /api/v1/providers?q=&status=&providerType=&page=&size=` (lines 35-49) — paged; empty strings normalized to null; returns `ProviderPage`.
- `GET /api/v1/providers/search?q=` (lines 73-77) — flat unpaged `Flux<ProviderResponse>`.

Repository queries at `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderRepository.java`:

- `searchPage` (37-46) and `countSearch` (48-55) share a WHERE:
  ```sql
  WHERE (:q IS NULL OR LOWER(name) LIKE LOWER(CONCAT('%', :q, '%'))
         OR LOWER(registration_number) LIKE LOWER(CONCAT('%', :q, '%')))
    AND (:status IS NULL OR status = :status)
    AND (:providerType IS NULL OR provider_type = :providerType)
  ```
- `search(String query)` (25-26) — same `LOWER(name) LIKE` OR `LOWER(registration_number) LIKE`.

Provider entity (`services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java`) is table-mapped `@Table(schema = "public", value = "providers")` — provider registry lives on the **public schema**, not per-tenant. Fields: `id`, `name`, `providerType`, `registrationNumber` (36; Javadoc 29-34), `specialty`, `email`, `phone`, `city`, `address`, `bankingDetails`, `keycloakUserId`, `status`, `networkTier`, timestamps, actor fields. No `tin`, `npi`, `license_number`, `code`, `accreditation_number`.

The Javadoc on `registrationNumber` at `Provider.java:29-34` says explicitly:

> Generic registration / licence / AHFOZ number... AHFOZ number for health-insurance tenants, workshop licence for motor. The tenant's `settings.providerRegLabel` controls the UI label.

So the backend intent is clear: **`registration_number` IS where the AHFOZ number lives**. The search already hits it.

### The orphan `ahfoz_number` column

- Defined in `services/java/tenancy-service/src/main/resources/db/migration/public/V105__providers.sql` line 13 as `ahfoz_number VARCHAR(50)`, alongside `practice_number VARCHAR(100)`.
- V106 (`services/java/tenancy-service/src/main/resources/db/migration/public/V106__provider_registration_number.sql`) renamed `practice_number` → `registration_number` — deliberately generalizing it.
- No later migration ever drops or backfills `ahfoz_number`. It still exists on the table.
- Nothing in the Java code reads or writes `ahfoz_number`. `grep -r "ahfoz_number"` under `services/java/user-service/src/main` returns no hits in Java sources.
- Also present in the (now unused for provider registry) tenant baseline `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:66`.

Implication: any tenant onboarded before V106 who wrote AHFOZ numbers to `ahfoz_number` has them stranded. The current search will not find them.

## Architecture doc vs. code

`.claude/adjudication.md` and `.claude/portals.md` describe provider identifiers only in passing ("providers have a registration number / AHFOZ code / practice licence"). They don't specify search-field composition; the V106 rename (`practice_number` → `registration_number`) is a code-only decision and the docs still name "AHFOZ number" as if it were a distinct field. This is a mild drift, not a blocker: the Provider.java Javadoc is authoritative and the docs read consistently with treating `registration_number` as a generic identifier slot.

No conflict with the 9 Critical Rules — this is a UI-copy + optional-broadening change; no currency, no tenant scoping, no AI, no Kafka, no auditability implications for the search endpoints themselves.

## Code References

- `clients/angular/src/app/shared/components/entity-picker/entity-picker.component.ts:256-344` — kind-dispatched search + placeholder defaults
- `clients/angular/src/app/core/services/members.service.ts:98-109` — `searchByName` + `searchBeneficiaries` wire calls
- `clients/angular/src/app/core/services/providers.service.ts:49-95` — `query({q, size})` + stale array-branch fallback
- `services/java/user-service/src/main/java/com/medfund/user/controller/MemberController.java:98-102` — `/search`
- `services/java/user-service/src/main/java/com/medfund/user/controller/BeneficiaryController.java:47-86` — beneficiary `/search` + sponsor enrichment
- `services/java/user-service/src/main/java/com/medfund/user/controller/DependantController.java:35-95` — no `/search` handler
- `services/java/user-service/src/main/java/com/medfund/user/controller/ProviderController.java:35-77` — paged `?q=` + flat `/search`
- `services/java/user-service/src/main/java/com/medfund/user/repository/MemberRepository.java:46-47,78-97` — member search WHERE
- `services/java/user-service/src/main/java/com/medfund/user/repository/DependantRepository.java:25-32` — dependant search WHERE
- `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderRepository.java:25-55` — provider search WHERE (`name` OR `registration_number`)
- `services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:29-34,36` — `registration_number` Javadoc naming AHFOZ explicitly
- `services/java/tenancy-service/src/main/resources/db/migration/public/V105__providers.sql:13` — orphan `ahfoz_number` column origin
- `services/java/tenancy-service/src/main/resources/db/migration/public/V106__provider_registration_number.sql` — rename that made it orphan

## Architecture Insights

- **Registration-number-as-generic-slot** is a deliberate design choice codified in V106 and Provider.java's Javadoc. Anywhere the UI still says "AHFOZ number" is a candidate for line-neutral relabelling ("Registration / licence / AHFOZ number", or configurable via `settings.providerRegLabel` per that same Javadoc) — matches the "line-neutral wording" framing rule in `.claude/CLAUDE.md`.
- **No relevance ranking.** All four search queries are plain substring `LIKE '%q%'`. That means `MBR-000201` and `Mary` both take the same code path, and short strings ("21") will fire noisy substring matches. Not asked for, but worth flagging: a `startsWith` or prefix boost or `pg_trgm` upgrade would be a natural follow-up if UX complaints start showing up around a member with a 6-digit number matching everything.
- **Providers live on `public.providers`, not per-tenant.** V105 places providers on the platform-wide public schema. This is consistent with the `bug_public_prefix_silent_rollback` memory only if the query is against `public.providers` explicitly — which the entity is, via `@Table(schema = "public", value = "providers")`. Cross-tenant provider visibility is intentional; per-tenant scoping happens further up the stack (network membership, tariff schedules, etc.).
- **N+1 sponsor enrichment on beneficiaries.** Each dependant hit fires a separate `findById` on the sponsor member. At the 20-row cap this is bounded but wasteful; a repository-level JOIN would flatten it. Not in scope for this ticket — follow-up worth logging.
- **Beneficiary result ordering is member-first + hard cap.** With 20 member matches, dependants never surface, even if a dependant would be a better hit for a numeric search. If we want number-search parity between the two halves, this ordering may need to change (interleave, or rank by column-match priority) — flagged as a decision point.
- **No test coverage on the search WHERE clauses.** Confirmed via grep: `MemberControllerTest` has no `/search` coverage; `BeneficiaryControllerTest` mocks both repos, so the `member_number` clause could be dropped tomorrow and no test would fail. Any plan that touches these queries needs to land a repository-level integration test alongside the change.

## Historical Context (from thoughts/shared/)

None directly relevant. The auto-memory index does not have entries on entity-picker search fields. Adjacent memories worth being aware of:

- `feedback_no_raw_id_inputs` — the reason the entity-picker exists at all (never expose raw UUIDs to operators). Reinforces that the picker is the right place to broaden, not by adding a second UUID-search input.
- `bug_public_prefix_silent_rollback` — only relevant if a future change moves the provider search into a claims/contributions-service query that references `public.providers`; the current search runs inside user-service against its own tables, so this is not a live concern.

## Open Questions

- Should the beneficiary search interleave members and dependants when the query is numeric, or is the current member-first ordering acceptable?
- Should we broaden the member search to include `national_id` and/or `email` while we're touching it? The user didn't ask, but "member number" and "national ID" are often the same mental class for operators.
- What's the plan for the orphaned `public.providers.ahfoz_number` column — leave it, add a UNION-style OR to the WHERE as a defensive net, backfill+drop in a new migration, or accept it as dead data? Recommend a separate follow-up plan; the search fix shouldn't wait on it.
- Do we want to trim the stale array-branch fallback in `providers.service.ts:63-95` at the same time? Independent cleanup but low risk.
