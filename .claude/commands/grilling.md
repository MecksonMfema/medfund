---
name: grilling
description: Relentless one-question-at-a-time interview that stress-tests a plan, ticket set, or design before any code is written, then writes the settled decisions back into the documents. Use when the user wants a plan or design challenged, or uses any 'grill' trigger phrase; not for writing the plan itself or for implementing it.
argument-hint: "[what to grill — a plan, a ticket set, a research doc]"
---

# Grilling

Interview the user relentlessly about every aspect of the plan until you reach a shared understanding.
Walk down each branch of the design tree, resolving dependencies between decisions one-by-one.

**Ask one question at a time and wait for the answer before continuing.** Several at once is
bewildering and gets shallow answers.

**If the codebase can answer a question, go and read the codebase instead of asking.** The user should
only ever be asked things that are genuinely a matter of judgement or preference.

**Offer your own recommended answer with every question**, so the user reacts to a proposal rather
than starting from blank.

## 1. Before you ask anything, re-verify the ground

Grilling material — a research doc, a ticket set, a plan — carries inherited claims. Those claims are
the most expensive thing in the room, because every decision downstream of a wrong one is wasted. So
the first pass is not questions, it is verification:

- **Check every `file:line` citation** the material makes. Open the file, read the line.
- **Check every claim of the form "already built", "already merged", "already true", "this is
  blocked".** PR numbers get transposed, a PR gets closed rather than merged, a follow-up quietly
  shipped the thing. A single wrong premise can invalidate a whole document.
- **Prefer a measurement over an argument.** If a question is "will this unique index collide with
  existing data?", the answer is a `COUNT(*)`, not a paragraph. Blockers routinely dissolve when
  someone finally runs the query. Where production data settles it, query production against a
  read-only role (see `.claude/infrastructure.md` for the read-only Postgres role and how to reach
  it) and quote the numbers with the date you took them. In a multi-tenant world, name the tenant
  schema you queried (`tenant_<slug>`) so the count is reproducible.
- **Fan heavy investigation out to a subagent.** A deep codebase or web investigation returns a lot
  of noise; run it in a subagent and keep the grilling session's context for the interview. Say so
  when you do.

Report what the verification changed before the first question. If a premise turned out to be false,
that reshapes the question list — say which questions it deleted or created.

## 2. How to put a question

Every question gets three parts, in this order:

1. **Plain-terms prose.** A short paragraph explaining the surrounding context in simple language —
   what this thing is, what it currently does, why anyone cares. Assume the user has not read the
   spec section you are quoting. Do not open with jargon and do not assume that a term you introduced
   an hour ago is still loaded.
2. **A reference table.** Every claim you are relying on, with **the full local path and line number**
   — `services/java/claims-service/src/main/java/com/medfund/claims/service/AdjudicationService.java:142`
   or `.claude/adjudication.md:88`, never a bare `D12` or `§6`. The user reads the surrounding context;
   a shorthand they have to go hunt for is a dead reference.
3. **The question itself**, asked with **3–4 concrete options** (A/B/C… — use the client's
   option-picker if it has one). Put your recommendation **first** and mark it `(Recommended)` **in
   the option label**, not only in the description. Give each option an honest downside — an option
   set where three options are obviously bad is not a choice, it is theatre.

If the user asks for the pros and cons of an option, or for an expansion in simpler terms, answer
that fully before re-asking. A question the user does not understand yet is not ready to be answered.

## 3. Not everything is a decision

Some forks are settled by fact, not by preference: the code already does X, the constraint makes Y
impossible, the spec's own rule forbids Z. **Do not put those to the user as a choice.** State the
finding, state what follows from it, and move on. Record them separately from the decisions — a
"settled by fact, not by preference" section — so a later reader can tell which rulings were the
user's call and which were forced.

Equally, some questions are unbuildable as written. Say so plainly and re-spec the item rather than
asking the user to choose between three impossible things.

## 4. Keep a running record as you go

Write each settled decision to the scratchpad the moment it is settled — question, options offered,
what was chosen, and the reasoning. The apply step at the end must not depend on remembering the
conversation, and a long grilling session will be compacted before it finishes.

**Number the decisions with a prefix that cannot collide with the source material's own numbering.**
If the spec you are grilling numbers its decisions `D1…D25`, number yours `G1…Gn`. Collisions are
invisible while you are writing and unresolvable a month later, when a ticket saying "D13" could mean
either.

## 5. Parking, and what is owed back to the author

- **Blocked on an external party?** Park that one question, say it is parked, and keep grilling.
  Don't stall the session waiting for a reply.
- **Needs the spec author's agreement?** Draft a plain-English message for the user to send — under
  `/tmp`, no jargon, no shorthand, stating the problem, the evidence and the specific thing you want
  changed. Ask the user before sending anything on their behalf.
- **Found something the spec gets wrong?** Collect it. The grilled document should end with an
  explicit list of what is owed back to the spec authors: corrections, contradictions between spec
  sections, and criteria that cannot be met as written.
- **Found a live defect along the way?** Grilling surfaces real bugs. Record them as follow-ups —
  don't expand the scope of what you're grilling to fix them.

## 6. Applying the decisions

When the user says apply, write the decisions into the documents themselves. This is the deliverable —
a decision that lives only in the transcript did not happen.

- **Strike through superseded text (`~~…~~`) rather than deleting it**, with the replacement next to
  it. The trail of what was previously believed, and why it changed, is most of the value.
- **Update the status banners.** If a blocker dissolved, the blocked banner goes; if it survived, say
  which one and why.
- **Fix the Context sections, not just the Decisions sections.** A ticket whose Context still asserts
  the thing its new decision exists to correct will mislead whoever builds it.
- **Correct facts wherever they appear**, including in documents that were not the subject of the
  grilling but repeat the same wrong claim.
- **Don't rename files just because their title changed** if other documents cite the path. Retitle
  and note it.

## 7. Then self-review the applied edits

The apply step is a large mechanical edit across many files and it will contain mistakes. Re-read the
result specifically hunting for:

- **Dangling references** — a decision number cited but never defined, or defined twice with two
  different meanings.
- **Collisions** with the source material's numbering that survived the renumber.
- **Half-converted text** left by a search-and-replace (`D5/G6 ·`).
- **Contradictions** between a document's own sections, and between a summary claim and the
  dependency list beneath it.
- **Stale or non-local paths** — a Windows path, a URL where the user asked for a local path.
- **Ordering** — decisions listed out of numeric order read as an omission.

When a verification command reports a problem, check the command before you trust it — a wrong regex
reporting a phantom defect wastes more time than the defect would have. If you got something wrong,
say so in one sentence and move on.

## Why this exists

The expensive mistakes in this repo have been decisions, not typos. A plan that survives an
adversarial interview — grounded in verified fact rather than inherited assertion — is worth more than
one that was merely written down quickly. In a multi-service polyglot repo the surface area of
"decisions I forgot to challenge" is bigger than in a single-app codebase: a Kafka event schema, a
tenant-schema migration ordering, a currency-conversion boundary, a rules-engine template — each is
its own decision tree.

## Pairs with

Run it **before** `create-plan`, not after — the point is to change the design while changing it is
still cheap. Run it **again** on an existing plan or ticket set whenever the codebase has moved
underneath it: a plan written against a codebase from three weeks ago is a research doc, not a plan.
Finish with `commit`.
