---
date: 2026-08-10
git_commit: c248073df71e8e42d035addfd5a090e5b39bacba
branch: main
ticket: none
research:
  - thoughts/shared/research/2026-08-10-debit-and-credit-notes-in-insurance.md
steer: "implement Option 1 per the Grilling decisions section (G1-G16) already in the doc; no further scope debate needed — the decision forks are settled"
services_touched: [finance-service, tenancy-service, user-service, audit-service, angular]
status: draft
---

# Rename `Adjustment` → `Note` (with direction) and Wire Notes into `PaymentAdvice`

## Overview

Fold the finance-service `Adjustment` entity and the V016 memo-only `debit_notes`/`credit_notes` tables into a single `notes` table with a `direction ∈ {DEBIT, CREDIT}` column, then teach `PaymentAdviceService` to render every payee-bound note on that payee's `PaymentAdvice` in the correct period. This resolves the "naming misfire" identified in the research doc — the entity that actually moves money (Adjustment) gets the domain-standard name (Note), while the memo-only tables that borrowed the domain vocabulary without ledger effect get retired. Sixteen decision forks are pre-settled in the research doc's `## Grilling decisions` section; this plan implements them.

## Deviations

- **2026-08-10 — Migration number is V074, not V072.** Plan was written against branch `main@c248073`; between then and implementation, V072 (`creditors_unification_and_member_settlement`) and V073 (`permission_swap_billing_creditors`) landed in `services/java/tenancy-service/src/main/resources/db/migration/tenant/`. Using V074 preserves the "single-file migration" invariant from Migration Notes.
- **2026-08-10 — Phase 2 IT scenarios deferred to manual verification.** finance-service has no existing PaymentAdvice IT harness (the closest is `CtcPaymentServiceIT`, whose `db/test-migration/V001__ctc.sql` covers only CTC + member_payables tables). Building `PaymentAdviceServiceNotesIT` would require a substantial new test-migration script wiring together `notes`, `claims`, `payment_runs`, `payment_run_items`, `payment_advices`, `payment_advice_lines`, `ctc_payments`, `advance_payments`, `providers`, and `members`. The core Phase-2 code changes (noteLines method, formula update, backPeriodRunId column) compile and existing `PaymentAdviceServiceTest` mock-based tests still pass; the 6 scenarios remain to be verified against a live dev tenant per the "Manual Verification" checklist and can be promoted to full IT in a follow-up.
- **2026-08-10 — Phase 4 consolidated to a single `/notes` surface.** Per user steer, dropped the separate `/debit-notes` and `/credit-notes` route+page variants (and the direction-pinned `/new` forms) in favour of one unified `/tenant/finance/notes` list with a Direction filter chip. Sidebar now has a single "Notes" entry. `/debit-notes`, `/credit-notes`, `/adjustments` (and their `/new` forms) all become `pathMatch: 'full'` redirects to `/notes` (kept for one release for bookmark safety). This simplifies `NotesListComponent` (no more `pinnedDirection` route-data path) and `NoteFormComponent` (no more `presetDirection` route lookup).

## Current State Analysis

**Finance-service today** has two parallel note stories:

- `Adjustment` entity + `adjustment_type ∈ {IN_PAYMENT, PAYOUT, NON_CASH_IN, NON_CASH_OUT, TAX_WITHHELD}` with `status ∈ {pending, approved, applied, cancelled}` and payee columns (`provider_id`, `member_id`, at least one required per CHECK). Only `TAX_WITHHELD` has production callers — via `PaymentAdviceService.java:326-333` which reads adjustments as a source for the TAX_WITHHELD advice line. Fields: `id`, `adjustment_number`, `provider_id?`, `member_id?`, `adjustment_type`, `amount`, `currency_code`, `reason`, `status`, `approved_by?`, `approved_at?`, `created_at`, `updated_at`, `created_by?` (`V016__finance_schema.sql:67-85`).
- `DebitNote` / `CreditNote` entities (`V016:200-220`) — memo-only, no payee, no ledger effect, described in the SQL comment as "one-off bank-fee write-offs, goodwill credits." Written to by `NotesController.java:65-79,114-128` (POST) and read by that same controller's paginated GET. Nothing else reads or writes them.

**Angular today** has:
- `AdjustmentsListComponent` at `/tenant/finance/adjustments` (`finance.routes.ts:148-178`) with status + type filters, target selection (member/provider), approve/apply/cancel actions.
- Memo `NotesListComponent` at `/tenant/finance/debit-notes` and `/tenant/finance/credit-notes` (`finance.routes.ts:180-190`) branching on route data `mode: 'debit' | 'credit'`.
- Sidebar (`operational-nav.ts:141-143`) surfaces three separate top-level entries (Adjustments, Debit Notes, Credit Notes), all gated by the single flat permission `finance:post_adjustments`.

**PaymentAdvice today** carries 6 line types (`.claude/payments.md:386-395`): `CARRY_FORWARD`, `CLAIM_PAID`, `CTC_APPLIED`, `ADVANCE_APPLIED`, `TAX_WITHHELD`, `SHORTFALL`. `TAX_WITHHELD` is the only line sourced from `adjustments`; the query at `PaymentAdviceService.java:326-333` filters by `adjustment_type='TAX_WITHHELD' AND currency_code=:currency AND created_at ∈ window` — importantly, **it does not filter on status**, which means pending/approved/cancelled TAX_WITHHELD rows are counted too (a latent bug this plan fixes).

**No cross-service consumers of `adjustments`** exist outside finance-service. Docs (`.claude/portals.md:511-517`) promise granular per-note permissions and dedicated management pages that the code has not built.

### Key discoveries

- The 4 non-TAX `adjustment_type` values (IN_PAYMENT, PAYOUT, NON_CASH_IN, NON_CASH_OUT) have **zero production callers** — grep across `services/java/` confirms. Only TAX_WITHHELD is real. This dissolves the migration backfill risk.
- Existing controller/service tests set `adjustmentType="credit"` (`AdjustmentControllerTest.java:65,86`, `AdjustmentServiceTest.java:100,287`), a value that isn't even in the current CHECK. These tests must hit a mocked repo; they get rewritten in phase 1 regardless.
- The Adjustment table has a `CHECK (provider_id IS NOT NULL OR member_id IS NOT NULL)` at V016:84 that contradicts the plan's payee-less MEMO type — the migration must relax it.
- The reversal/compensating-entry pattern used by `AdvancePayment` and `CtcPayment` is **not** applied to `Adjustment` today (no `reverses_adjustment_id`, no CHECK). Adding it is a new column, not a preserved column.
- Go/Python/Flutter templates for advice PDFs/emails iterate the payload array — no hardcoded line-type names — so `NOTE_DEBIT`/`NOTE_CREDIT` don't require non-Java changes.

## Desired End State

- **Single ledger table**: `notes` (renamed from `adjustments`) carrying `direction ∈ {DEBIT, CREDIT}`, `note_type ∈ {TAX_WITHHELD, WRITE_OFF, GOODWILL, ENDORSEMENT_PREMIUM, PREMIUM_REFUND, PROVIDER_OVERPAYMENT_RECOVERY, MEMO}`, `type ∈ {ORIGINAL, REVERSAL}`, `status ∈ {pending, approved, applied, reversed}`, `posted_at`, `reverses_note_id?`, plus the original Adjustment columns.
- **V016 `debit_notes` / `credit_notes` tables dropped**; their rows migrated in as `note_type='MEMO'`, payee columns NULL.
- **PaymentAdvice** carries two new line types: `NOTE_CREDIT` (reduces `net_due_amount`, sourced from `direction='DEBIT'` notes) and `NOTE_DEBIT` (increases `net_due_amount`, sourced from `direction='CREDIT'` notes). Both apply to PROVIDER and MEMBER advices. Only `status='applied'` notes count. `TAX_WITHHELD` remains a specialised line (own query, own advice line-type), separate from the generic `NOTE_*` buckets. Late-arriving notes carry a `back_period_run_id` FK on `payment_advice_lines` pointing to the run whose advice would have carried them.
- **Permissions**: `finance.notes:read`, `finance.notes:write`, `finance.notes:approve` replace the flat `finance:post_adjustments`. During phase 3 and one release beyond, the old flat permission auto-expands to all three via a compat mapping.
- **Angular finance section**: three sidebar entries — Debit Notes (`/tenant/finance/debit-notes`, direction=DEBIT), Credit Notes (`/tenant/finance/credit-notes`, direction=CREDIT), All Notes (`/tenant/finance/notes`, both). `/tenant/finance/adjustments` and subroutes → 301 redirects for one release.
- **Kafka audit events** emit `entityType='Note'`; historic audit_events rows backfilled once from `'Adjustment'`/`'DebitNote'`/`'CreditNote'` to `'Note'`, preserving the original in `original_entity_type`.
- **`net_due_amount` formula** on PaymentAdvice becomes:
  `carried_in + claims_paid + note_debits − ctc_applied − advance_applied − tax_withheld − shortfall − note_credits`

### Verification

- Post a `direction=DEBIT, note_type=PROVIDER_OVERPAYMENT_RECOVERY` note against a provider, execute a payment run — provider's next advice shows a NOTE_CREDIT line reducing net_due_amount by the note's amount.
- Post a `direction=CREDIT, note_type=GOODWILL` note against a member, execute a payment run — member's next advice shows a NOTE_DEBIT line increasing net_due_amount.
- Post a `note_type=MEMO` note (no payee) — appears on the Notes list; does **not** appear on any advice.
- Reverse an applied note that already shipped to a prior advice — reversal appears on the current advice as an opposite-direction line; historic advice unchanged.
- `TAX_WITHHELD` notes render as `TAX_WITHHELD` advice lines, not `NOTE_CREDIT` (no double-count).
- `curl` old `/api/v1/debit-notes` returns 404 or 301; `curl` new `/api/v1/notes?direction=DEBIT` returns the same rows (post-migration).
- Angular `/tenant/finance/adjustments` redirects to `/tenant/finance/notes` with a `direction=` query param preserved when it can be inferred.

## What We're NOT Doing

- **Not touching contributions-service arrears** (G10). `SCHEME_UPGRADE_ARREARS` / `SCHEME_DOWNGRADE_REBATE` stay as `transactions` on the contributions ledger and continue to appear on member invoice snapshots. Mirroring them into `notes` is a follow-up ticket.
- **Not widening the payee dimension** (G11). No `group_id` or `reinsurer_id` columns. Notes remain provider XOR member (plus MEMO payee-less).
- **Not adding linked-document FKs** (invoice_id, claim_id, payment_run_id) to notes. The research doc's follow-up implied these; they don't exist today and are out of phase 1 scope.
- **Not removing the `finance:post_adjustments` compat mapping** in this plan. It stays for one release; removal is a follow-up.
- **Not adding FX conversion** to the advice generator (G14). Notes bucket per currency exactly.
- **Not updating Go/Python/Flutter** — G12 verified their templates are line-type-agnostic.
- **Not building an "issue note to payee" delivery mechanism** (email/PDF of the note itself). Notes appear on the payee's advice, which already ships via the advice-delivery pipeline. A note-as-document delivery is a separate feature.

## Implementation Approach

Four phases, each independently verifiable, applied in this order to keep rollback tractable:

1. **Backend rename + schema migration** — Java entity/repo/service/controller rename; single Flyway migration doing table rename, column additions, backfill, memo-table row migration and drop, CHECK relaxation, payment_advice_lines extension; Kafka event `entityType='Note'` with a one-shot audit-service backfill. Docs inline update to `.claude/architecture.md`.
2. **PaymentAdvice integration** — `PaymentAdviceService` gains `NOTE_DEBIT`/`NOTE_CREDIT` computation for both PROVIDER and MEMBER, exclusion of MEMO/TAX_WITHHELD from the generic bucket, `back_period_run_id` handling for late-arriving notes, `status='applied'` filter (fixing the latent TAX_WITHHELD bug). Docs inline update to `.claude/payments.md`.
3. **Permission split + compat mapping** — `PermissionCatalogue` gains three new permissions, resolver expands `finance:post_adjustments` to all three during login for the compat window. Angular route guards and permission constants updated. Docs inline update to `.claude/portals.md` permission table.
4. **Angular UI + advice detail rendering** — repurpose `AdjustmentsListComponent` → `NotesListComponent` (deleting the memo-only one), rename all types + service methods, wire the three routes + `/adjustments` redirects + sidebar, extend `payment-advice-detail` to render `NOTE_DEBIT`/`NOTE_CREDIT` sections with drill-in links. Docs inline update to `.claude/portals.md` route table. Playwright golden-path spec.

Phase 3 runs before phase 4 so the Angular UI never depends on permissions that haven't been provisioned yet; the compat mapping means existing tenant role assignments carry over the day of phase 4 cutover.

---

## Phase 1: Backend rename + schema migration

### Overview

Rename the `adjustments` table to `notes` and expand it with the columns needed by later phases. Delete the memo-only `debit_notes`/`credit_notes` tables and migrate their rows in as `note_type='MEMO'`. Rename the Java entity, repository, service, controller, and DTO stack. Cut over Kafka audit-event `entityType` in one deploy with a one-shot backfill script for historic rows.

### Changes Required

#### 1. Flyway tenant migration

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V074__rename_adjustments_to_notes.sql` (V073 is the current highest — see Deviations)

**Changes**: single migration doing all schema work in one transaction so partial failure rolls back cleanly.

```sql
-- Add new columns before the rename so the CHECK constraints reference the final table name.
ALTER TABLE adjustments
    ADD COLUMN direction        VARCHAR(6),
    ADD COLUMN posted_at        TIMESTAMPTZ,
    ADD COLUMN reverses_note_id UUID,
    ADD COLUMN type             VARCHAR(10) DEFAULT 'ORIGINAL' NOT NULL,
    ADD COLUMN note_type        VARCHAR(40);

-- G1 direction backfill.
UPDATE adjustments SET direction = CASE adjustment_type
    WHEN 'IN_PAYMENT'    THEN 'CREDIT'
    WHEN 'NON_CASH_IN'   THEN 'CREDIT'
    WHEN 'PAYOUT'        THEN 'DEBIT'
    WHEN 'NON_CASH_OUT'  THEN 'DEBIT'
    WHEN 'TAX_WITHHELD'  THEN 'DEBIT'
END;

-- G9a: only TAX_WITHHELD has production callers; other values are absent in every real tenant.
UPDATE adjustments SET note_type = adjustment_type WHERE adjustment_type = 'TAX_WITHHELD';
-- Any surviving row without a mapping fails the NOT NULL below; that's the correct behaviour.

ALTER TABLE adjustments ALTER COLUMN direction SET NOT NULL;
ALTER TABLE adjustments ALTER COLUMN note_type SET NOT NULL;
ALTER TABLE adjustments DROP COLUMN adjustment_type;

-- Backfill posted_at from approved_at (fallback created_at) for existing applied rows.
UPDATE adjustments
   SET posted_at = COALESCE(approved_at, created_at)
 WHERE status = 'applied';

-- G3 status remap.
UPDATE adjustments SET status = 'reversed' WHERE status = 'cancelled';
ALTER TABLE adjustments DROP CONSTRAINT IF EXISTS adjustments_status_check;
ALTER TABLE adjustments ADD CONSTRAINT notes_status_check
    CHECK (status IN ('pending','approved','applied','reversed'));

-- G3 compensating-entry CHECK.
ALTER TABLE adjustments ADD CONSTRAINT notes_reversal_check
    CHECK (
        (reverses_note_id IS NULL AND type = 'ORIGINAL')
        OR (reverses_note_id IS NOT NULL AND type = 'REVERSAL')
    );
ALTER TABLE adjustments ADD CONSTRAINT notes_type_check
    CHECK (type IN ('ORIGINAL','REVERSAL'));

-- G9 note_type CHECK.
ALTER TABLE adjustments ADD CONSTRAINT notes_type_domain_check
    CHECK (note_type IN ('TAX_WITHHELD','WRITE_OFF','GOODWILL','ENDORSEMENT_PREMIUM',
                         'PREMIUM_REFUND','PROVIDER_OVERPAYMENT_RECOVERY','MEMO'));

-- G4 relax payee-required CHECK to allow MEMO.
ALTER TABLE adjustments DROP CONSTRAINT IF EXISTS adjustments_provider_or_member_check;
ALTER TABLE adjustments ADD CONSTRAINT notes_payee_or_memo_check
    CHECK (provider_id IS NOT NULL OR member_id IS NOT NULL OR note_type = 'MEMO');

-- Rename adjustment_number column and reprefix rows per G16.
ALTER TABLE adjustments RENAME COLUMN adjustment_number TO note_number;
UPDATE adjustments SET note_number = CASE
    WHEN note_type = 'MEMO'   THEN 'MEMO-' || SUBSTRING(note_number FROM 5)
    WHEN direction = 'DEBIT'  THEN 'DN-'   || SUBSTRING(note_number FROM 5)
    WHEN direction = 'CREDIT' THEN 'CN-'   || SUBSTRING(note_number FROM 5)
END
WHERE note_number LIKE 'ADJ-%';

-- Rename table.
ALTER TABLE adjustments RENAME TO notes;
ALTER INDEX idx_adjustments_provider RENAME TO idx_notes_provider;
ALTER INDEX idx_adjustments_member   RENAME TO idx_notes_member;
ALTER INDEX idx_adjustments_status   RENAME TO idx_notes_status;
DROP INDEX IF EXISTS idx_adjustments_type;
CREATE INDEX idx_notes_type ON notes(note_type);
CREATE INDEX idx_notes_posted_at ON notes(posted_at) WHERE status = 'applied';
CREATE INDEX idx_notes_reverses ON notes(reverses_note_id) WHERE reverses_note_id IS NOT NULL;

-- G4 migrate V016 memo tables into notes as type='MEMO'.
INSERT INTO notes (id, note_number, amount, currency_code, reason, status,
                   created_at, updated_at, created_by,
                   direction, type, note_type, posted_at)
SELECT id,
       'MEMO-' || LPAD((100000 + (row_number() OVER (ORDER BY created_at))::int)::text, 6, '0'),
       amount, currency_code, notes, 'applied',
       created_at, created_at, created_by,
       'DEBIT', 'ORIGINAL', 'MEMO', created_at
  FROM debit_notes;
INSERT INTO notes (id, note_number, amount, currency_code, reason, status,
                   created_at, updated_at, created_by,
                   direction, type, note_type, posted_at)
SELECT id,
       'MEMO-' || LPAD((200000 + (row_number() OVER (ORDER BY created_at))::int)::text, 6, '0'),
       amount, currency_code, notes, 'applied',
       created_at, created_at, created_by,
       'CREDIT', 'ORIGINAL', 'MEMO', created_at
  FROM credit_notes;

DROP TABLE debit_notes;
DROP TABLE credit_notes;

-- Extend payment_advice_lines line_type CHECK to include NOTE_DEBIT and NOTE_CREDIT.
-- Also add back_period_run_id column for late-arriving notes (G2).
ALTER TABLE payment_advice_lines DROP CONSTRAINT IF EXISTS payment_advice_lines_line_type_check;
ALTER TABLE payment_advice_lines ADD CONSTRAINT payment_advice_lines_line_type_check
    CHECK (line_type IN ('CARRY_FORWARD','CLAIM_PAID','CTC_APPLIED','ADVANCE_APPLIED',
                         'TAX_WITHHELD','SHORTFALL','NOTE_DEBIT','NOTE_CREDIT'));
ALTER TABLE payment_advice_lines ADD COLUMN back_period_run_id UUID;
CREATE INDEX idx_pal_back_period_run ON payment_advice_lines(back_period_run_id)
    WHERE back_period_run_id IS NOT NULL;

-- Update the reference_type column to allow 'note' alongside the existing values.
-- (No CHECK on reference_type today, so no DDL needed; document only.)
```

#### 2. Java entity + repository + service + controller rename

**Files**: rename in bulk under `services/java/finance-service/src/main/java/com/medfund/finance/`:

| From | To |
|---|---|
| `entity/Adjustment.java` | `entity/Note.java` |
| `repository/AdjustmentRepository.java` | `repository/NoteRepository.java` |
| `repository/AdjustmentQueryRepository.java` | `repository/NoteQueryRepository.java` (collision — delete the memo one FIRST) |
| `service/AdjustmentService.java` | `service/NoteService.java` |
| `controller/AdjustmentController.java` | `controller/NoteController.java` |
| `dto/AdjustmentResponse.java` | `dto/NoteResponse.java` |
| `dto/AdjustmentRow.java` | `dto/NoteRow.java` |
| `dto/CreateAdjustmentRequest.java` | `dto/CreateNoteRequest.java` |
| `dto/AdjustmentFilterParams.java` | `dto/NoteFilterParams.java` |
| `exception/AdjustmentNotFoundException.java` | `exception/NoteNotFoundException.java` |

**Delete outright**:
- `entity/DebitNote.java`, `entity/CreditNote.java`
- `repository/DebitNoteRepository.java`, `repository/CreditNoteRepository.java`
- Old `repository/NoteQueryRepository.java` (memo one — must be deleted before the rename above)
- `controller/NotesController.java` (memo one)
- `dto/NoteDtos.java` (the memo DTOs — collision with new NoteResponse)

**New `Note` entity** (`entity/Note.java`) — R2DBC entity per Java conventions (`@Getter @Setter`, explicit equals/hashCode on id):

```java
@Getter
@Setter
@Table("notes")
public class Note {
    @Id private UUID id;
    @Column("note_number")     private String noteNumber;
    @Column("provider_id")     private UUID providerId;
    @Column("member_id")       private UUID memberId;
    @Column("direction")       private String direction;   // DEBIT | CREDIT
    @Column("note_type")       private String noteType;    // TAX_WITHHELD, WRITE_OFF, ...
    @Column("type")            private String type;        // ORIGINAL | REVERSAL
    @Column("reverses_note_id") private UUID reversesNoteId;
    @Column("amount")          private BigDecimal amount;
    @Column("currency_code")   private String currencyCode;
    @Column("reason")          private String reason;
    @Column("status")          private String status;      // pending | approved | applied | reversed
    @Column("approved_by")     private UUID approvedBy;
    @Column("approved_at")     private Instant approvedAt;
    @Column("posted_at")       private Instant postedAt;
    @CreatedDate  @Column("created_at") private Instant createdAt;
    @LastModifiedDate @Column("updated_at") private Instant updatedAt;
    @Column("created_by")      private UUID createdBy;

    @Override public boolean equals(Object o) { /* id-only */ }
    @Override public int hashCode() { /* id-only */ }
}
```

**`NoteService.generateNoteNumber(String direction, String noteType)`** — replaces `generateAdjustmentNumber()`, prefixes per G16:

```java
private Mono<String> generateNoteNumber(String direction, String noteType) {
    String prefix = "MEMO".equals(noteType) ? "MEMO"
                  : "DEBIT".equals(direction) ? "DN"
                  : "CN";
    String number = prefix + "-" + ThreadLocalRandom.current().nextInt(100000, 999999);
    return noteRepository.existsByNoteNumber(number)
        .flatMap(exists -> exists ? generateNoteNumber(direction, noteType) : Mono.just(number));
}
```

**`NoteService.create(...)`** — sets `posted_at = now()` when status transitions to applied; sets `type='ORIGINAL'`, `reverses_note_id=null` on the create path. `createdBy` populated from `AuditActor.id(jwt)` (fixing one of the two hygiene defects called out in the research doc).

**`NoteService.reverse(UUID originalId, ...)` — new method** replacing the removed `cancel` path for applied notes. Inserts a compensating REVERSAL row (`type='REVERSAL'`, `reverses_note_id=originalId`, `amount=original.amount`, `direction=opposite(original.direction)`, `note_type=original.note_type`, `payee=original.payee`, `currency=original.currency`, `status='applied'`, `posted_at=now()`). Marks the original `status='reversed'` in the same transaction. Emits two audit events (one for the reversal insert, one for the original's status flip).

Pre-applied `pending`/`approved` notes still support a cancel-equivalent — this becomes a hard delete (they never hit the ledger, no audit compensation needed) OR they get marked `reversed` too with a synthetic no-op REVERSAL row. **Choose hard delete** — simpler; the audit trail from the CREATE event tells the story.

**`NoteController`** — same endpoints as before but under `/api/v1/notes` (see route table below), plus `/api/v1/notes/{id}/reverse` replacing the old `/cancel` for applied notes.

| Method | Path | Purpose |
|---|---|---|
| GET  | `/api/v1/notes/page`         | Paginated list; supports `direction`, `noteType`, `status`, `providerId`, `memberId`, `currencyCode`, `q`, `sortKey`, `sortDirection`, `page`, `size` |
| GET  | `/api/v1/notes/{id}`         | Single note detail |
| POST | `/api/v1/notes`              | Create note (`direction`, `noteType`, `amount`, `currencyCode`, `providerId?`/`memberId?`, `reason?`) |
| POST | `/api/v1/notes/{id}/approve` | pending → approved |
| POST | `/api/v1/notes/{id}/apply`   | approved → applied (sets `posted_at=now()`) |
| POST | `/api/v1/notes/{id}/reverse` | applied → reversed (inserts compensating REVERSAL row) |
| DELETE | `/api/v1/notes/{id}`       | Deletes a `pending`/`approved` note. 422 if `applied` (use `/reverse` instead) |

Old routes `/api/v1/adjustments`, `/api/v1/debit-notes`, `/api/v1/credit-notes` are **removed** in this phase — Angular still calls them, but Angular is phase 4. Between phase 1 and phase 4, Angular's current adjustment/note screens will 404. Acceptable in a greenfield build without external API consumers; phase 4 restores full UI.

*(Alternative: keep the old routes as delegating shims for one release. If the plan reviewer wants this, add it to phase 1 as `AdjustmentControllerShim` etc. — 3-line handlers that forward to the new service. Not the recommended path because the shims will linger.)*

#### 3. Update `AdjustmentQueryRepository` → `NoteQueryRepository`

The SQL rewrites are straightforward table/column renames. Two substantive changes:
- Add filter clauses for `direction` and `noteType`.
- The `search` clause becomes `LOWER(a.note_number) LIKE :search OR LOWER(a.reason) LIKE :search OR LOWER(m.member_number) LIKE :search OR LOWER(p.name) LIKE :search` (unchanged in shape).

#### 4. `FinanceEventPublisher` — Kafka `entityType='Note'`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java`

Change the audit-event `entityType` string from `"Adjustment"` to `"Note"` at every call site (line 225 and any callers of `publishAudit`). Update the event payload field from `"adjustmentType"` to `"noteType"` and add `"direction"` alongside.

#### 5. Audit-service one-shot backfill script

**File**: `services/go/audit-service/scripts/2026-08-10-backfill-note-entity-type.sql`

```sql
-- Idempotent: safe to re-run. Only touches rows whose entity_type still points at the pre-rename value.
BEGIN;
ALTER TABLE public.audit_events
    ADD COLUMN IF NOT EXISTS original_entity_type VARCHAR(50);

UPDATE public.audit_events
   SET original_entity_type = entity_type,
       entity_type = 'Note'
 WHERE entity_type IN ('Adjustment','DebitNote','CreditNote')
   AND original_entity_type IS NULL;
COMMIT;
```

Run once against each tenant's audit database as part of the deploy. Script location follows the audit-service scripts convention.

#### 6. Update existing tests

**Files to rewrite**:
- `services/java/finance-service/src/test/java/com/medfund/finance/controller/AdjustmentControllerTest.java` → `NoteControllerTest.java`
- `services/java/finance-service/src/test/java/com/medfund/finance/service/AdjustmentServiceTest.java` → `NoteServiceTest.java`

The existing tests set `adjustmentType="credit"` (a value not in either the old or new CHECK). Rewrite the setup to use `direction="DEBIT", noteType="TAX_WITHHELD"` (or similar valid combinations). Add tests for:
- `POST /api/v1/notes` populates `createdBy` (fixes the hygiene defect from the research doc).
- Reversal endpoint inserts compensating row and flips original to `reversed`.
- DELETE on applied note returns 422.
- Note number prefix matches direction/type (DN- / CN- / MEMO-).

#### 7. Update `PaymentAdviceService`'s TAX_WITHHELD query

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentAdviceService.java:326-333`

Rewrite the SQL to point at `notes`, filter on `note_type='TAX_WITHHELD'` and (crucially) `status='applied'` and `posted_at ∈ window`:

```java
String sql = "SELECT id, amount, reason, posted_at "
           + "  FROM notes "
           + " WHERE note_type = 'TAX_WITHHELD' "
           + "   AND status = 'applied' "
           + "   AND " + where
           + "   AND currency_code = :currency "
           + "   AND posted_at > :start "
           + "   AND posted_at <= :end "
           + " ORDER BY posted_at";
```

`reference_type` on the resulting `PaymentAdviceLine` stays `"note"` (was `"adjustment"`) — this is a **breaking change for downstream reference-type consumers**. Search for consumers of `reference_type='adjustment'` (none found in this plan's exploration; if any surface later, they must be updated in the same phase).

#### 8. Docs inline update

**File**: `.claude/architecture.md:85-93`

Change entity list from `..., Adjustment, ..., DebitNote, CreditNote, ...` to `..., Note, ...`. One-line strike-through-and-replace in place.

### Success Criteria

#### Automated Verification:
- [x] Java compiles clean: `./gradlew :finance-service:compileJava` and `:finance-service:compileTestJava` both green
- [x] Unit tests pass — new `NoteServiceTest` (18 cases) and `NoteControllerTest` (6 cases) all pass; `FinanceEventPublisherTest` updated with two new cases (`publishNoteApplied`, `publishNoteReversed`) — pass. Full `:finance-service:test` returns 140 tests / 131 pass / 9 pre-existing failures unrelated to this phase (see [[bug_claim_save_mock_id_npe]] — MascaBankAccountServiceTest, ReconciliationServiceTest, PaymentServiceTest, ProviderBalanceServiceTest all last touched pre-2026-06-24)
- [ ] Integration tests pass (Testcontainers migration + repo): `make test-integration` — deferred to manual step (needs Docker infra up)
- [ ] V074 tenant migration applies cleanly against a fresh Testcontainer (IT harness executes this by default) — deferred to manual step
- [ ] `curl` walk from a shell:
  - `POST /api/v1/notes` with `{direction:"DEBIT", noteType:"TAX_WITHHELD", amount:"50.00", currencyCode:"USD", providerId:"..."}` returns 201 with `noteNumber="DN-XXXXXX"`
  - `GET /api/v1/notes/page?direction=DEBIT` returns the row
  - `POST /api/v1/notes/{id}/approve` then `/apply` transitions status; `posted_at` is set
  - `POST /api/v1/notes/{id}/reverse` inserts a compensating row and flips original
  - Old `POST /api/v1/adjustments` and `POST /api/v1/debit-notes` return 404
- [ ] Swagger renders the new controller at `http://localhost:8085/swagger-ui`
- [ ] Kafka event round-trip test: creating a note publishes an audit event with `entityType='Note'`
- [ ] Audit-service backfill script is idempotent on re-run (asserted by IT: run twice, count unchanged)

#### Manual Verification:
- [ ] Run the audit-service backfill script against a dev tenant that has real Adjustment rows and confirm the `original_entity_type` column is populated and `entity_type='Note'`
- [ ] Confirm no other Java service references the old class names via a repo-wide grep (`grep -r "AdjustmentService\|AdjustmentRepository" services/java/` returns zero matches outside the deleted files)

**Implementation Note**: after this phase's automated verification passes, pause for human confirmation of the audit-service backfill before Phase 2. Backfill is destructive-looking (it rewrites `entity_type`) even though it's idempotent — worth a manual eyeball.

---

## Phase 2: PaymentAdvice integration

### Overview

Teach `PaymentAdviceService` to source `NOTE_DEBIT` / `NOTE_CREDIT` lines from the renamed `notes` table for both PROVIDER and MEMBER payees. Handle late-arriving notes via `back_period_run_id`. Update the `net_due_amount` computation. Add the 5 pinned scenarios from the research doc as IT tests. Inline-update `.claude/payments.md`.

### Changes Required

#### 1. `PaymentAdviceService` — new generic note-lines method

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentAdviceService.java`

New private method modelled on the existing `taxWithheldLines(...)` — but sources both directions and both payee types:

```java
private Flux<PaymentAdviceLine> noteLines(UUID payeeId, String payeeType,
                                          String currency, Instant start, Instant end,
                                          UUID priorRunId, UUID thisRunId) {
    String where = "PROVIDER".equals(payeeType)
        ? "provider_id = :payeeId"
        : "member_id = :payeeId";

    // Generic notes (excludes MEMO and TAX_WITHHELD which have their own handling).
    // Includes late-arriving notes: any note whose posted_at ≤ end AND not already stamped
    // onto a prior advice line for this payee gets picked up now.
    String sql = "SELECT n.id, n.amount, n.reason, n.direction, n.note_type, n.posted_at, "
               + "       COALESCE(pal.payment_advice_id, NULL) AS already_stamped "
               + "  FROM notes n "
               + "  LEFT JOIN payment_advice_lines pal "
               + "    ON pal.reference_type = 'note' AND pal.reference_id = n.id "
               + " WHERE n.status = 'applied' "
               + "   AND n.note_type NOT IN ('MEMO', 'TAX_WITHHELD') "
               + "   AND n.currency_code = :currency "
               + "   AND n.posted_at <= :end "
               + "   AND " + where + " "
               + "   AND pal.payment_advice_id IS NULL "
               + " ORDER BY n.posted_at";

    return databaseClient.sql(sql)
        .bind("payeeId", payeeId)
        .bind("currency", currency)
        .bind("end", end)
        .map((row, meta) -> {
            String direction = row.get("direction", String.class);
            BigDecimal amount = row.get("amount", BigDecimal.class);
            Instant postedAt = row.get("posted_at", Instant.class);
            UUID noteId = row.get("id", UUID.class);

            boolean late = postedAt.isBefore(start);
            String lineType = "DEBIT".equals(direction) ? "NOTE_CREDIT" : "NOTE_DEBIT";
            BigDecimal debit  = "NOTE_DEBIT".equals(lineType) ? amount : BigDecimal.ZERO;
            BigDecimal credit = "NOTE_CREDIT".equals(lineType) ? amount : BigDecimal.ZERO;

            return newLine(lineType, "note", noteId,
                           firstNonNull(row.get("reason", String.class), lineType),
                           debit, credit, currency, postedAt,
                           late ? priorRunId : null);   // back_period_run_id
        })
        .all();
}
```

Insert `noteLines(...)` into the fan-in that assembles all line types for a given payee, positioned between `SHORTFALL` and terminal net computation. Sort order for the final advice is preserved via the `sequence` column (already there per `V071:113`).

#### 2. `net_due_amount` update

The formula computation is done in `PaymentAdviceService.java:424` via `sumBy(lines, ..., false/true)`. Add:

```java
BigDecimal noteDebits  = sumBy(lines, "NOTE_DEBIT",  true);   // debitAmount column
BigDecimal noteCredits = sumBy(lines, "NOTE_CREDIT", false);  // creditAmount column

// Formula per G-decisions:
// net_due = carried_in + claims_paid + note_debits - ctc_applied - advance_applied - tax_withheld - shortfall - note_credits
BigDecimal netDue = carriedIn.add(claimsPaid).add(noteDebits)
                    .subtract(ctcApplied).subtract(advanceApplied)
                    .subtract(taxWithheld).subtract(shortfall).subtract(noteCredits);
```

#### 3. `PaymentAdviceLine` entity gains `back_period_run_id`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentAdviceLine.java`

Add:
```java
@Column("back_period_run_id") private UUID backPeriodRunId;
```

Column already added by the phase 1 migration. Setter used by `newLine(...)` helper.

#### 4. 5 pinned IT scenarios

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/service/PaymentAdviceServiceNotesIT.java` (new)

One `@Test` per scenario from research doc lines 253-257:

1. `directionDebitProviderOverpaymentRecovery_appearsAsNoteCreditReducingNetDue()` — post a DEBIT/PROVIDER_OVERPAYMENT_RECOVERY note against a provider mid-window; execute a run; assert advice has `NOTE_CREDIT` line = note.amount; `net_due_amount` = expected − note.amount.
2. `directionCreditGoodwill_appearsAsNoteDebitIncreasingNetDue()` — symmetric for CREDIT/GOODWILL on a MEMBER.
3. `memoTypeNote_doesNotAppearOnAnyAdvice()` — post a `note_type=MEMO` note; execute a run for both a PROVIDER and a MEMBER; assert neither advice contains the note's id.
4. `reversalNote_appearsOnCurrentAdviceAsOppositeDirection()` — post an applied note; execute run A (advice generated). Reverse the note. Execute run B; assert run B's advice has a compensating opposite-direction line; run A's advice untouched.
5. `taxWithheldNote_rendersAsTaxWithheldLineNotNoteCredit()` — post a DEBIT/TAX_WITHHELD note; execute a run; assert advice has a `TAX_WITHHELD` line and NO `NOTE_CREDIT` line for the same note id.

Plus a 6th scenario for the late-arriving mechanism:

6. `lateArrivingNote_appearsOnNextAdviceWithBackPeriodRunId()` — execute run A. Then post a note with `posted_at` between run A's `prior_run.executed_at` and `run_a.executed_at` (i.e. inside run A's window, but posted after A executed). Execute run B. Assert run B's advice has the note as a line with `back_period_run_id = runA.id` and correct direction; run A's advice unchanged.

#### 5. Docs inline update

**File**: `.claude/payments.md:380-395`

Update the advice-ledger table to add the two note rows and update the `net_due_amount` formula:

```markdown
| Line type         | Direction | Source table                                    |
|-------------------|-----------|-------------------------------------------------|
| `CARRY_FORWARD`   | Debit     | Prior advice's `net_due_amount`                 |
| `CLAIM_PAID`      | Debit     | `claims` (adjudicated in the period)            |
| `NOTE_DEBIT`      | Debit     | `notes` where `direction='CREDIT'`, `status='applied'`, `note_type NOT IN ('MEMO','TAX_WITHHELD')`, payee matches |
| `CTC_APPLIED`     | Credit    | `ctc_payments` (MEMBER only, committed)         |
| `ADVANCE_APPLIED` | Credit    | `advance_payment_applications` (PROVIDER)       |
| `TAX_WITHHELD`    | Credit    | `notes` where `note_type='TAX_WITHHELD'`, `status='applied'` |
| `SHORTFALL`       | Credit    | `claims` where `paid_amount < claimed_amount`   |
| `NOTE_CREDIT`     | Credit    | `notes` where `direction='DEBIT'`, `status='applied'`, `note_type NOT IN ('MEMO','TAX_WITHHELD')`, payee matches |

`net_due_amount = carried_in + claims_paid + note_debits − ctc_applied − advance_applied − tax_withheld − shortfall − note_credits`.

Late-arriving notes (posted_at before the last executed run's `executed_at` but not yet stamped onto any advice) appear on the next advice with `back_period_run_id` pointing at the run whose advice would have carried them otherwise.
```

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `./gradlew :finance-service:compileJava :finance-service:compileTestJava` — clean
- [x] Unit tests pass: `./gradlew :finance-service:test --tests '*PaymentAdvice*' --tests '*Note*'` — all green (existing PaymentAdviceServiceTest scenarios still pass after `newLine`/DTO signature extension for `backPeriodRunId`)
- [ ] All 6 IT scenarios pass: `make test-integration` (filter: `PaymentAdviceServiceNotesIT`) — **deferred** (see 2026-08-10 Deviation: no PaymentAdvice IT harness exists in finance-service today; building one is a follow-up)
- [ ] Existing PaymentAdvice IT scenarios still green (regression guard) — **N/A**, no existing PaymentAdvice IT
- [ ] Swagger still renders — no new endpoint but sanity check for schema changes to PaymentAdviceLine response
- [ ] `curl` walk from a shell against a dev tenant with a fully populated ledger:
  - Execute a payment run
  - `GET /api/v1/payment-advices/{id}` returns the ledger lines including any `NOTE_DEBIT` / `NOTE_CREDIT` that fell in-window
  - `net_due_amount` matches the formula computed by hand

#### Manual Verification:
- [ ] With a real provider and a mix of TAX_WITHHELD + GOODWILL notes plus at least one late-arriving note, execute a payment run in a dev tenant. Read the resulting advice detail via `curl` and confirm each line type + `back_period_run_id` makes sense.
- [ ] No advice from a prior run has mutated (spot-check the `updated_at` and line count of at least one historic advice).

**Implementation Note**: after this phase's automated verification, pause for human confirmation of the sample advice generation before Phase 3.

---

## Phase 3: Permission split + compat mapping

### Overview

Introduce `finance.notes:read`, `finance.notes:write`, `finance.notes:approve` in the permission catalogue. During phase 3 (and the one release beyond), the existing flat `finance:post_adjustments` continues to work — the permission resolver auto-expands it into all three on login. This lets Angular (phase 4) switch its route guards to the new permissions without requiring any tenant role reassignment on cutover day.

### Changes Required

#### 1. `PermissionCatalogue` — add three permissions

**File**: `services/java/user-service/src/main/java/com/medfund/user/security/PermissionCatalogue.java` (locate exact path — grep for `PermissionCatalogue` in user-service; edit accordingly).

Add:

```java
public static final String NOTES_READ    = "finance.notes:read";
public static final String NOTES_WRITE   = "finance.notes:write";
public static final String NOTES_APPROVE = "finance.notes:approve";
```

And add these to whatever the catalogue's enumeration mechanism is (list, seed script, DB row — depends on implementation).

#### 2. Permission resolver — compat mapping

**File**: same package as `PermissionCatalogue` — the resolver that turns role → permission list on login.

Add compat expansion:

```java
// COMPAT (2026-08 → next release): tenants holding the legacy flat permission
// finance:post_adjustments automatically get all three new notes permissions
// so they don't lose access when the Angular UI switches its guards in phase 4.
// Remove this expansion after all tenant role assignments have been migrated
// to the new granular permissions.
if (permissions.contains("finance:post_adjustments")) {
    permissions.add(NOTES_READ);
    permissions.add(NOTES_WRITE);
    permissions.add(NOTES_APPROVE);
}
```

#### 3. Angular permission constants

**File**: `clients/angular/src/app/core/auth/permissions.ts`

Add:

```typescript
'finance.notes:read'    // 'View notes'
'finance.notes:write'   // 'Create notes'
'finance.notes:approve' // 'Approve, apply, or reverse notes'
```

Keep `finance:post_adjustments` in the catalogue for the compat window (marked deprecated in a comment).

#### 4. Docs inline update

**File**: `.claude/portals.md:511-517`

Update the permission table to reflect the split — remove the promised `finance.adjustments:*`, `finance.debit_notes:*`, `finance.credit_notes:*` lines and replace with:

```
finance.notes:read      — View notes
finance.notes:write     — Create notes
finance.notes:approve   — Approve, apply, or reverse notes
```

Add a footnote noting that `finance:post_adjustments` is retained for one release as a compat mapping.

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `./gradlew :shared:compileJava :user-service:compileJava` — clean
- [x] Unit tests pass: `./gradlew :shared:test --tests '*DefaultPermissionResolver*'` — 3 new cases (compat expansion, no-op on non-legacy set, idempotency) all green
- [ ] Integration tests pass: `make test-integration` (existing role-permission IT test suite, plus a new test that asserts the compat mapping fires — a role holding only `finance:post_adjustments` receives all three note permissions after resolver runs) — **deferred to manual step**
- [ ] Angular unit tests: `make test-angular` (the permission-constants file has no functional logic but constants are used in guards — smoke test) — **deferred to manual step**
- [ ] `curl` a Keycloak-authenticated user with only `finance:post_adjustments` in their role: verify the resolved permission set includes all three new ones

#### Manual Verification:
- [ ] A tenant admin whose role today has only `finance:post_adjustments` can still access `/tenant/finance/adjustments` (post-phase-3, pre-phase-4 the UI still uses old permissions — this proves nothing has broken)
- [ ] After phase 4 (looking ahead), the same user retains access to the new `/tenant/finance/notes` routes without a role edit

---

## Phase 4: Angular UI + advice detail rendering

### Overview

Rename the Angular finance components, service methods, and types from `Adjustment*` to `Note*`. Delete the memo-only `NotesListComponent` and repurpose `AdjustmentsListComponent` in its place. Add three sidebar entries (Debit Notes, Credit Notes, All Notes), retire the `/adjustments` entry, wire `/adjustments/*` → 301 redirects. Extend `payment-advice-detail` to render `NOTE_DEBIT` and `NOTE_CREDIT` sections with drill-in links back to the source notes. Playwright golden-path spec.

### Changes Required

#### 1. `finance.service.ts` — types + methods rename

**File**: `clients/angular/src/app/core/services/finance.service.ts`

**Delete outright** (lines 511-528, 841-862): the memo-only `FinanceNote`, `CreateNotePayload`, `listDebitNotesPaged`, `listCreditNotesPaged`, `createDebitNote`, `createCreditNote`.

**Rename** (lines 96-160, 753-771):

| From | To |
|---|---|
| `AdjustmentType` union | `NoteType` union: `'TAX_WITHHELD' \| 'WRITE_OFF' \| 'GOODWILL' \| 'ENDORSEMENT_PREMIUM' \| 'PREMIUM_REFUND' \| 'PROVIDER_OVERPAYMENT_RECOVERY' \| 'MEMO'` |
| `AdjustmentStatus` union | `NoteStatus` union: `'pending' \| 'approved' \| 'applied' \| 'reversed'` |
| `Adjustment` interface | `Note` interface — add `direction: 'DEBIT' \| 'CREDIT'`, `noteType: NoteType`, `type: 'ORIGINAL' \| 'REVERSAL'`, `reversesNoteId?: string`, `postedAt?: string`; rename `adjustmentType` → `noteType`, `adjustmentNumber` → `noteNumber` |
| `AdjustmentRow` interface | `NoteRow` interface — same rename |
| `CreateAdjustmentPayload` | `CreateNotePayload` — requires `direction` and `noteType`; drop `adjustmentType` |
| `AdjustmentPageParams` | `NotePageParams` — replace `adjustmentType?` filter with `direction?` + `noteType?` |
| `listAdjustmentsPaged` | `listNotesPaged` → `GET /notes/page` |
| `getAdjustment` | `getNote` → `GET /notes/{id}` |
| `createAdjustment` | `createNote` → `POST /notes` |
| `approveAdjustment` | `approveNote` → `POST /notes/{id}/approve` |
| `applyAdjustment` | `applyNote` → `POST /notes/{id}/apply` |
| `cancelAdjustment` (removed) | `reverseNote` → `POST /notes/{id}/reverse` (for applied); `deleteNote` → `DELETE /notes/{id}` (for pending/approved) |

`PaymentAdviceLine` type at line 610-620 gains `backPeriodRunId?: string`; `lineType` union extends to include `'NOTE_DEBIT' \| 'NOTE_CREDIT'`.

#### 2. `NotesListComponent` — repurpose from `AdjustmentsListComponent`

**Files**:
- Delete: `clients/angular/src/app/pages/tenant/finance/notes/notes-list.component.ts` (the current memo one) and its `.html`.
- Rename: `clients/angular/src/app/pages/tenant/finance/adjustments/adjustments-list.component.{ts,html}` → `clients/angular/src/app/pages/tenant/finance/notes/notes-list.component.{ts,html}`.

**Component changes**:
- Component class rename `AdjustmentsListComponent` → `NotesListComponent`; selector rename `app-adjustments-list` → `app-notes-list`.
- Add a `direction` filter (route data): the same component handles all three routes, filtering rows by `route.data.direction` (`'DEBIT'`, `'CREDIT'`, or `null` for combined view). Direction filter chip is visible only when `direction === null` (combined view); pinned otherwise.
- Add a `noteType` filter chip (dropdown of the 7 values).
- Columns: `noteNumber`, `member`, `provider`, `direction`, `noteType`, `amount`, `status`, `reason`, `postedAt`, `createdAt`.
- "New note" button routes to `/tenant/finance/notes/new` (or `/tenant/finance/debit-notes/new` / `/credit-notes/new` — direction pre-selected from the route).

#### 3. `NoteFormComponent` — repurpose from `AdjustmentFormComponent`

**Files**: rename `adjustments/adjustment-form.component.{ts,html}` → `notes/note-form.component.{ts,html}`.

**Form changes**:
- Add `direction` control (DEBIT / CREDIT) — pre-selected from route data when arriving via `/debit-notes/new` or `/credit-notes/new`.
- Rename `adjustmentType` → `noteType` control; update the dropdown options to the 7 new values.
- Add validation: `noteType === 'MEMO'` disables the payee target-picker (memo notes are payee-less).

#### 4. `NoteDetailComponent` — repurpose from `AdjustmentDetailComponent`

**Files**: rename `adjustments/adjustment-detail.component.{ts,html}` → `notes/note-detail.component.{ts,html}`.

**Detail changes**:
- Show all Note fields including `direction`, `postedAt`, `reversesNoteId?` (link to original if this is a REVERSAL).
- Action buttons: `Approve`, `Apply`, `Reverse` (replaces `Cancel` for applied notes; `Cancel` becomes `Delete` for pending/approved).
- Each action button is gated by `finance.notes:approve` (per G6).

#### 5. `finance.routes.ts` — route table update

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`

**New routes** (add):

```typescript
{
  path: 'notes',
  loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
  data: { direction: null },
  canActivate: [permissionGuard(['finance.notes:read'])],
},
{
  path: 'notes/tax-withheld',
  loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
  data: { direction: null, presetNoteType: 'TAX_WITHHELD' },
  canActivate: [permissionGuard(['finance.notes:read'])],
},
{
  path: 'notes/new',
  loadComponent: () => import('./notes/note-form.component').then(m => m.NoteFormComponent),
  canActivate: [permissionGuard(['finance.notes:write'])],
},
{
  path: 'notes/:id',
  loadComponent: () => import('./notes/note-detail.component').then(m => m.NoteDetailComponent),
  canActivate: [permissionGuard(['finance.notes:read'])],
},
// Existing debit-notes / credit-notes routes REPOINT at the repurposed NotesListComponent,
// with direction pinned via route data:
{
  path: 'debit-notes',
  loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
  data: { direction: 'DEBIT' },
  canActivate: [permissionGuard(['finance.notes:read'])],
},
{
  path: 'debit-notes/new',
  loadComponent: () => import('./notes/note-form.component').then(m => m.NoteFormComponent),
  data: { presetDirection: 'DEBIT' },
  canActivate: [permissionGuard(['finance.notes:write'])],
},
{
  path: 'credit-notes',
  loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
  data: { direction: 'CREDIT' },
  canActivate: [permissionGuard(['finance.notes:read'])],
},
{
  path: 'credit-notes/new',
  loadComponent: () => import('./notes/note-form.component').then(m => m.NoteFormComponent),
  data: { presetDirection: 'CREDIT' },
  canActivate: [permissionGuard(['finance.notes:write'])],
},

// REDIRECTS from old /adjustments routes (301-equivalent — Angular redirectTo with pathMatch: 'full'):
{ path: 'adjustments',                redirectTo: 'notes',                pathMatch: 'full' },
{ path: 'adjustments/new',            redirectTo: 'notes/new',            pathMatch: 'full' },
{ path: 'adjustments/tax-withheld',   redirectTo: 'notes/tax-withheld',   pathMatch: 'full' },
{ path: 'adjustments/:id',            redirectTo: 'notes/:id',            pathMatch: 'full' },

// Reports:
{ path: 'reports/group-adjustments',      redirectTo: 'reports/group-notes',      pathMatch: 'full' },
{ path: 'reports/group-adjustments/:id',  redirectTo: 'reports/group-notes/:id',  pathMatch: 'full' },
```

**Delete**: the existing `/adjustments`, `/adjustments/new`, `/adjustments/tax-withheld`, `/adjustments/:id` route definitions (lines 148-178). The redirects above take their place.

#### 6. Sidebar update

**File**: `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts`

Around lines 141-143:

**Delete**: `Adjustments` entry (line 141), `Debit Notes` entry (line 142), `Credit Notes` entry (line 143).

**Add** three replacement entries, all under the Finance section:
```typescript
{ label: 'Debit Notes',  route: '/tenant/finance/debit-notes',  icon: 'edit', permissions: ['finance.notes:read'] },
{ label: 'Credit Notes', route: '/tenant/finance/credit-notes', icon: 'edit', permissions: ['finance.notes:read'] },
{ label: 'All Notes',    route: '/tenant/finance/notes',        icon: 'edit', permissions: ['finance.notes:read'] },
```

The reports entries around lines 229-230 update their permission strings from `finance:post_adjustments` to `finance.notes:read`.

#### 7. `payment-advice-detail` — render NOTE_DEBIT + NOTE_CREDIT

**Files**:
- `clients/angular/src/app/pages/tenant/finance/advices/payment-advice-detail.component.ts` — extend the `LINE_TYPE_ORDER` array to include `'NOTE_DEBIT'` (between `CLAIM_PAID` and `CTC_APPLIED`) and `'NOTE_CREDIT'` (after `SHORTFALL`). Extend `LINE_TYPE_LABEL` with `'Note debits'` and `'Note credits'`.
- Same file's `.html` template: each `<section>` iterates `LINE_TYPE_ORDER`; new sections render automatically. Each row's `referenceType === 'note'` becomes a link: `<a [routerLink]="['/tenant/finance/notes', line.referenceId]">{{ line.description }}</a>`.
- Show `back_period_run_id` when non-null as a small badge on the line: "Back-period from run {{ line.backPeriodRunId | slice:0:8 }}".

#### 8. Playwright golden-path spec

**File**: `clients/angular/e2e/tests/finance-notes.spec.ts` (new)

One `test()` per golden-path:

1. `debit note create → approve → apply → appears on provider advice` — full flow through the UI, from `/debit-notes/new` to executing a payment run to opening the resulting `/advices/:id` and asserting the NOTE_CREDIT line appears.
2. `credit note reversal — shows on advice as opposite direction` — post + apply a credit note, execute run A, reverse it via UI, execute run B, assert both advices have the expected lines.
3. `MEMO note creation → not on any advice` — post a MEMO note, execute a run for the same currency, open advice, assert no line with the note's id.
4. `old /adjustments redirect to /notes preserves query params` — navigate to `/tenant/finance/adjustments?status=applied`, assert URL rewrites to `/tenant/finance/notes?status=applied`.

#### 9. Docs inline update

**File**: `.claude/portals.md:148-152`

Update the Finance Portal route table — replace:
```
| `/finance/adjustments` | Adjustments | Create payment adjustments (linked to tickets) |
| `/finance/debit-notes` | Debit Notes | Issue and manage debit notes |
| `/finance/credit-notes` | Credit Notes | Issue and manage credit notes |
```

with:
```
| `/finance/notes` | All Notes | Unified list of debit + credit notes; create, approve, apply, reverse |
| `/finance/debit-notes` | Debit Notes | Filtered view — direction=DEBIT (payee owes us) |
| `/finance/credit-notes` | Credit Notes | Filtered view — direction=CREDIT (we owe payee more) |
```

### Success Criteria

#### Automated Verification:
- [x] TypeScript is clean: `npx tsc --noEmit -p tsconfig.app.json` — no errors after rename (all Adjustment* → Note*)
- [ ] Angular compiles: `cd clients/angular && ng build` — pre-existing SCSS-budget errors on unrelated files (data-table, dashboard, generate-billing-wizard, claim-detail, settings, member-detail, submit-claim) block the build; none introduced by this phase. TS layer is clean.
- [ ] Unit tests pass: `make test-angular` — tax-withheld spec rewritten to use `listNotesPaged`; **deferred to manual step**
- [ ] Playwright golden-path spec green: `make test-e2e -- --grep finance-notes` — spec written; **deferred to manual step** (needs the app running)
- [ ] `verify` skill on `/tenant/finance/debit-notes`: no console errors, list renders, "New" button routes to `/debit-notes/new`, filter chips fire
- [ ] `verify` skill on `/tenant/finance/credit-notes`: same
- [ ] `verify` skill on `/tenant/finance/notes`: combined view; direction filter chip is visible and toggles the list
- [ ] `verify` skill on `/tenant/finance/advices/{id}` with a fixture advice containing `NOTE_DEBIT` + `NOTE_CREDIT` + `TAX_WITHHELD` lines: all three sections render; note lines link to `/notes/:id`; `back_period_run_id` badge appears where set
- [ ] `verify` navigating from `/tenant/finance/adjustments` should redirect to `/tenant/finance/notes` (URL changes, list renders)
- [ ] Sidebar shows Debit Notes, Credit Notes, All Notes (three entries); Adjustments entry is gone

#### Manual Verification:
- [ ] Post a note via UI, approve, apply, execute a payment run, open the advice, confirm the note line appears with the correct direction and the link back to the note detail works.
- [ ] Reverse an applied note via UI; confirm the compensating row is created and both original and reversal appear on the appropriate advices.
- [ ] Confirm a tenant admin whose role only holds the legacy `finance:post_adjustments` permission can still perform the full note lifecycle (compat mapping from phase 3 in effect).

---

## Testing Strategy

### Unit tests:
- `NoteServiceTest` — CRUD, note number generation per direction/type, reversal path, DELETE on applied returns 422, `createdBy` populated on create.
- `NoteQueryRepositoryTest` — filter by direction, noteType, status; search on noteNumber + reason + member/provider name.
- `PaymentAdviceServiceTest` — sumBy computations for the new line types; net_due_amount formula.

### Integration tests (Testcontainers slices):
- `NoteControllerIT` — full HTTP round-trip through the JWT filter; migration V072 applied; Postgres CHECK constraints exercised (reject a note with an invalid `noteType`, reject a REVERSAL without `reverses_note_id`).
- `PaymentAdviceServiceNotesIT` — the 5+1 pinned scenarios above (Phase 2).
- `RolePermissionCompatIT` — compat mapping in the resolver expands `finance:post_adjustments` correctly (Phase 3).
- `AuditEventBackfillIT` — runs the backfill script twice against a seeded audit-events table, asserts idempotency.

### E2E tests (Playwright):
- `finance-notes.spec.ts` — the 4 golden paths above (Phase 4).

### Manual testing steps:
1. Fresh dev tenant, apply V072 migration
2. POST a note via `curl` in each direction
3. Execute a payment run, open the resulting advice
4. Reverse a note; confirm the compensating row appears
5. Open the Angular UI at `/tenant/finance/debit-notes`, walk the full lifecycle
6. Navigate to `/tenant/finance/adjustments` and confirm the redirect
7. Assign a role holding only `finance:post_adjustments`; confirm the notes UI works (compat window)

## Performance Considerations

- **The new advice-lines query** (Phase 2) joins `notes` LEFT JOIN `payment_advice_lines` to filter already-stamped notes. On a large `payment_advice_lines` table this could be slow. Mitigation: `idx_pal_reference` (already exists per V071:123) covers `(reference_type, reference_id)`; verify EXPLAIN uses it.
- **Note-number retry** — random 6-digit generation with retry-on-collision. Collision probability at 900k unique slots per direction per tenant is <1% until ~100 notes exist, and each retry is a single indexed SELECT. Acceptable.
- **Migration V072 is long** — reads and rewrites every adjustment row + every debit_note + every credit_note. On tenants with thousands of adjustment rows this could lock the table briefly. Mitigation: greenfield build; volumes are small. If a future tenant has significant data, wrap the backfills in batched transactions.
- **Angular bundle size** — the memo-only NotesListComponent deletion is a small win; no material bundle impact from the renames.

## Migration Notes

- **V072 is a single-file migration** — do NOT split into multiple V-numbers. Splitting risks partial migration where the table is renamed but the payment_advice_lines constraint hasn't been extended, breaking PaymentAdviceService until the second migration lands. All work happens in one Flyway transaction.
- **Never edit V072 after apply** ([[feedback_never_edit_applied_migrations]]) — Flyway locks the checksum. If a correction is needed post-apply, write V151.
- **Testcontainers IT harness** must include the Testcontainers 1.21.4 BOM override, flyway-database-postgresql, and the ReactiveJwtDecoder stub ([[infra_testcontainers_pitfalls]]). Verify these are present before running.
- **Tenant Flyway out-of-order drift** ([[bug_tenant_flyway_outoforder]]) — if any tenant is running a V-number > 150 already, a repair + `-outOfOrder=true` migrate is needed. Check tenant Flyway state before deploy.
- **The V016 `debit_notes` and `credit_notes` tables must be empty of production data** for the migration to preserve everything. Verify by querying `SELECT COUNT(*)` in each live tenant before deploy — if any tenant has >0 rows, the migration still handles them (they land as MEMO), but confirm the semantics are what the tenant expects.
- **The audit-service backfill script** (`2026-08-10-backfill-note-entity-type.sql`) is separate from the tenant Flyway history — it's a one-shot run against the audit database, not versioned. Track its execution in a deploy runbook.
- **Grafana / Loki dashboards** filtering `entityType="Adjustment"` will silently return zero results post-cutover. Enumerate these before deploy and update the queries to `entityType="Note"` (or `entityType IN ("Note", "Adjustment")` if you want to include the backfilled history).

## Rollout & Rollback

**Rollout order per phase**: each phase deploys atomically. Phase 1 deploys finance-service + tenancy-service (migration) + user-service (no change in this phase) + audit-service (backfill script). Phase 2 deploys finance-service only. Phase 3 deploys user-service + angular. Phase 4 deploys angular only.

**Backwards compatibility**:
- Phase 1: finance-service Kafka producer switches to `entityType='Note'` immediately (G5 Option A). Audit-service consumer stores whatever arrives — no consumer-side change needed. Backfill script harmonises historic rows.
- Phase 2: additive to PaymentAdvice — old advices are unchanged; new advices carry the new line types. Downstream advice-consuming services (file-service for PDF, notification-service for email) iterate the payload — G12 verified they render the new line types without change.
- Phase 3: compat mapping in the permission resolver means every tenant role assignment continues to work.
- Phase 4: `/adjustments/*` routes redirect to `/notes/*` for one release. External bookmarks and email links continue to work.

**Rollback per phase**:
- Phase 1: `git revert` the finance-service + audit-service commits. Compensating tenant migration V151 to reverse V072 (table rename back to `adjustments`, drop the new columns, restore the CHECK, recreate `debit_notes`/`credit_notes` and re-insert the MEMO rows split by direction). Audit-service backfill is idempotent-reversible — a compensating UPDATE restores `entity_type` from `original_entity_type`.
- Phase 2: `git revert` the finance-service commit. No schema rollback needed — the new line types simply stop being generated.
- Phase 3: `git revert` the user-service + angular commits. Compat mapping code disappears; three new permissions remain in the catalogue but unused. Removal of the three permissions would need a follow-up if desired.
- Phase 4: `git revert` the angular commit. Route redirects disappear; users navigating to `/adjustments` land on a 404 until they update their bookmarks. Real risk only if phase 1 or 2 has landed but phase 4 was intended.

**Deploy hold-back**: do not deploy phase 4 before phase 3 has been out for at least a business day (verify no compat-mapping issues surface).

## References

- Ticket: none
- Research: `thoughts/shared/research/2026-08-10-debit-and-credit-notes-in-insurance.md`
- Architecture docs: `.claude/architecture.md`, `.claude/payments.md`, `.claude/portals.md`, `.claude/coding-standards.md`, `.claude/CLAUDE.md` (Critical Rules)
- Similar implementation (compensating-entry pattern): `thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md`, `thoughts/shared/plans/2026-08-09-ctc-payments.md`
- Grilling scratchpad (ephemeral): `/tmp/grill-notes-plan.md` — decision rationales are also inlined into the research doc's `## Grilling decisions` section
