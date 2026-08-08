# Workflow artifacts (RPI loop)

Working documents for the Research → Plan → Implement (RPI) loop.

| Folder | What lives here | Skill that writes it |
|---|---|---|
| `research/` | Cited findings that answer a codebase question or ground a ticket | `research-codebase` |
| `plans/` | Detailed, phased, code-level implementation plans | `create-plan` |
| `tickets/` | Intent artifacts — what should be true and why (code-free) | (manual, or a ticket-authoring skill) |
| `specs/` | Longer intent documents with settled Decisions | (manual, or a spec-authoring skill) |

Each RPI step runs in its **own context** — clear between steps. The file on disk is what survives the clear, which is why the frontmatter must be complete and any dev-supplied steer must be recorded verbatim.

Filenames are dated (`YYYY-MM-DD-<slug>.md` for research and plans) so they sort chronologically. Plans and research may prefix a ticket handle if one exists (e.g. `2026-08-08-CLAIMS-142-provider-payout.md`).

Plans and research are **working artifacts** — they don't have to be committed. Tickets and specs are intent artifacts and typically do get committed.
