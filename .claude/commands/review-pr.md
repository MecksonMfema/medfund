---
name: review-pr
description: Review an existing pull request in one of two modes — the author's full self-review defect sweep, or a reviewer's confirmation pass (gate audit, high-level functionality, follow-up ideas). Mode auto-selects from authorship; pass `author` or `reviewer` to override. Not for writing a PR description or opening a PR (use create-pr); this skill only reviews a PR that already exists.
argument-hint: "[pr-number-or-url] [author|reviewer]"
---

# PR Review

Ground the review in **actual code**, not the description's summary. The checklists below are a floor,
not a ceiling. InsureFlow is a polyglot monorepo — the highest-value defects live where a change in one
language breaks a consumer in **another** (a Kafka event's Go/Elixir/Python consumers; a Java producer
behind an Angular DTO; a gateway route and its backend service). Those files are not in the diff, and
that is where expensive misses hide.

## Two modes

| | **Author mode** | **Reviewer mode** |
|---|---|---|
| Who | You are the PR author | You are not the PR author |
| Job | The **full defect sweep** — find everything, triage everything | **Confirm** the author's sweep happened and holds; review high-level functionality; propose follow-ups |
| Core step | **6** (sweep + verify) | **7** (gate audit, high-level, follow-ups) — **no defect fan-out** |
| Output | Full findings + triage dispositions | A gate verdict, then a bounce or a pass review |

**The reviewer does not re-run the defect sweep** — doing so hides whether the author's process works.

**Mode selection:** an `author`/`reviewer` token in the input always wins; otherwise step 2a decides
from authorship.

## Steps

Steps 1–5 are identical in both modes. The modes fork at 6/7 and rejoin at 8.

### 1. Identify the PR

PR target = the input (number, URL, or empty for the current branch's PR), with any mode token stripped.
If empty: `gh pr view --json number,url,title,headRefName,baseRefName,state,author`.

**If no PR resolves, don't review the working diff** — say so and stop: *"No PR for `{branch}` yet —
`review-pr` runs on a PR, and the self-review is posted to it. Open one with `create-pr` first?"* An
unposted review is invisible to the step-2a gate.

### 2. Ground yourself in the PR

Launch these concurrently:

```bash
gh pr view {N} --json number,title,body,headRefName,baseRefName,author,state,mergeable,mergeStateStatus,isDraft,files,additions,deletions,changedFiles
gh pr view {N} --json comments
gh pr view {N} --json reviews
gh api repos/MecksonMfema/medfund/pulls/{N}/comments --paginate   # inline line comments
gh pr view {N} --json commits
gh pr diff {N} > /tmp/pr-{N}.diff
```

Then:
- Read the PR **description** fully.
- **Find the linked artifact** — the `thoughts/shared/plans|tickets|research` doc the PR implements, from
  the body's link or the branch slug. Read it; the review is grounded against it. Missing and
  underivable → say so once and mark spec alignment `N/A — no linked plan/ticket`.
- Read every existing comment and review, the author's **triage table first**. Don't re-raise what's
  addressed or consciously deferred; take "fixed in `<sha>`" at its word unless HEAD shows otherwise.

### 2a. Resolve the mode and the self-review gate

Resolve (1) whether the runner is the PR author (`gh api user -q .login` vs the PR `author.login`) —
this sets the mode unless a token overrode it; (2) whether the author has posted any review or comment.

- **Author mode** — this run *is* the self-review. **Count the round** from earlier full-sweep posts on
  this PR + 1. Rounds 1→2 are routine; a 3rd full sweep needs a real trigger (a new Blocker, or
  substantial fixes since the last sweep) — otherwise offer a fix-verification post instead. Announce
  mode and round in one line and continue.
- **Reviewer mode, author has posted** — continue to 2b.
- **Reviewer mode, author has posted nothing** — the gate failed at the first hurdle. **Stop and bounce**
  per 7.4. Don't review anyway; that hides the failure. (A ticked `Self-reviewed` checkbox is not a
  posted review.)

### 2b. Post-self-review commit gate (reviewer mode — stop and ask)

A self-review the author then pushed fixes on top of reviewed code that no longer exists. **Skip in
author mode.**

1. **Baseline** = the most recent post by the PR author that *presents a review* (a triage table, a
   findings list, this skill's output). Ordinary replies ("fixed in `abc123`", "rebased") don't count.
2. **What landed after it** — from the step-2 `commits`. If nothing, say so in one line and continue.
3. **Size it** (`BASE` = parent of the earliest post-baseline commit):
   ```bash
   gh api repos/MecksonMfema/medfund/compare/{BASE}...{HEAD_SHA} \
     --jq '{commits:(.commits|length), files:(.files|length), additions:([.files[].additions]|add), deletions:([.files[].deletions]|add), paths:[.files[].filename]}'
   ```
4. **Stop and ask — wait for the answer** for any post-self-review commit. State {M} commits, {files}
   files, +{add}/−{del}, the areas, and your verdict in one line, then offer: **(A)** bounce (7.4, gate
   failure = "commits landed after the self-review"); **(B)** confirm the gate over the whole PR anyway;
   **(C)** scope every later step to `{BASE}...{HEAD}`. Point at the option your verdict favours
   (substantial → A, trivial → B).

### 3. Check out the PR head locally

Review the **actual current head**, never a stale local copy.

1. `ORIGINAL_REF=$(git symbolic-ref --quiet --short HEAD || git rev-parse HEAD)` (step 8 offers to restore it).
2. `git fetch origin --prune`.
3. `REMOTE_SHA=$(gh pr view {N} --json headRefOid -q .headRefOid)`.
4. Abort if the working tree has **tracked** modifications (`git status --porcelain`); untracked files are fine.
5. `gh pr checkout {N}` then `git reset --hard $REMOTE_SHA` (checkout can stop at a non-fast-forward).
6. Verify `git rev-parse HEAD == $REMOTE_SHA`; if not, stop and investigate.

### 3a. Merge-conflict check

`gh pr view {N} --json mergeable,mergeStateStatus`. `MERGEABLE` → continue (`BLOCKED` is usually pending
checks). `CONFLICTING`/`DIRTY`/`UNKNOWN` → verify locally (`git merge-tree --write-tree origin/main HEAD`
— non-zero exit or conflict markers = real); never report a conflict from the flag alone. If real, stop
and ask whether to (A) merge `origin/main` and resolve or (B) review as-is. **Never push during a review.**

### 4. Verify completeness and ground in acceptance criteria

- Spot-check 3–5 files/classes the description claims it added — they exist (`git ls-files`); commit and
  file counts roughly match the narrative. If incomplete, stop and report.
- Re-validate the description's **behavioural** claims against HEAD. A materially false behavioural claim
  is IMPORTANT (the body becomes the merge subject); other drift is paperwork for the step-9 hand-off.
- **Extract the acceptance criteria** from the linked plan/ticket — numbered scope items, success
  criteria, any must/never in the spec; note out-of-scope and deferred callouts (they bound what you can
  fairly flag). Carry this into 6k (author) / 7.2 (reviewer). No artifact → mark it `N/A`.

### 5. Test gate — CI first, local as the fallback

Most suites run per-language on GitHub Actions for the PR. Read the run by the step-3 SHA rather than
re-running everything:

```bash
gh run list --commit "$REMOTE_SHA" --limit 20 --json workflowName,status,conclusion,url
```

| Result | Do |
|---|---|
| all relevant workflows `success` | PASS — cite the URLs |
| `in_progress`/`queued` | run the sweep, then `gh run watch <id> --exit-status` before writing |
| `failure` | `gh run view <id> --log-failed`; a test failure is a **blocker** reported verbatim up front. If the same failure is red on `main`, it's inherited — note it, not a Blocker |
| no run (stacked PR, or Actions down) | fall back to local |

**Fallback** — run only the `make` targets the diff earns (`make test-java` / `-go` / `-elixir` /
`-python` / `-angular` / `-flutter`, plus `make test-integration` when the diff touches a Flyway
migration, R2DBC repo, or Kafka consumer/producer — `make infra` must be up). Say in *Build / Test
status* why it ran locally. For a migration in the diff, confirm it's a **new higher-numbered file**
(never an edit to an applied one) and that tenant-schema SQL uses unqualified table names (no `public.`
prefix on tenant tables) — see the memory gotchas `feedback_never_edit_applied_migrations` and
`bug_public_prefix_silent_rollback`.

---

## 6. AUTHOR MODE — the defect sweep

**Author mode only.** In reviewer mode skip to step 7.

For each category produce findings with `file:line`. Nothing to flag → `No issues`. Doesn't apply →
`**N/A — [reason]**`. Don't pad. **For a large diff, fan 6a–6h out to parallel subagents** (the Agent
tool, `Explore`/`general-purpose`), each given the diff path, its categories, and the consumer list;
the main agent handles 6i–6k inline (they need artifact text and conversation context). A **verify
stage (6.4)** refutes Blockers/Importants before write-up.

Three shaping rules:
- **Sweep by class, not by diff.** List what the PR introduces or re-means — a Kafka event field, a DTO
  field, a shared record, a URL/route, an enum value, a public method — and grep its **other consumers**,
  including the ones in a different language. Changing a default? Grep the removed value too.
- **Interact, don't just load.** For a UI change, drive the *second* action (filter, toggle, resubmit)
  via the `verify` skill, and say which.
- **Check every claim pointing outside the diff** — a CI workflow, a deploy, a Flyway migration that
  "runs on deploy", a consumer that "picks this up". Open the artifact; re-reading your own code can't confirm it.

A defect this PR didn't cause (pre-existing, or outside the ticket) defaults to **Deferred → ticket**. A
consumer this PR broke is this PR's.

#### 6a. The 9 Critical Rules (`.claude/CLAUDE.md`) — the InsureFlow floor

Walk each against the diff; most Blockers live here:

1. **Currency** — no mixed-currency arithmetic; convert via the exchange-rate service first. `BigDecimal`
   (Java) / `decimal` (others), never floating point for money.
2. **Tenant scoping** — every query is tenant-scoped via `TenantContext`/the tenant-aware connection
   factory; tenant resolved from JWT or subdomain. A read of a shared `public.<entity>` table with
   per-tenant relevance **must** join its `public.<entity>_tenants` membership table — omitting it leaks
   platform data across tenants (a Blocker even though it hits a `public.` table).
3. **AI auditability** — every AI-assisted decision logs model version, input features, confidence, output.
4. **Data protection** — PII encrypted in transit/at rest; MFA for admin/staff via Keycloak; OAuth2/OIDC
   via Keycloak, no custom auth; audit logs immutable.
5. **Rules in the rules-engine** — tenant/line-varying business logic is JSON `RuleDefinition` → Drools
   DRL, with line-agnostic facts; line-specific behaviour is rule *content*, not engine code.
6. **Kafka for side effects** — services don't call each other for side effects; sync REST/gRPC is
   query-only.
7. **Swagger** — every new/changed endpoint has a complete OpenAPI definition at `/swagger-ui` (Java) /
   `/docs` (Python).
8. **Audit-log every mutation** — CREATE/UPDATE/DELETE emits a Kafka audit event via the `AuditActor`
   helper with a **non-null `actorEmail`** and a **friendly `entityName`** (never the UUID) — see
   `feedback_audit_actor_email`, `feedback_audit_entity_name`.
9. **Security events** — login/logout/MFA/role/permission-denial/impersonation all logged.

**A PR cannot waive a Critical Rule.** A violation is a Blocker unless explicitly deferred with a reason.

#### 6b. Language conventions

- **Java** — Lombok, not boilerplate: `@Slf4j` (never `LoggerFactory.getLogger`), `@RequiredArgsConstructor`
  for services, `@Getter @Setter` (never `@Data`) on R2DBC entities, `record` for DTOs. `DateTime`/money
  as `BigDecimal`. Reactive: no blocking in the chain (`.block()`, blocking I/O); tenant read via
  `deferContextual`; Reactor-Kafka consumers **ack on success only**, never swallow the ack
  (`bug_reactor_kafka_ack_swallow`).
- **Go** — errors wrapped and handled, `context` threaded, no goroutine/data races (the gateway scheduler
  race is a known class — `services/go`).
- **Python** — typed, `ruff`-clean; FastAPI endpoints documented at `/docs`.
- **Angular/Flutter** — follow the design-system tokens/components (read `.claude/design-system.md` before
  a new page); specs match shipped behaviour.

#### 6c. Correctness & data

Business-logic bugs on new paths; boundary/null/off-by-one; swallowed errors leaving inconsistent state;
pagination correctness; a missing existence check returning 500 instead of 404. **Transactions:**
multi-aggregate mutations are atomic or explicitly compensating.

#### 6d. Multi-tenancy & migrations (backend)

- Tenant isolation holds through projections, caches (keys include tenant), and background jobs (which
  have no request tenant — the tenant must be carried explicitly).
- Flyway: **never edit an applied migration** (write a new higher-numbered file), favour idempotent SQL;
  **no `public.` prefix on tenant tables** in tenant-schema queries (it swallows into an opaque ROLLBACK);
  tenant DDL belongs to **tenancy-service** only. A new `platform_settings`/role/permission migration
  updates its seeder too.

#### 6e. Security

Auth guards present and declarative where possible; input validated server-side against allowlists;
self-operation (admin disabling/demoting themselves) rejected; stale claims take effect immediately; no
secret/PII in logs; SQL/`LIKE` wildcards escaped.

#### 6f. Cross-language consumer sweep (the monorepo's signature miss)

For every contract the PR changes, grep the consumers in **other** languages and confirm they still agree:
- a **Kafka event schema** (topic, field names, payload shape) → Go/Elixir/Python/Java consumers;
- an **Angular/Flutter DTO** → its Java producer, and vice-versa;
- a **gateway route or prefix** → the backend service it proxies, and any entitlement/auth gate;
- a **shared enum/record** → every service that imports it.
Name the consumers you checked. These files are not in the diff; that's the point.

#### 6g. Performance

N+1 queries, missing `AsNoTracking()`-equivalents on read paths, unnecessary reloads after save, work
that belongs in a background job awaited inline, unbounded result sets.

#### 6h. Architecture & design

Logic in the right layer/service; interfaces injected, not concretes; one rule in one place (divergent
copies drift); no mechanism invented where one exists; unenforced "callers must…" contracts replaced by a
type or a test; no per-tenant/per-line hardcoding where configuration or a rule belongs.

#### 6i. Tests (main agent, inline)

New public surface and security-sensitive code covered; negative paths (rejection, validation failure,
tenant mismatch) tested; the right suite (`make test-*` / `test-integration`); a regression test on any
bug fix. **Coverage gate: 70% line per service** (`.claude/coverage-backlog.md`) — a new/changed service
must clear it. Never pin an absolute timestamp into test data — assert the relationship.

#### 6j. Documentation & migration (main agent, inline)

Docs updated for a new convention or shared component; Flyway `Up`/`Down` both sensible; seed data
updated. The PR description's own file under `thoughts/shared/prs/` is **not** a review concern — never
above SUGGESTION.

#### 6k. Spec alignment (main agent, inline)

**N/A if step 4 found no artifact.** Walk the acceptance criteria and judge whether the code **satisfies
each** — `✅ met` / `⚠️ partial` / `❌ missed` with `file:line` or runtime evidence. A method named for a
requirement is not evidence. Distrust a green test that asserts a degenerate fallback the spec never
sanctioned. Diverging from an explicit requirement is a **violation** (Blocker/Important); bad behaviour
in a case the ticket never addressed is a **gap** (a question, not a finding). Honour out-of-scope callouts.

#### 6.4 Verify stage

**Scope: every Blocker and Important.** Spawn one subagent per finding, each prompted to **refute** it,
given the running services where relevant. Keep CONFIRMED (attach evidence), rewrite RESHAPED, drop
REFUTED (report how many). Each verifier states the command it ran and what it expected. No unverified
Blocker/Important survives to write-up.

#### 6.5 Triage and stop

Give every surviving Blocker/Important a disposition — Fixed (SHA) / Deferred (reason + where it goes) /
Won't fix (reason). Then fix and stop: after round 1, fix and run round 2; after round 2, hand off unless
round 2 found a Blocker or its fixes were substantial; a fix after the final sweep gets a
fix-verification post, not a fresh sweep; a 4th round means stop and ask. Building a deferred item
re-opens scope — ask first.

---

## 7. REVIEWER MODE — the confirmation pass

**Reviewer mode only. No defect fan-out.** If you're spawning subagents over 6a–6h you're doing the
author's job.

### 7.1 Audit the gate

The question is **"did the author's sweep cover this PR, and does its conclusion hold?"** — not "are
there bugs". Read the self-review posts oldest-to-newest, recording each one's **scope**:

- Did a fresh full sweep read the **final** architecture, with all later fixes confined to code it
  covered? (Both true → a fix-verification round may certify. Either false → it can't.)
- Does any row assert something **outside the diff** (CI, deploys, migrations, a cross-language consumer)
  that the sweep couldn't have checked by reading the diff? Cheapest gate failure to find.
- Do Deferred/Won't-fix rows **carry reasons**, and does each hold? A disposition without a reason is
  itself an Important.
- Does the triage cover Blockers **and** Importants, with Blockers Fixed (not deferred)?

**Spot-check the 2–3 strongest claims** — the one that certifies the whole PR, one cross-language consumer
sweep, one interaction test, one outward-pointing claim (CI/migration), and the Build/Test status against
the step-3 SHA. State the command and what it showed for each; if an anchor doesn't survive, find another
rather than softening it. A confirmed open Blocker, an Important deferred without a reason, or a failed
scope audit → go to **7.4** and stop. Otherwise continue.

### 7.2 High-level functionality

The part no self-review does well. Does it satisfy the ticket (walk the step-4 criteria as a table)?
Does the shape make sense (right layer, one rule in one place, no invented mechanism)? Is the premise
right (a comment saying "caller must gate on X" is an unenforced contract, not a solution)? What does it
make harder later (divergent copies, a shim nobody scheduled to delete)? Genuinely wrong here → 7.4; a
direction question → a discussion point or 7.3.

### 7.3 Follow-up ideas

Work the PR revealed that is genuinely **out of its scope** — systemic gaps, deferrals worth promoting to
a real ticket, coverage holes. One line each: what, why now, rough size. Don't smuggle in-scope defects
here to avoid bouncing.

### 7.4 Bounce (the gate failed)

**Stop. No fix list** — that does the author's job. State: (1) the verdict — what the self-review claimed
and that it doesn't hold (or, for the 2a no-post case, that nothing was posted); (2) 2–3 evidence anchors
each with its command or `file:line`; (3) what genuinely holds, so the bounce isn't read as an attack;
(4) the ask — a fresh full sweep, then re-request review. Skip step 8's pass template; use the gate-failed one.

---

### 8. Cross-check threads, write, and post

**Threads (both modes):** walk **unresolved** threads only; for each, say whether the latest commit
addresses it (with the SHA) or not — don't silently drop any. Honour the author's reasoned triage; argue
with the *reason*, not by re-raising verbatim. A Deferred/Won't-fix with no stated reason is itself an
Important.

**Write** to `thoughts/shared/prs/pr-{N}-review-{author|reviewer}.md` (add `-2`, `-3` for later rounds —
each is what the next gate audit reads). Length is not rigour: body under ~1,500 words, TL;DR under ~300;
describe *how* you checked only when it changes a finding's status; a dead end is one line. Every review
opens with a `## TL;DR` in plain language — the verdict and its reason, no `file:line` or tables.

Use the matching shape:
- **Author:** TL;DR · Build/Test status · Completeness · Spec alignment (6k table) · Verification performed
  · Blockers/Important/Suggestions (numbered, `file:line`, fix, CONFIRMED/RESHAPED) · Triage table ·
  Verify-stage tally · Responses to prior threads · Deferred follow-ups.
- **Reviewer, gate held:** TL;DR · Gate audit (each round's scope + the 2–3 spot-checks with what ran) ·
  Spec alignment · High-level functionality · Follow-up ideas · Responses to prior threads · What looks
  good · a plain-English close with a **/10** (9–10 merge as-is; 7–8 merge + follow-ups; 5–6 merge once
  named changes land; ≤4 don't merge).
- **Reviewer, gate failed:** the 7.4 bounce — verdict, evidence anchors, what holds, next step; no fix list.

**Post (ask first — a gate; wait for the answer).** Show the review and offer (A) post, (B) don't. Never
auto-post.

| Outcome | Form |
|---|---|
| Reviewer, gate failed | `--request-changes` |
| Reviewer, gate held, nothing outstanding | `--approve` |
| Reviewer, gate held, discussion points only | `--comment` |
| Author (any severity) | `--comment` (GitHub refuses approve/request-changes on your own PR) |

```bash
gh pr review {N} --{form} --body-file thoughts/shared/prs/pr-{N}-review-{mode}.md
```

In author mode, **posting creates the record** the next reviewer's step-2a gate looks for — say so;
declined, the PR reads as un-self-reviewed.

### 9. Close out

- Print the review's full path (`realpath`) — if not posted, it's the only copy.
- Reviewer mode: offer to restore `ORIGINAL_REF` (`git checkout {ORIGINAL_REF}`) unless HEAD already is
  it — ask and wait; tracked modifications from a 3a resolution → say so and stop.

## Important notes

- Every finding names a **file and line** (a failed gate excepted); reference a prior reviewer's comment
  rather than restating it.
- **Never push, force-push, or commit to the branch during a review.**
- A runtime claim is a Blocker/Important only with executed evidence (a command's output, a failing test,
  a provably unguarded path) — otherwise it's a SUGGESTION labelled "unverified — static reading", however
  serious it looks.
