---
name: research-codebase
description: Investigate a question across the InsureFlow codebase and write a cited research document to thoughts/shared/research/. Use for open "how does X work / where does X live" questions, and as step 1 of the RPI loop when a developer picks up a ticket or spec to build. It only researches — the plan afterwards belongs to the create-plan skill.
argument-hint: "<research question, or a ticket/spec path to research for implementation>"
model: opus
---

# Research Codebase

You are conducting comprehensive research across the codebase to answer a question by running parallel
research passes and synthesizing their findings into a document under `thoughts/shared/research/`.

Use the input supplied with this invocation as the research question. If none was supplied, follow
"Initial setup" below.

The subject is **InsureFlow** (the working directory) — a multi-tenant, multi-line insurance core system
built as a greenfield polyglot monorepo. There are no external legacy or design-spec scopes to pull in;
the legacy codebase referenced in the root `CLAUDE.md` is domain-knowledge reference only and does not
sit outside this repo.

## Initial setup

When invoked with no question, respond with:

```
I'm ready to research the codebase. Give me your question and I'll investigate it across the
InsureFlow monorepo (Java services, Go services, Elixir umbrella, Python AI service, Angular web,
Flutter mobile).
```

Then wait for the query.

## Steps

### 1. Read any directly-mentioned files first, and follow what they cite

If the user names specific files (tickets, specs, docs), read them **FULLY** (no limit/offset) in the main
context before launching anything. This grounds the decomposition.

**When the input is a ticket or a spec, you are in ticket mode** — step 1 of the RPI loop, researching so
that a `create-plan` run can build from your output. Two things change: you resolve sources transitively
(below), and you add two sections to the document (Step 6).

**Transitive source resolution.** InsureFlow tickets often link to architecture docs, adjacent tickets, or
a domain reference under `.claude/`. Follow the chain and read each link fully:

1. **The ticket itself.**
2. **Its parent/overview ticket or spec**, if it says anything like "Part 2 of 5 — see …". The overview
   is where the epic's Decisions live.
3. **Any `.claude/*.md` architecture doc** it names (e.g. `.claude/multi-tenancy.md`,
   `.claude/adjudication.md`, `.claude/rules-engine.md`, `.claude/payments.md`,
   `.claude/coding-standards.md`).
4. **Any prior research doc under `thoughts/shared/research/`** it names.

**Announce what you pulled in, so it can be trimmed:**

```
Ticket mode. Following this ticket's sources:
- overview: thoughts/shared/tickets/claims-provider-payout-epic.md
- architecture doc: .claude/adjudication.md
- prior research: thoughts/shared/research/2026-06-14-provider-payout-batching.md

Say if you want any of these left out.
```

State it and keep going — don't block on a reply.

### 2. Understand the scope map before delegating

The InsureFlow monorepo splits by language and service. Match the question to the relevant service(s)
before decomposing.

| Layer | Directory | Notes |
|---|---|---|
| Java core services | `services/java/{tenancy,user,claims,contributions,finance,rules-engine,keycloak-event-listener}-service`, plus `services/java/shared` | Spring Boot 3.3 WebFlux, R2DBC, per-tenant Postgres schema |
| Go high-throughput services | `services/go/{gateway,notification-service,audit-service,file-service,payment-gateway}`, plus `services/go/shared` | Fiber v2, Kafka consumers |
| Elixir umbrella | `services/elixir/apps/{live_dashboard,chat_service}` | Phoenix 1.7, LiveView, PubSub |
| Python AI service | `services/python/ai-service` | FastAPI, uv-managed |
| Angular web app | `clients/angular` | Angular 19, role-based routing (super admin, tenant admin, operations, provider, member portals) |
| Flutter mobile / PWA | `clients/flutter` | Member + provider companion |
| Infrastructure & scripts | `infra/`, `scripts/`, `docker-compose.yml`, `Makefile` | |
| Architecture docs | `.claude/*.md` (14 topic docs — see `.claude/CLAUDE.md`) | Read the relevant one before diving in |
| Historical context | `thoughts/shared/{research,plans,tickets,specs}/` | Prior RPI-loop artifacts |

When the question crosses services — "how does a claim flow from Angular through the gateway to
claims-service to Kafka" — be explicit about which slice each pass covers. A single pass that straddles
Java and Go usually returns shallow findings for both.

### 3. Decompose the question and plan

Break the query into composable research areas. Take time to think about the underlying patterns and
architectural implications. Keep a visible checklist of subtasks. Identify which services and which
`.claude/*.md` docs are relevant.

### 4. Launch parallel research passes

Delegate the searching to focused research passes; each one should be told *what* to find, not *how* to
search. The passes you have available are capabilities, not fixed tools:

- a **locator pass** — finds which files, directories, and components are relevant;
- an **analyzer pass** — reads the promising hits and reports implementation detail;
- a **pattern pass** — finds similar existing implementations and concrete usage examples;
- a **thoughts-directory pass** — discovers and deep-reads relevant docs under `thoughts/shared/`;
- an **architecture-docs pass** — deep-reads the `.claude/*.md` topic docs that ground the question
  (`architecture.md`, `multi-tenancy.md`, `adjudication.md`, `rules-engine.md`, `payments.md`,
  `multi-currency.md`, `ai-integration.md`, `coding-standards.md`, `infrastructure.md`, `portals.md`);
- a **web research pass** for anything that needs external, current information.

**Be specific about the service in every prompt.** "The claims flow" is ambiguous; say
`services/java/claims-service/` for adjudication, `services/go/gateway/` for routing/auth, or
`clients/angular/src/app/features/claims/` for the UI.

Run passes in parallel when they cover different things — launch up to 3 concurrently, bounded by
currently available capacity. Match pass count to scope. After they return, read the most critical files
yourself — pass summaries are starting points, not ground truth.

### 5. Wait for all research passes, then synthesize

Wait for **every** pass to finish before writing. Then:

- Treat **live code** as the primary source of truth; `.claude/*.md` docs give the *intended* architecture
  and are load-bearing when the code and doc disagree — flag the drift rather than picking one silently.
- `thoughts/` findings are supplementary historical context; a research doc from three months ago is a
  snapshot, not ground truth.
- Connect findings across services; cite concrete `file:line` references.

### 6. Gather metadata

- Filename: `thoughts/shared/research/YYYY-MM-DD-<description>.md` (add a ticket handle like
  `CLAIMS-142-` before the description if there's one). Get the date, current commit
  (`git rev-parse HEAD`), branch, and repo name.

### 7. Write the research document

```markdown
---
date: [ISO date+time with timezone]
researcher: [name]
git_commit: [commit hash]
branch: [branch]
repository: medfund
topic: "[question]"
tags: [research, codebase, relevant-services]
status: complete
last_updated: [YYYY-MM-DD]
last_updated_by: [name]
---

# Research: [Question]

**Date**: [date+time] · **Researcher**: [name] · **Commit**: [hash] · **Branch**: [branch]

## Research Question
[Original query]

## Summary
[High-level answer]

## Findings

### [Service or component]
- Finding with `services/java/claims-service/src/main/java/…/ClaimService.java:123`
- Connections, implementation details, cross-service references

### [Next service or component]
- …

## Cross-service flow   ← when the question spans services
[How data / events move between services, with the Kafka topic names and the request boundaries. Cite
both producer and consumer file:line.]

## Architecture doc vs. code
[Where `.claude/*.md` and the code agree or drift. Flag drifts explicitly — a design doc that no longer
matches the code is a follow-up in its own right.]

## Code References
- `services/java/…:123` — what's there
- `services/go/…:45` — what's there
- `clients/angular/…:67` — what's there
- `.claude/adjudication.md:200` — what the design says

## What this ticket needs decided   ← ticket mode only
[The genuine forks a plan has to resolve, each with the evidence for and against. Not open questions for
the archive — the input `create-plan` will crystallize into Decisions. One bullet per fork.]

## Gaps between spec and code   ← ticket mode only
[Where the cited architecture doc or ticket promises something the code doesn't do yet, and where the
code has moved past or away from the doc. This is the delta the ticket actually asks for. Cite both
sides — the doc section and the `file:line`.]

## Architecture Insights
[Patterns, conventions, decisions discovered — including anything relevant to the 9 Critical Rules in
.claude/CLAUDE.md: tenant scoping, currency handling, AI auditability, PII/PHI protection, per-tenant
rules-engine content, Kafka event contracts, Swagger completeness, audit-log emission, security events.]

## Historical Context (from thoughts/shared/)
- `thoughts/shared/research/…md` — prior finding about X
- `thoughts/shared/plans/…md` — prior plan touching this area

## Related Research
[Links to other docs in thoughts/shared/research/]

## Open Questions
[Anything needing further investigation]
```

Never write placeholder values — fill metadata from Step 6 before writing.

### 8. Add GitHub permalinks (when on main or the commit is pushed)

If the working branch is `main` or the commit is pushed, turn file references into permalinks
(`gh repo view --json owner,name` → `https://github.com/{owner}/{repo}/blob/{commit}/{file}#L{line}`).
The remote is `github.com/MecksonMfema/medfund`.

### 9. Present, hand off, and handle follow-ups

Present a concise summary with key file references. For follow-ups, append to the same document: update
`last_updated`/`last_updated_by`, add `last_updated_note`, add a `## Follow-up Research [timestamp]`
section, and launch new research passes as needed.

**Then hand off to the next step of the loop.** Research is step 1 of Research → Plan → Implement, and
each step runs in its **own context** — that is what keeps the plan from being written in a context
stuffed with exploration. The research document is what survives the clear, so the hand-off has to name
it:

```
Research written to thoughts/shared/research/2026-08-08-provider-payout-batching.md

Clear your context, then run:
  create-plan thoughts/shared/research/2026-08-08-provider-payout-batching.md

You can pass more than one document and add a steer in plain words, e.g.
  create-plan thoughts/shared/research/2026-08-08-provider-payout-batching.md \
              thoughts/shared/tickets/finance-provider-payout.md \
              "keep the multi-currency conversion out of scope for phase 1"
```

Use the real paths. Tell the user to clear even if they don't — the discipline is the point, and it is
the step people skip.

## Important notes

- **Two modes.** A *free-form question* researches what the question names. A *ticket or spec path* puts
  you in **ticket mode** — resolve its sources transitively (Step 1), add the two ticket-mode sections
  (Step 7), and hand off to `create-plan` (Step 9). Ticket mode is step 1 of the RPI loop and is the
  higher-traffic path.
- **InsureFlow is a polyglot monorepo.** Match research passes to specific services rather than letting
  one pass straddle multiple languages. A pass told "find where claims are adjudicated" without a
  service hint will conflate the Angular UI, the gateway routing, and the Java claims service — and
  return shallow results for all three.
- **The 9 Critical Rules in `.claude/CLAUDE.md` are load-bearing.** When research surfaces code that
  appears to violate one (mixed currencies, un-tenant-scoped queries, un-audited AI decisions,
  synchronous inter-service side-effect calls, undocumented endpoints, un-logged security events),
  call it out explicitly in the "Architecture Insights" section.
- Always run **fresh** codebase research — don't rely solely on existing research docs; `thoughts/` is
  supplementary.
- Use parallel research passes to maximize coverage and keep the main context focused on synthesis, not
  deep file reading.
- Find concrete `file:line` references; research docs should be self-contained.
- **thoughts/ path handling**: cite documents at their real repository path under `thoughts/shared/`
  (for example `thoughts/shared/research/<slug>.md`). There is no separate search-mirror tree; do not
  strip or rewrite any path segment.
- This skill **researches** — it doesn't implement or write specs. Its output feeds the `create-plan`
  skill (or a ticket/spec-authoring skill, if one is added later).
