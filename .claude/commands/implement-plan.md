---
name: implement-plan
description: Implement an approved implementation plan from thoughts/shared/plans, phase by phase, with InsureFlow build/test/UI verification between phases. Step 3 of the RPI loop and the only implementer skill — use it whenever the input is a plan path or slug. Given a spec or ticket instead, plan it first with create-plan.
argument-hint: "<a plan path or slug from thoughts/shared/plans, plus any extra steer in plain words>"
---

# Implement Plan

You are implementing an approved implementation plan from `thoughts/shared/plans/`. These plans contain
phases with specific changes and success criteria, worked out at code altitude by the `create-plan` skill.

Use the input supplied with this invocation: a plan path or slug, **plus any steer the dev typed in plain
words** ("start with phase 2", "reuse the existing outbox publisher"). Resolve a slug against
`thoughts/shared/plans/` (the filenames are dated — `YYYY-MM-DD-[TICKET-]slug.md`; if several match, ask
which). If no input was supplied, ask which plan.

**If you're handed a spec or a ticket instead of a plan** (`thoughts/shared/specs/…`,
`thoughts/shared/tickets/…`), say so and point at the front of the loop:

```
That's a ticket, not a plan — this is step 3 of Research → Plan → Implement.

Start at step 1:
  research-codebase thoughts/shared/tickets/<slug>.md

then clear context and run create-plan with what it writes.
```

Those artifacts are deliberately code-free; implementing straight from one means deriving the whole design
inside the implementation, which is exactly what the loop exists to prevent. If the user would rather you
just build it, that's their call — proceed, but design the concrete edits and get them approved before
editing anything.

## Getting Started

- Read the plan **completely** and check for existing checkmarks (`- [x]`) — completed phases are done.
- Read the originating spec/ticket/research the plan references, and **every file the plan names**.
- **Read files fully** — never use limit/offset; you need complete context.
- Read the `.claude/*.md` architecture doc that matches the work — pick from:

  | Work area | Doc |
  |---|---|
  | Overall system design, service boundaries | `.claude/architecture.md` |
  | Language versions, libraries, rationale | `.claude/tech-stack.md` |
  | AI/ML integration points | `.claude/ai-integration.md` |
  | Currency handling, FX, financial precision | `.claude/multi-currency.md` |
  | Schema-per-tenant, tenant resolution, per-tenant rules | `.claude/multi-tenancy.md` |
  | Claims adjudication pipeline | `.claude/adjudication.md` |
  | Rules engine, DRL compilation, templates | `.claude/rules-engine.md` |
  | Payments and payouts | `.claude/payments.md` |
  | Deployment, CI/CD, observability, security | `.claude/infrastructure.md` |
  | Per-language coding conventions | `.claude/coding-standards.md` |
  | Portal specs (super admin, tenant admin, provider, member) | `.claude/portals.md` |

- Skim the project's auto-memory index (`MEMORY.md` under your local Claude project memory dir) — it
  lists prior bugs and feedback that constrain the work: audit-actor-email requirements, tenant
  Flyway drift, one-contribution-per-month guard, Reactor-Kafka ack pitfalls, `public.<tenant-table>`
  silent rollback, and more. Memory is per-machine by design; if the index is empty, that's fine.
- Think deeply about how the pieces fit together.
- Keep a visible checklist to track your progress through the phases.
- Start implementing once you understand what needs to be done.

## Implementation Philosophy

Plans are carefully designed, but reality can be messy. Your job is to:

- Follow the plan's **intent** while adapting to what you find
- Implement each phase fully before moving to the next
- Verify your work makes sense in the broader codebase context
- Update checkboxes in the plan as you complete sections

The plan's code blocks are the *design*, not a transcript to type in. Where the surrounding code has moved
since the plan was written, keep the design and adapt the code; where the design itself no longer holds,
stop and say so.

If you encounter a mismatch:

- STOP and think deeply about why the plan can't be followed
- Present the issue clearly:
  ```
  Issue in Phase [N]:
  Expected: [what the plan says]
  Found: [actual situation]
  Why this matters: [explanation]

  How should I proceed?
  ```

## Handling a steer

A steer given at implement time changes the code but not, by default, the plan that describes it — and
the plan is what a PR reviewer checks the diff against. So a steer that moves the build has to move the
plan too. The test is **written versus silent**:

- **The plan says otherwise in writing → it's a deviation.** Write it into the plan *before* the code, as
  a dated line under `## Deviations` (create the section if absent) or as an amended phase. You already
  edit this file to tick checkboxes; this is the same access, used for the thing that actually matters.
- **The plan is silent → it's implementation detail.** Naming, ordering, which private helper — apply it
  and move on. The plan was never at that altitude, and recording it turns the file into a diary.
- **The steer contradicts a Decision from the ticket or spec → stop and surface it**, using the mismatch
  format above. Decisions are hard constraints; a verbal instruction is not the route around them.

## Verification Approach — InsureFlow's Makefile, not `make check test`

InsureFlow is a polyglot monorepo; the `Makefile` at the repo root is the single source for the
developer lifecycle. Do **not** invent targets, and do **not** run individual gradle / go / mix /
ng commands when a Makefile target exists.

### Infrastructure

Every service assumes Postgres, Redis, Kafka and Keycloak are up. Start them once per session:

- `make infra` — brings up postgres, redis, kafka, keycloak, minio (detached)
- `make infra-ps` — status
- `make infra-logs` — tail
- `make keycloak-setup` — one-off realm/client bootstrap after first `make infra`

### Per-service run targets

| Service | Target | Port |
|---|---|---|
| Tenancy | `make tenancy` | 8081 |
| User | `make user` | 8082 |
| Claims | `make claims` | 8083 |
| Contributions | `make contributions` | 8084 |
| Finance | `make finance` | 8085 |
| Rules engine (library) | `make rules` | — |
| API gateway | `make gateway` | 3000 |
| Notification | `make notification` | 3001 |
| Audit | `make audit` | 3002 |
| File service | `make file-svc` | 3003 |
| Payment gateway | `make payment` | 3004 |
| Live dashboard (Elixir) | `make live-dashboard` | 4000 |
| Chat service (Elixir) | `make chat` | 4001 |
| AI service (Python) | `make ai` | 8000 |
| Angular web (dev server) | `make web` | **5100** (not 4200 — Makefile header is stale) |

Java services run with **Spring Boot DevTools** — the JVM restarts automatically when Gradle recompiles.
Go services use **air** for live reload. Angular and Phoenix have their own live reload. So the usual
loop is: leave the service running, edit, save, retest.

### Test targets

| Check | Command |
|---|---|
| Java unit tests | `make test-java` |
| Java integration tests (Testcontainers slices, `*IT`) | `make test-integration` |
| Go tests | `make test-go` |
| Elixir tests | `make test-elixir` |
| Python tests | `make test-python` |
| Angular unit tests | `make test-angular` |
| Flutter tests | `make test-flutter` |
| Playwright E2E (in `clients/angular/e2e/`) | `make test-e2e` |
| Coverage summary across all languages | `make test-coverage` |

### Building a single service without running it

If you just need to know the code compiles:

- Java: `cd services/java && ./gradlew :<service>-service:build`
- Go: `cd services/go/<service> && go build ./...`
- Angular: `cd clients/angular && npx ng build`
- Python: `cd services/python/ai-service && uv sync`

### Anything visual

Invoke the **`verify`** skill and *drive the real app in a browser* before the phase is handed back. A
green build says the code compiled — not that the page renders, the interaction fires, or the mobile
layout survived. For Angular changes this is required, not optional.

### Migrations

Java services use **Flyway**. Tenant-schema migrations sit under
`services/java/<service>/src/main/resources/db/migration/tenant/`; platform-wide migrations under
`db/migration/public/` or equivalent. Two invariants from the auto-memory that catch new devs out:

- **Never edit an applied migration** — Flyway locks the checksum on apply. Write a new higher-numbered
  file for any correction, and favor idempotent SQL (`CREATE … IF NOT EXISTS`, `ALTER … IF EXISTS`).
  See `feedback_never_edit_applied_migrations`.
- **Don't prefix `public.<tenant-table>` in a tenant-schema query** — it swallows into an opaque
  ROLLBACK. Use unqualified names for tenant tables; only prefix `public.` for platform-wide V105+
  tables. See `bug_public_prefix_silent_rollback`.

### Logs when something's off

Java service logs go to stdout (the terminal that ran `make <service>`). Go services via `air` do the
same. For Docker infra: `make infra-logs`.

## After implementing a phase

- Run that phase's **Success criteria** from the plan, plus the compile + test targets above
- Fix any failure before proceeding — never advance on a red phase
- Update your progress in both the plan and your checklist
- Check off completed items in the plan file itself using an edit
- **Pause for human verification.** Once automated verification passes — including `verify` for any
  visual change, so what's left is genuinely only what a browser can't reach — tell the user:

  ```
  Phase [N] Complete - Ready for Manual Verification

  Automated verification passed:
  - [List automated checks that passed]

  Please perform the manual verification steps listed in the plan:
  - [List manual verification items from the plan]

  Let me know when manual testing is complete so I can proceed to Phase [N+1].
  ```

If instructed to execute multiple phases consecutively, skip the pause until the last phase. Otherwise,
assume you are just doing one phase.

Do not check off items in the manual testing steps until confirmed by the user.

### Clearing context at a phase boundary

On a **long or multi-session plan**, the phase pause is also a clean place to clear context — by phase 4
a single session is carrying phase 1's exploration, three rounds of build output, and a verification
exchange per phase. The plan file is the state: checkboxes record what's done, and *Resuming Work* below
picks up from the first unchecked item. So the context is genuinely disposable here.

Add it to the phase-complete message when the plan is long or spans sessions:

```
Optional: clear your context before Phase [N+1] — the plan's checkboxes hold the state.
  implement-plan thoughts/shared/plans/<this-plan>.md
```

Two limits:

- **Only on green.** If the phase surfaced an `Issue in Phase [N]` and the design is still being
  negotiated, that argument lives in the context and *not* in the plan file. Never suggest clearing
  mid-mismatch.
- **The dev's call, not a rule.** Tightly-coupled phases — phase 2 extending the service phase 1 just
  created — are cheaper to carry than to re-read. Offer it; don't insist. For a short plan, don't
  mention it at all.

## Honor the 9 Critical Rules (from `.claude/CLAUDE.md`)

These always apply, whether or not the plan mentions them. Skim them before every phase:

1. **Never mix currencies in arithmetic** — convert via the exchange-rate service before summing or
   comparing. `BigDecimal` (Java) / `decimal` (others) — never floating point for money.
2. **Every DB query is tenant-scoped** via the `TenantContext` interceptor. Resolve tenant from JWT
   or subdomain. Cross-tenant reads are a design decision, not a shortcut.
3. **AI decisions must be auditable** — log model version, input features, confidence, output, with
   a human-reviewable trail.
4. **Data protection first** — PII encrypted at rest and in transit; PHI additionally under healthcare
   compliance; audit logs immutable; MFA mandatory for admin/staff (TOTP + Email OTP + SMS OTP via
   Keycloak); OAuth 2.0 / OIDC via Keycloak — no custom auth.
5. **Per-tenant rules live in the rules-engine service** as JSON `RuleDefinition`s compiled to Drools
   DRL. Facts are line-agnostic (`ClaimFact`, `MemberFact`, `ContributionFact`, `PaymentRunFact`);
   line-specific behavior is rule *content*, not engine code.
6. **Service communication via Kafka events** for side effects; sync REST/gRPC only for queries.
7. **Every API endpoint must be documented in Swagger (OpenAPI 3.1)** — accessible at `/swagger-ui`
   (Java) or `/docs` (Python). No endpoint ships without a complete definition.
8. **Every entity mutation must be audit-logged** to Kafka (actor, `actorEmail`, old/new value,
   changed fields, correlation ID). Audit events are immutable; the `AuditActor` helper is the
   source of truth for actor identity — never inline JWT extraction, never null `actorEmail`
   (see `feedback_audit_actor_email`). `AuditEvent.entityName` is friendly text, never the UUID
   (see `feedback_audit_entity_name`).
9. **All security events must be logged** — login, logout, MFA, password changes, role assignments,
   permission denials, impersonation. Keycloak event listener pushes auth events; services emit
   access events.

### Language-specific conventions

Java (see `.claude/CLAUDE.md` for the full table): use Lombok — `@Slf4j` for loggers,
`@RequiredArgsConstructor` for services, `@Getter @Setter` (not `@Data`) for R2DBC entities, `record`
for DTOs, `@Builder` + `@Getter` for complex value objects. Never `LoggerFactory.getLogger(...)`
manually. Never `@Data` on entities.

**A plan is not a licence to break a Critical Rule.** If following the plan literally would violate
one, that is a mismatch — raise it using the `Issue in Phase [N]` format above rather than
implementing it.

## If You Get Stuck

When something isn't working as expected:

- First, make sure you've read and understood all the relevant code
- Consider if the codebase has evolved since the plan was written
- Check the auto-memory index for a matching prior bug or gotcha
- Present the mismatch clearly and ask for guidance

Use exploration passes sparingly — mainly for targeted debugging or exploring unfamiliar territory.

## Resuming Work

If the plan has existing checkmarks:

- Trust that completed work is done
- Pick up from the first unchecked item
- Verify previous work only if something seems off

## Before the PR: the self-review loop

The work isn't done when the last phase builds and the tests pass. Once all phases are green, the plan
author (you, with the developer) delivers a fully self-reviewed diff — no open Blockers or Importants
by the time anyone else looks at it, or deferred with a stated reason.

Read *"by the time anyone else looks at it"* precisely: it means before a **reviewer** opens the PR,
not before the PR exists. The order is create the PR, then self-review the whole diff — because a
review's output has to be posted on the PR to count.

So when the final phase is verified, prompt for it:

```
All phases are verified. Recommended: a full self-review before a reviewer sees this.

The order is `create-pr` first (or `gh pr create` directly), then `code-review` over the whole
diff — triage every Blocker/Important, fix, and re-sweep until nothing is left but items deferred
with a reason. The self-review comment gets posted to the PR.

Want me to open the PR now and run it?
```

If a PR already exists for the branch, skip straight to `code-review`.

Three things make the difference between a sweep that works and one that doesn't:

- **Sweep by class, not by diff.** For everything the plan introduced — a Kafka event schema, a
  shared DTO, a URL builder, a rule template — the question is *who else consumes it*. In a polyglot
  monorepo those consumers are usually in a *different language*: a new Java Kafka event has Go
  and Elixir consumers; a new Angular DTO has a Java producer. Those files are not in your diff, and
  that is where the expensive misses live.
- **A fix-verification pass only certifies if the sweep already read the final design.** The round
  whose conclusion goes on the PR needs both: a fresh full sweep that covered the *final*
  architecture, and every fix since confined to code that sweep covered. A multi-phase plan is
  especially exposed here — a sweep run at phase 2 says nothing about the design that landed in
  phase 4, so that sweep cannot certify however thorough it was.
- **Check claims that point outside the diff** — "runs in CI", "the consumer picks this up", "the
  migration runs on deploy". Open `.github/workflows/*.yml`, the consumer's Kafka registration, or
  the Flyway migration folder and confirm it. Re-reading your own code never can. Same for any
  deferral reason resting on one.

Open the PR only when the user asks. Plan files are working artifacts — don't commit the plan itself
unless asked.

Remember: You're implementing a solution, not just checking boxes. Keep the end goal in mind and
maintain forward momentum.
