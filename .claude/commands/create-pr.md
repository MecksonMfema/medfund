---
name: create-pr
description: Open a new GitHub pull request for the current branch against main, with a description grounded in the diff and linked to its plan/ticket; pass `quick` for a short summary on small/docs PRs. Use when the user asks to create/open/raise a PR. Not for reviewing a PR (use review-pr) and not for cutting a release or deploying.
argument-hint: "[quick]"
---

# Create Pull Request

You are opening a GitHub pull request for the current branch, with a description a reviewer can trust —
grounded in the actual diff and linked to the plan or ticket the work came from. InsureFlow is a
polyglot monorepo; the root `Makefile` is the single source for the build/test lifecycle and `gh` is
the GitHub CLI. The remote is `MecksonMfema/medfund`; the base branch is `main`.

## Create depth: `full` (default) vs `quick`

Use the input supplied with this invocation. The optional **`quick`** token (`create-pr quick`) produces
a short, low-ceremony PR sized for small, low-risk changes — docs, a tightly-scoped config/CI tweak, a
mechanical refactor, or a routine `main`-merge sync. No token means **full**; an unrecognised token
means ask whether they meant `quick` or full.

When `quick` is set, apply these deltas; **everything else is identical to full mode** — steps 0, 1, 2,
3, 7, and 8 (pre-flight, branch/PR checks, push, create, confirm) run the same either way:

| Step | Full | Quick |
|---|---|---|
| **4** analyze | read the whole diff, trace referenced files, identify breaking changes | skim `--stat` + commit subjects; a sentence or two on what changed and why |
| **5** verify | run the earned `make` targets (below) | same — `quick` doesn't skip verification |
| **6** description | Summary + Changes + How to Verify + Notes | a 2–4 line summary (what / why this shape / what to watch), a short bullet list only if there are distinct changes worth listing |

If unsure whether a change is small enough for `quick`, default to `full`.

## Step 0 — stale merge-base pre-flight (both modes, first)

We reuse long-lived branch names across tickets, and a merged PR can leave a branch's merge-base with
`origin/main` stale — so `git diff origin/main...HEAD` (three-dot, what GitHub shows) can replay
already-merged work and double the PR's apparent size. **This check is silent on a clean branch** —
if 0b prints nothing, say nothing and go to step 1.

**0a. Fetch first** — the check is meaningless against a stale ref: `git fetch origin`.

**0b. Compare the two diff forms.** Three-dot is `merge-base..HEAD` (the PR as GitHub renders it);
two-dot is the real content difference from `main`'s tip:

```bash
git diff origin/main...HEAD --stat   # three-dot — what GitHub will show
git diff origin/main HEAD --stat     # two-dot — the actual content difference
```

The decision rule is the **one-directional** set — files the three-dot diff claims you changed that the
two-dot diff says are already identical to `main`:

```bash
comm -23 <(git diff origin/main...HEAD --name-only | LC_ALL=C sort) \
         <(git diff origin/main HEAD --name-only | LC_ALL=C sort)
```

(`LC_ALL=C` on both sides forces the byte ordering `comm` assumes.) Any file it prints is already-merged
work riding on a stale base. Do **not** test for superset/equality — on an active repo the two lists
differ in both directions even when nothing is wrong; only the `comm -23` direction is meaningful.

**0c. If files are flagged, find the replant point** from the branch's last merged PR, then **report and
ask — never rebase unprompted**:

```bash
gh pr list --state merged --head "$(git rev-parse --abbrev-ref HEAD)" --limit 1 --json number,headRefOid,title
```

Show the user the extra files, the merged PR, and the exact `git rebase --onto origin/main <headRefOid>`
(or, if all the work is uncommitted, `git switch -c <new-branch> origin/main`). Re-run 0b after; only
continue once it prints nothing.

## Steps

1. **Check current branch state:**
   - `git rev-parse --abbrev-ref HEAD`. If on `main`, tell the user they need a feature branch and stop.
   - `git status --porcelain`. If there are uncommitted changes, ask whether to commit first (offer the
     `commit` skill) — don't commit silently.

2. **Check for an existing PR:** `gh pr view --json url,number,title,state 2>/dev/null`. If one exists,
   say so and ask whether to update it (describe it) or run `review-pr` instead.

3. **Push the branch if needed:** `git ls-remote --heads origin $(git rev-parse --abbrev-ref HEAD)`; if
   absent, `git push -u origin $(git rev-parse --abbrev-ref HEAD)`.

4. **Gather and analyze the changes:**
   - `git log origin/main..HEAD --oneline`
   - `git diff origin/main...HEAD --stat` and the full `git diff origin/main...HEAD`
   - Read the whole diff. Read files it references but doesn't show. Separate user-facing changes from
     internal detail. Flag breaking changes and migration requirements (new Flyway files, event-schema
     changes).

5. **Verify — run the `make` targets the diff earns, and CI does the rest.** Don't invent targets or run
   raw `gradlew`/`go test`/`ng` when a `make` target exists (see the `implement-plan` skill's table).
   Most suites also run per-language on GitHub Actions (`.github/workflows/{java,go,elixir,python,angular,e2e}.yml`)
   once the PR is open — name those in *How to Verify* rather than re-running everything locally.

   | The diff touches… | Run before opening |
   |---|---|
   | Java (`services/java/**`) | `make test-java` |
   | Java + a Flyway migration, R2DBC repo, or Kafka consumer/producer | also `make test-integration` (Testcontainers; needs `make infra` up) |
   | Go (`services/go/**`) | `make test-go` |
   | Elixir (`services/elixir/**`) | `make test-elixir` |
   | Python (`services/python/**`) | `make test-python` |
   | Angular (`clients/angular/**`) | `make test-angular`; drive the real page via the `verify` skill for any visual change |
   | Flutter (`clients/flutter/**`) | `make test-flutter` |

   Record what you ran and the result — a green `make` run or, when you're leaving it to CI, which
   workflow covers it. Never claim a suite passed without having run it or linked the CI run.

6. **Link the originating artifact, then write the description.**

   - **Find the plan/ticket this PR implements** (no external tracker — the artifact lives in the repo):
     prefer a `thoughts/shared/plans/…md`, `thoughts/shared/tickets/…md`, or `thoughts/shared/research/…md`
     already named in this conversation; else match the branch slug against `thoughts/shared/plans/` and
     `thoughts/shared/tickets/`. If several match, ask which; if genuinely none, confirm that explicitly
     and use a one-line `_No linked plan/ticket — {reason}._` note rather than silently skipping.
   - Build the GitHub URL on the branch so it tracks the latest file:
     `https://github.com/MecksonMfema/medfund/blob/<branch>/thoughts/shared/plans/<slug>.md`
     (use `git rev-parse --abbrev-ref HEAD` for `<branch>`). Confirm the path exists with
     `git cat-file -e HEAD:thoughts/shared/plans/<slug>.md`.
   - **Title** (50–72 chars): this is the merge subject and the lasting trace in `git log`. Say what
     changed and to what — not the branch name, not "fix bug".
   - **Description** — save to `thoughts/shared/prs/<branch>_description.md` (create the dir if needed),
     using the template at the bottom. The first line is the artifact link; then a blank line and the
     Summary. Fill each section from your analysis:
     - **Summary — three things, in order:** (1) what changed, in one or two plain sentences; (2) why this
       shape — the decision behind it and the alternative not taken (omit only if there was no choice);
       (3) what to look at hardest — where the risk is or what you're least sure of. No `file:line`, no
       command output here.
     - **Changes** — the specific changes, grouped by service/language when it spans several.
     - **How to Verify → Automated** — the `make` targets you ran with their result, plus the GitHub
       Actions workflow(s) that cover this PR.
     - **How to Verify → Manual** — what a human clicks or observes (Swagger at `/swagger-ui`, a gateway
       curl with a tenant JWT, an Angular page), with real URLs/ports.
     - **Notes** — breaking changes, new migrations, new Kafka topics, and anything deferred with its
       reason. Call out **cross-language ripples**: a new/changed Kafka event schema has Go/Elixir/Python
       consumers; a changed Angular DTO has a Java producer; a new gateway route has a backend service.

7. **Create the PR** from the saved file:
   ```bash
   gh pr create --title "PR Title" --body-file thoughts/shared/prs/<branch>_description.md --base main
   ```
   If `gh` complains about no default remote, run `gh repo set-default MecksonMfema/medfund` first.

8. **Confirm and point at the self-review:**
   - Print the PR URL and restate any manual verification the reviewer must do.
   - The team runs the author's self-review **after** the PR exists and posts it to the PR (that posted
     comment is the record a reviewer's own `review-pr` run checks for). So close with:

     > *"Recommended: a full self-review before a reviewer sees this — `review-pr` over the whole diff,
     > then post it to the PR. Want me to run it now?"*

     Skip only if the user already ran the self-review on this PR.

## Important notes

- Be thorough but scannable. Put breaking changes and migration notes where they can't be missed.
- Honour the **framing rule** — line-neutral wording ("claim", "policy", "beneficiary") in PR text
  unless the change is genuinely medical-vertical-specific.
- Note which services/clients are affected and which language each lives in — reviewers sweep
  cross-language, and the PR body is where the ripple is first declared.
- Per the harness, PR **bodies** end with the `🤖 Generated with [Claude Code]` trailer. Commits are the
  `commit` skill's concern — don't commit here.

## Default Template

```markdown
[Plan: {short title}](https://github.com/MecksonMfema/medfund/blob/<branch>/thoughts/shared/plans/<slug>.md)
<!-- or [Ticket: …]/[Research: …]; or: _No linked plan/ticket — {reason}._ -->

## Summary
<!-- What changed (1–2 sentences) / why this shape, and the alternative not taken / what to look at hardest. No file:line, no command output. -->

## Changes
<!-- grouped by service/language when it spans several -->
-

## How to Verify
### Automated
- [ ] `make test-<lang>` (+ `make test-integration` if earned) — {result}
- [ ] GitHub Actions: `.github/workflows/<lang>.yml` on this PR
### Manual
- [ ] {what a human clicks/observes, with real URLs/ports}

## Notes
<!-- breaking changes, new Flyway migrations, new Kafka topics, cross-language consumer ripples, anything deferred + why -->

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

## Quick Template (`create-pr quick`)

```markdown
[Plan: {short title}](https://github.com/MecksonMfema/medfund/blob/<branch>/thoughts/shared/plans/<slug>.md)
<!-- repo artifact link optional if none: _No linked plan/ticket — {reason}._ -->

## Summary
<!-- 2–4 lines: what changed, why this shape, anything worth looking at hardest -->

<!-- optional, only if there are distinct changes worth listing: -->
## Changes
-

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```
