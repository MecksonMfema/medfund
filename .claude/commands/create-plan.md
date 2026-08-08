---
name: create-plan
description: Create a detailed, code-level implementation plan in thoughts/shared/plans through interactive research and iteration. Step 2 of the RPI loop — it consumes one or more research docs, plus a ticket or spec, plus any steer in plain words. It only plans; building the plan belongs to the implement-plan skill.
argument-hint: "<one or more research doc / ticket / spec paths, in any combination, plus any extra steer in plain words>"
model: opus
---

# Create Plan

You are creating a detailed implementation plan through an interactive, iterative process. Be skeptical,
be thorough, and work collaboratively with the user to produce a high-quality technical plan.

An InsureFlow plan sits **below** the intent artifacts and **above** the code:

```
  ticket  |  internal spec                                ← intent: what & why (PM / lead)
                          │
              ── a developer picks it up ──
                          ↓
  research-codebase  →  ⌧ clear  →  create-plan  →  ⌧ clear  →  implement-plan
      (findings)                     (this skill)                   (builds it)
```

A **spec** or **ticket** says *what* should be true and *why*, deliberately code-free. This plan says
***how***, concretely — named files, named symbols, and the actual code for the changes that matter,
broken into phases each of which can be verified on its own. That is the whole reason this skill exists:
the plan is where the design argument gets settled at code altitude, in a document that is cheap to
revise, instead of mid-implementation in a diff that is expensive to unwind.

`implement-plan` is the only implementer — plans are what it consumes.

## Initial Response

### Reading the input

The input is **a set**, in any combination and any order:

- **Research docs** (`thoughts/shared/research/…`) — zero, one, or several. Several is normal: a ticket
  may have been researched more than once, or across more than one service.
- **A ticket or spec** (`thoughts/shared/tickets/…`, `thoughts/shared/specs/…`, or a slug) — the intent
  artifact this plan serves.
- **A steer** — any plain-words text the dev typed alongside the paths ("keep the multi-currency
  conversion out of scope for phase 1", "reuse the existing outbox publisher", "start with the Java side
  first"). Treat it as an instruction, not as prose to skim past. See *Handling the steer* below.

Read every referenced file **fully** (no limit/offset) in the main context before launching anything.

**If no input was supplied**, respond with:

```
I'll help you create a detailed implementation plan. Let me start by understanding what we're building.

Please provide:
1. The research — one or more docs from `thoughts/shared/research/`. If you haven't researched yet,
   say so and I'll offer to run it first.
2. The ticket or spec this plan serves (`thoughts/shared/tickets/…` or `thoughts/shared/specs/…`)
3. Any steer in plain words — scope you want cut, a component you want reused, where to start

I'll analyze this and work with you to produce a complete plan.

Tip: pass them all at once —
  create-plan thoughts/shared/research/2026-08-08-provider-payout-batching.md \
              thoughts/shared/tickets/finance-provider-payout.md \
              "keep the multi-currency conversion out of scope for phase 1"
```

Then wait for the user's input.

### If no research doc was supplied

This skill is **step 2** of Research → Plan → Implement. A plan built without research is a plan built on
whatever the ticket happened to say, which is exactly what the loop exists to prevent. So when the input
contains no research doc, ask once:

```
No research doc supplied. The loop is research → clear context → plan, and step 1 is where the
expensive investigation happens once and lands in a file.

Want me to run `research-codebase` on this first? (Recommended — you'd clear context and come back
with the doc.) Or shall I proceed and research inline?
```

Default to running research. Take "proceed" for an answer without arguing — a two-line fix doesn't need
the full loop — but **record the outcome in the plan's frontmatter** (see the template). That record is
how the bypass rate gets measured; don't skip it and don't soften the reason the user gave.

### Handling the steer

A steer changes what gets built, and it lives only in a context that is about to be cleared. So:

- **Record it verbatim** in the plan's frontmatter — the dev's words, not your paraphrase.
- **A steer that adds** (emphasis, a constraint the ticket is silent on, an ordering preference) — apply
  it, no ceremony.
- **A steer that contradicts a Decision in the ticket or spec** — STOP and ask, before drafting:

  ```
  Your steer defers the multi-currency conversion. The ticket's Decisions has it in MVP scope
  (thoughts/shared/tickets/finance-provider-payout.md).

  Which is binding — and should I note the ticket as stale?
  ```

  The test is written, not felt: **if the ticket or spec says otherwise in writing, it's a contradiction;
  if it's silent, it's an addition.**
- **When the steer wins, the plan says so** — a `## Scope changes from the ticket` section naming what
  changed and why. The PR reviewer checks the diff against the ticket; without that section the
  divergence reads as sloppiness rather than a decision.

## Process Steps

### Step 1: Context Gathering & Initial Analysis

1. **Read all mentioned files immediately and FULLY**:
   - Specs (`thoughts/shared/specs/<slug>.md`) and tickets (`thoughts/shared/tickets/<slug>.md`)
   - Research documents (`thoughts/shared/research/…`)
   - Related plans, and any data files mentioned
   - **IMPORTANT**: read entire files — never pass limit/offset
   - **CRITICAL**: do NOT spawn exploration passes before reading these yourself in the main context
   - **NEVER** read a mentioned file partially

   Read the `.claude/*.md` architecture doc that matches the work, too — pick from:

   | Work area | Doc |
   |---|---|
   | Overall system design, service boundaries | `.claude/architecture.md` |
   | Language versions, libraries, rationale | `.claude/tech-stack.md` |
   | Phased build plan, ordering | `.claude/migration-strategy.md` |
   | AI/ML integration points | `.claude/ai-integration.md` |
   | Currency handling, FX, financial precision | `.claude/multi-currency.md` |
   | Schema-per-tenant, tenant resolution, per-tenant rules | `.claude/multi-tenancy.md` |
   | Claims adjudication pipeline, tariffs, ICD-10, AHFOZ | `.claude/adjudication.md` |
   | Visual rule builder, rule categories, DRL compilation | `.claude/rules-engine.md` |
   | Online payments, provider integrations, payouts | `.claude/payments.md` |
   | Deployment, CI/CD, observability, security | `.claude/infrastructure.md` |
   | Per-language conventions, testing, error handling | `.claude/coding-standards.md` |
   | Portal specs (super admin, tenant admin, provider, member, group liaison) | `.claude/portals.md` |

2. **Launch exploration passes — scaled to what the research already covered.** Delegate read-only
   investigation, telling each pass *what* to find, not *how* to search. The passes available are
   capabilities, not fixed tools:
   - a **locator pass** — which files, directories, and components are relevant
   - an **analyzer pass** — how the current implementation actually works
   - a **pattern pass** — similar existing implementations to model after
   - a **thoughts-directory pass** — existing research, specs, plans, or decisions about this area

   Ask each for **file paths, real symbol names, and `file:line` references** — not prose summaries.

   **Scale by kind, not by volume.** If clearing context between research and planning is going to mean
   anything, the expensive sweep must happen *once*. So when research docs were supplied:

   - **Don't re-run what the research answers** — what exists, how it works today, where it lives. Read
     the doc; trust it.
   - **Do run the passes research couldn't have run**, because they're planning questions rather than
     research questions: **who else consumes this**, what tests already cover it, which existing feature
     to model after, what breaks downstream when this changes. A research doc written to answer "how does
     X work" has usually never looked at any of those.
   - **Check freshness cheaply.** Research docs carry `git_commit` in frontmatter. Diff the paths the doc
     cites against today:

     ```bash
     git diff <research_commit>..HEAD --stat -- <paths the doc cites>
     ```

     Nothing moved → the doc is current by construction. Something moved → re-investigate *that*, and say
     so. This beats both blind trust and a blind re-sweep.

   With **no research doc**, there is nothing to scale against: run the passes at full depth, including
   the "what exists / how does it work" sweep.

3. **Read every file the passes identified as relevant** — fully, in the main context. You need complete
   understanding before planning anything.

4. **Analyze and verify understanding**:
   - Cross-reference the spec/ticket requirements against the actual code
   - Identify discrepancies or misunderstandings
   - Note assumptions that need verification
   - Determine the true scope from codebase reality
   - **Validate the premise** — if exploration shows the work already exists or is already fixed, STOP and
     tell the user rather than planning a no-op

5. **Present informed understanding and focused questions**:
   ```
   Based on the ticket and my research of the codebase, I understand we need to [accurate summary].

   I've found that:
   - [Current implementation detail with file:line reference]
   - [Relevant pattern or constraint discovered]
   - [Potential complexity or edge case identified]

   Questions that my research couldn't answer:
   - [Specific technical question that requires human judgment]
   - [Business logic clarification]
   - [Design preference that affects implementation]
   ```

   Only ask what you genuinely cannot answer by reading code. If the input was a spec, its **Decisions**
   section has already settled some of these — honor those as fixed and don't re-litigate them.

### Step 2: Research & Discovery

After the initial clarifications:

1. **If the user corrects a misunderstanding**:
   - Do NOT simply accept the correction
   - Launch new exploration passes to verify the corrected picture
   - Read the specific files or directories they name
   - Only proceed once you have verified the facts yourself

2. **Keep a visible checklist** of the remaining exploration tasks.

3. **Run parallel exploration passes for comprehensive research** — up to 3 concurrently, bounded by
   available capacity, each focused on a different aspect:

   **For deeper investigation:**
   - **locator pass** — find more specific files ("everything that touches provider payout batching")
   - **analyzer pass** — understand implementation details ("how the contributions outbox publishes to
     Kafka")
   - **pattern pass** — find a similar feature already built here to model after

   **For historical context:**
   - **thoughts-directory pass** — prior research, plans, or decisions about this area, then a deep read
     of the most relevant ones

   **Be specific about the service in every prompt.** InsureFlow is a polyglot monorepo — "the UI" is
   ambiguous (Angular admin, Angular member portal, Flutter member app), "the claims flow" is ambiguous
   (Java `claims-service`, Go `gateway`, Angular UI), and "the auth path" can mean Keycloak, the gateway,
   or the shared JWT filter. Name the exact directory:

   | Service or surface | Directory |
   |---|---|
   | Tenancy | `services/java/tenancy-service/` |
   | User & policy | `services/java/user-service/` |
   | Claims adjudication | `services/java/claims-service/` |
   | Contributions & billing | `services/java/contributions-service/` |
   | Finance & payouts | `services/java/finance-service/` |
   | Rules engine (Drools + JSON) | `services/java/rules-engine/` |
   | Java shared (DTOs, InsuranceLine enum, audit helpers) | `services/java/shared/` |
   | Keycloak event listener | `services/java/keycloak-event-listener/` |
   | API gateway | `services/go/gateway/` |
   | Notification service | `services/go/notification-service/` |
   | Audit service | `services/go/audit-service/` |
   | File processing | `services/go/file-service/` |
   | Payment gateway | `services/go/payment-gateway/` |
   | Go shared (Kafka, HTTP helpers) | `services/go/shared/` |
   | Live dashboards | `services/elixir/apps/live_dashboard/` |
   | Chat service | `services/elixir/apps/chat_service/` |
   | AI service | `services/python/ai-service/` |
   | Angular web app | `clients/angular/src/app/` (features under `features/<name>/`) |
   | Angular E2E (Playwright) | `clients/angular/e2e/` |
   | Flutter app | `clients/flutter/` |
   | Infrastructure | `infra/`, `scripts/`, `docker-compose.yml`, `Makefile` |

4. **Wait for ALL passes to complete** before proceeding, then read the load-bearing files yourself —
   pass summaries are starting points, not ground truth.

5. **Present findings and design options**:
   ```
   Based on my research, here's what I found:

   **Current State:**
   - [Key discovery about existing code, with file:line]
   - [Pattern or convention to follow]

   **Design Options:**
   1. [Option A] — [pros/cons]
   2. [Option B] — [pros/cons]

   **Open Questions:**
   - [Technical uncertainty]
   - [Design decision needed]

   Which approach aligns best with your vision?
   ```

   If the solution space is wide or the load-bearing assumptions are shaky — or whenever the user asks to
   be grilled — run the **`grilling` skill** here, before drafting. It is far cheaper to change a design
   under questioning than to change it in Step 4.

### Step 3: Plan Structure Development

Once aligned on the approach:

1. **Propose the outline first**:
   ```
   Here's my proposed plan structure:

   ## Overview
   [1-2 sentence summary]

   ## Implementation Phases:
   1. [Phase name] - [what it accomplishes]
   2. [Phase name] - [what it accomplishes]
   3. [Phase name] - [what it accomplishes]

   Does this phasing make sense? Should I adjust the order or granularity?
   ```

2. **Get feedback on the structure** before writing any detail. A phase is a chunk that can be
   implemented **and verified** as a unit — if a phase can't be verified on its own, it's drawn at the
   wrong boundary. In a polyglot repo, a common bad boundary is "backend then frontend" — split them if
   the backend can be verified via curl and the frontend needs its own UI check, but do not split them
   if the backend change is meaningless without the client that reads it (that phase can't be verified
   on its own).

### Step 4: Detailed Plan Writing

After the structure is approved:

1. **Write the plan** to `thoughts/shared/plans/YYYY-MM-DD-[TICKET-]description.md`
   - `YYYY-MM-DD` — today's date
   - `TICKET-` — optional ticket handle (e.g. `CLAIMS-142-`), omitted entirely if there isn't one
   - `description` — a short kebab-case handle
   - Examples: `2026-08-08-CLAIMS-142-provider-payout-batching.md`, `2026-08-08-tenant-onboarding-flow.md`

2. **Use this template structure**:

````markdown
---
date: [YYYY-MM-DD]
git_commit: [commit the plan was written against]
branch: [branch]
ticket: thoughts/shared/tickets/<slug>.md      # or the spec path; omit if neither
research:                                       # every research doc that grounded this plan
  - thoughts/shared/research/YYYY-MM-DD-<slug>.md
# When no research was done, replace the list with the reason the dev gave, verbatim:
# research: none — "two-line fix, already know the file"
steer: "<the dev's plain-words steer, verbatim — omit the key if there wasn't one>"
services_touched: [claims-service, gateway, angular]   # from the scope map above
status: draft
---

# [Feature/Task Name] Implementation Plan

## Overview

[Brief description of what we're implementing and why]

## Current State Analysis

[What exists now, what's missing, key constraints discovered — with file:line references]

## Desired End State

[A specification of the desired end state after this plan is complete, and how to verify it]

### Key Discoveries:
- [Important finding with `services/java/claims-service/…:123`]
- [Existing pattern or shared component to reuse — e.g. `services/java/shared/audit/AuditActor.java`]
- [Constraint to work within — a tenant-scoping invariant, a Kafka topic contract, a migration ordering]

## What We're NOT Doing

[Explicitly list out-of-scope items to prevent scope creep]

## Scope changes from the ticket   ← only when a steer changed the scope

[What this plan does differently from the ticket or spec it serves, and why. One line each. The PR
reviewer reads the ticket to check what was promised — this is where they find out it moved.]

## Implementation Approach

[High-level strategy and reasoning — including which services are touched in what order, and how the
Kafka contracts between them stay backwards-compatible during the rollout.]

## Phase 1: [Descriptive Name]

### Overview
[What this phase accomplishes]

### Changes Required:

#### 1. [Component/File Group]
**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/PayoutService.java`
**Changes**: [Summary of changes]

```java
// Specific code to add/modify — follow the Java conventions in .claude/CLAUDE.md
// (@Slf4j, @RequiredArgsConstructor for services, @Getter @Setter (not @Data) for R2DBC entities,
//  record for DTOs, @Builder + @Getter for complex value objects).
```

#### 2. [Schema change if any]
**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V0NN__…sql`
**Changes**: [Summary — add columns, indexes, seed data]

```sql
-- Idempotent DDL. Never edit an applied migration — write a new higher-numbered file
-- (see feedback_never_edit_applied_migrations in the auto-memory).
```

### Success Criteria:

#### Automated Verification:
- [ ] Java compiles clean: `cd services/java && ./gradlew :claims-service:build`
- [ ] Unit tests pass: `make test-java` (or `cd services/java && ./gradlew :claims-service:test`)
- [ ] Integration tests pass: `make test-integration`
- [ ] Tenant migration applies against a fresh testcontainer (covered by the IT harness)
- [ ] Swagger renders the new/changed endpoint at `http://localhost:8083/swagger-ui`
- [ ] Kafka event schema is valid — [name the schema check or the consumer test that guards it]

#### Manual Verification:
- [ ] Trigger the new flow end-to-end via the Angular admin (the specific URL + interaction)
- [ ] Edge case that has to be exercised by hand
- [ ] No regressions in [the specific adjacent surface at risk]

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm
the manual testing before moving to the next phase.

---

## Phase 2: [Descriptive Name]

[Same structure, with both automated and manual success criteria...]

---

## Testing Strategy

### Unit Tests:
- [What to test]
- [Key edge cases]

### Integration Tests (Testcontainers slices):
- [End-to-end scenarios covered by *IT under services/java]
- [Reactor-Kafka consumer round-trips]

### E2E Tests (Playwright, clients/angular/e2e):
- [User journey verified in a real browser]

### Manual Testing Steps:
1. [Specific step to verify the feature]
2. [Another verification step]
3. [Edge case to test by hand]

## Performance Considerations

[Query shape (R2DBC), N+1 risk on reactive joins, index coverage, Kafka consumer lag,
Angular bundle-size impact, memory footprint per service.]

## Migration Notes

[Flyway ordering, tenant-schema vs public-schema migration boundary
(see bug_public_prefix_silent_rollback and bug_public_flyway_history_load_bearing in auto-memory),
backfill of existing rows, whether a Kafka topic recompact is needed, per-tenant rules-engine
recompilation, whether a Keycloak realm change has to be pushed.]

## Rollout & Rollback

[Which services deploy first (usually Kafka producers before consumers for additive changes;
the reverse for removals). How to revert without leaving orphan events or schema drift.]

## References

- Ticket: `thoughts/shared/tickets/<slug>.md`
- Spec: `thoughts/shared/specs/<slug>.md`
- Related research: `thoughts/shared/research/YYYY-MM-DD-<slug>.md`
- Architecture doc: `.claude/<topic>.md`
- Similar implementation: `services/…:123`
````

### Step 5: Review

1. **Present the draft plan location**:
   ```
   I've created the implementation plan at:
   `thoughts/shared/plans/YYYY-MM-DD-[TICKET-]description.md`

   Please review it and let me know:
   - Are the phases properly scoped?
   - Are the success criteria specific enough?
   - Any technical details that need adjustment?
   - Missing edge cases or considerations?
   ```

2. **Iterate on feedback** — be ready to add missing phases, adjust the technical approach, sharpen
   success criteria, or add/remove scope.

3. **Continue refining** until the user is satisfied.

### Step 6: Hand off to implementation

Planning is step 2 of Research → Plan → Implement, and each step runs in its **own context**. The plan
file is what survives the clear — that is the whole reason it exists as a file. So end with:

```
Plan written to thoughts/shared/plans/2026-08-08-CLAIMS-142-provider-payout-batching.md
4 phases, each independently verifiable.

Clear your context, then run:
  implement-plan thoughts/shared/plans/2026-08-08-CLAIMS-142-provider-payout-batching.md

You can add a steer in plain words, e.g.
  implement-plan thoughts/shared/plans/2026-08-08-CLAIMS-142-provider-payout-batching.md \
                 "start with phase 2, the migration can wait"
```

Use the real path and the real phase count. Tell the user to clear even if they won't — it is the step
people skip, and skipping it is what makes the next context a continuation of this one rather than a
fresh read of the plan.

Do **not** commit the plan unless asked — plans are working artifacts.

## Important Guidelines

1. **Be Skeptical**:
   - Question vague requirements
   - Identify problems early
   - Ask "why" and "what about"
   - Don't assume — verify against the code

2. **Be Interactive**:
   - Don't write the whole plan in one shot
   - Get buy-in at each major step
   - Allow course corrections

3. **Be Thorough**:
   - Read all context files COMPLETELY before planning
   - Research real patterns using parallel exploration passes
   - Include specific file paths and line numbers
   - Write measurable success criteria, clearly split automated vs manual

4. **Be Practical**:
   - Favour incremental, independently testable changes
   - Consider migration and rollback
   - Think about edge cases
   - Include "what we're NOT doing"

5. **Reuse before you rebuild** — InsureFlow house style. Before a plan introduces a new helper, DTO,
   Kafka event, or Angular component, check whether one exists:
   - Java shared code: `services/java/shared/` (audit helpers like `AuditActor`, `AuditEvent`;
     `InsuranceLine` enum; DTOs; error handling; JWT extraction)
   - Go shared code: `services/go/shared/` (Kafka publisher, HTTP middleware)
   - Angular shared: `clients/angular/src/app/shared/` (form controls, tables, layout, debounced
     search-select — never use raw `<input>` for IDs; see the `no_raw_id_inputs` feedback memory)
   - Auto-memory: skim the project's `MEMORY.md` index (under your local Claude project memory dir)
     for prior decisions that constrain the design (Testcontainers pitfalls, audit-actor-email
     invariants, tenant Flyway drift, one-contribution-per-month guard, etc.). Memory is per-machine
     by design; if empty on this box, skip.

   A plan that reinvents a shared piece is a plan that will get sent back.

6. **Honor the 9 Critical Rules** from `.claude/CLAUDE.md` — the plan is bound by them as much as the
   code. The ones that most often reshape a plan:
   - **Never mix currencies in arithmetic**; always convert via the exchange-rate service; `BigDecimal`
     in Java, `decimal` elsewhere — never floating point for money
   - **Every database query must be tenant-scoped** via the tenant-aware `TenantContext` interceptor.
     Cross-tenant reads are a design decision, not a shortcut
   - **AI decisions must be auditable** — log model version, input features, confidence, output, with
     a human-reviewable trail
   - **PII/PHI must be encrypted at rest and in transit**; audit logs are append-only; MFA mandatory
     for admin/staff; OAuth 2.0 / OIDC via Keycloak (no custom auth)
   - **Per-tenant rules live in the rules-engine service** as JSON `RuleDefinition`s compiled to Drools
     DRL — line-specific behavior is rule *content*, not engine code
   - **Service communication via Kafka events** for side effects; sync calls only for query/read
   - **Every API endpoint must be documented in Swagger (OpenAPI 3.1)** — accessible at `/swagger-ui`
     (Java) or `/docs` (Python)
   - **Every entity mutation must be audit-logged** to Kafka (actor, old/new value, changed fields,
     correlation ID); audit events are immutable
   - **All security events must be logged** — login, MFA, permission denials, impersonation

   If the plan appears to need an exception, say so explicitly in the plan and flag it — don't plan
   around a Critical Rule silently.

7. **Track Progress** — keep a visible checklist of the planning tasks and update it as research
   completes.

8. **No Open Questions in the Final Plan**:
   - If an open question appears while planning, STOP
   - Research it or ask for clarification immediately
   - Do NOT write a plan with unresolved questions
   - Every decision must be made before the plan is final

## Success Criteria Guidelines

**Always split success criteria into two categories:**

1. **Automated Verification** (an implementing agent can run it):
   - Commands that actually exist in this repo (see below)
   - Specific files that should exist
   - Compilation and test suites

2. **Manual Verification** (needs a human):
   - Anything that only a real user journey proves — a payment actually charging, an SMS landing on a
     handset, a Keycloak realm event firing
   - Acceptance judgement: "does this read right", "is this the UX we wanted"
   - Performance under real conditions

**Use InsureFlow's real commands via the Makefile.** The Makefile is the single source for developer
lifecycle. Do not invent targets.

| Check | Command |
|---|---|
| Start infrastructure (postgres, redis, kafka, keycloak, minio) | `make infra` |
| Java service compile + run (per-service) | `make tenancy` / `make user` / `make claims` / `make contributions` / `make finance` |
| Go service run (per-service) | `make gateway` / `make notification` / `make audit` / `make file-svc` / `make payment` |
| Elixir apps | `make live-dashboard` / `make chat` |
| Python AI | `make ai` |
| Angular dev server (port **5100**, not 4200) | `make web` |
| Java unit tests | `make test-java` |
| Java integration tests (Testcontainers slices) | `make test-integration` |
| Go tests | `make test-go` |
| Elixir tests | `make test-elixir` |
| Python tests | `make test-python` |
| Angular unit tests | `make test-angular` |
| Flutter tests | `make test-flutter` |
| Playwright E2E (in `clients/angular/e2e/`) | `make test-e2e` |
| Coverage summary across all languages | `make test-coverage` |
| Anything visual | the **`verify`** skill — drive the running app in a browser |

**Visual changes are not manual verification.** The `verify` skill drives the real app and gates on
console errors, broken images, and the interaction actually firing — so it belongs under **Automated
Verification** for any phase that touches Angular templates, styles, or client-side behaviour. Only what
a browser genuinely cannot reach goes in the manual list.

**Format example:**
```markdown
### Success Criteria:

#### Automated Verification:
- [ ] Java compiles: `cd services/java && ./gradlew :claims-service:build`
- [ ] Unit tests: `make test-java`
- [ ] Integration tests (Testcontainers): `make test-integration`
- [ ] Tenant migration applies cleanly (covered by IT harness)
- [ ] Angular unit tests: `make test-angular`
- [ ] Playwright: `make test-e2e` — the new payout-batching journey is green
- [ ] `verify` on `/admin/finance/payouts`: no console errors, table renders, filter fires

#### Manual Verification:
- [ ] A real Ecocash payout confirmation arrives at the provider's phone
- [ ] The payout run reconciles against the finance ledger by close of day
```

## Common Patterns

### For Java service changes (Spring Boot WebFlux + R2DBC):
- Start with the R2DBC entity + Flyway migration (idempotent SQL; never edit an applied migration;
  higher-numbered file for corrections)
- Add the repository/query work behind `TenantContext`
- Update business logic under `service/`
- Expose via a `@RestController` — document with OpenAPI annotations so it renders at `/swagger-ui`
- Emit a Kafka event on every mutation (audit + business event); test the round-trip
- Add integration tests under `*IT.java` (Testcontainers Postgres + Kafka; remember the 1.21.4 BOM
  override and the ReactiveJwtDecoder stub — see the `infra_testcontainers_pitfalls` memory)

### For Go service changes (Fiber v2 + Reactor-Kafka consumers):
- Handler → service → repository split under the service's package tree
- **Never `.doOnTerminate` for Kafka offset ack** — it fires on error and drops failed records; use
  `.doOnSuccess` with full-cause-chain error logging (see the `reactor_kafka_ack_swallow` memory)
- Add fiber middleware for tenant resolution + JWT verification
- Add unit tests (`go test ./...`) and any consumer round-trip test

### For Angular work:
- Feature module under `clients/angular/src/app/features/<name>/`
- Reuse shared form controls; **never a raw `<input>` for IDs** — use the debounced search-select from
  shared (payload holds ID, UI shows name)
- Stats/aggregates come from server-side KPI + chart endpoints — never aggregate in the client
- Add a Playwright E2E spec under `clients/angular/e2e/` for the golden path

### For cross-service features:
- Name the Kafka topics and payload schemas up front; producer service first, consumer service second
- Sync REST calls only for reads — no cross-service DB access
- Correlate with a request/trace ID that flows via header + Kafka header

### For Refactoring:
- Document current behavior first
- Plan incremental changes with green builds between them
- Maintain backwards compatibility on Kafka event schemas (add fields, don't remove them without a
  deprecation window); include the rollout order

## Exploration Pass Best Practices

When delegating research:

1. **Run multiple passes in parallel** for efficiency (up to 3 concurrently)
2. **Each pass should be focused** on one specific area
3. **Give detailed instructions**: exactly what to look for, which directories, what to extract, and the
   output shape you want back
4. **Be EXTREMELY specific about directories** — include the full path context. This is polyglot; a pass
   told "the claims flow" without a language hint will straddle Angular, Go, and Java and come back
   shallow for all three. Instead say `services/java/claims-service/src/main/java/com/medfund/claims/`
   or `clients/angular/src/app/features/claims/`
5. **Specify read-only investigation** — exploration passes never edit
6. **Request specific `file:line` references** in responses
7. **Wait for all passes to complete** before synthesizing
8. **Verify pass results** — if something comes back unexpected, run a follow-up pass and cross-check
   against the actual code. Don't accept a result that looks wrong.

## Important notes

- **This skill plans; it does not implement.** No source edits, no `make` builds, no PRs here.
  Building the plan is the `implement-plan` skill's job.
- **Each step of the loop runs in its own context.** Research investigates and writes a doc; you read
  that doc and write a plan; implementation reads the plan. The files are what carry state across the
  clears — which is why the frontmatter has to be complete and the steer has to be verbatim. Anything
  that shaped this plan and isn't *in* this plan is lost the moment the context clears.
- **A plan is a lower altitude than a spec or a ticket, not a replacement for one.** A spec carries the
  goal and the settled decisions in prose; this plan turns those into concrete, phased, code-level
  changes. When the input is a spec, its **Decisions** are fixed constraints — if one now looks wrong,
  raise it, don't quietly re-decide it in the plan.
- **thoughts/ path handling**: reference documents at their real repository path under `thoughts/shared/`
  (for example `thoughts/shared/specs/<slug>.md`). There is no separate search-mirror tree and nothing
  to sync — do not strip or rewrite any path segment.
