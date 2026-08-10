---
date: 2026-08-09
git_commit: 117d24e07b8239534826dd4484dfa5b7adeb1e69
branch: main
research:
  - thoughts/shared/research/2026-08-09-contribution-statement-pdf-divergence.md
steer: "fix: stop the silent PDF fallback, rename Balance carried forward → Amount Due end-to-end"
services_touched: [contributions-service, file-service, angular]
status: implemented
---

# Contribution-statement PDF divergence — fix Plan

## Deviations

- **2026-08-10** — Phase 1 rename extended to two label sites the plan did not enumerate but its casing-grep guard catches: `services/go/notification-service/internal/invoice/body.html:10` (customer invoice email body) and `clients/angular/src/app/pages/tenant/billing/ledger/ledger.component.ts:401` (client-side jsPDF ledger export). Both were `"Amount due"` and now read `"Amount Due"` to match the plan's stated intent that "every displayable label reads 'Amount Due'".

## Overview

Two changes, each independently verifiable:

1. **Stop the silent PDF fallback.** The file-service Kafka consumer currently catches every error from `contributions-service`' render-payload endpoint and ships a stripped legacy-shape PDF anyway (`services/go/file-service/cmd/main.go:240-245`). Replace that with a bounded retry (3 attempts, 0.5s + 2s backoff) then log-loud and refuse to publish `InvoicePdfReady`. Delete the fallback branch from the template so the failure mode cannot regress.
2. **Rename the closing bookend `"Balance carried forward"` → `"Amount Due"` end-to-end.** Two Java string literals, two test fixtures, one Excel service suffix, one Go template marker, one Go test fixture, one Angular template marker, one Angular spec fixture, three E2E specs, and one Javadoc pointer. Also normalises the two lower-case `"Amount due"` label sites (PDF footer + Excel footer + one Angular fixture) to the same `"Amount Due"` capitalisation so the label is canonical everywhere.

## Current State Analysis

- **The bug is one nil check.** `services/go/file-service/cmd/main.go:240-245` sets `renderData = nil` on any error from `FetchRenderPayload`. The renderer's `buildView` at `services/go/file-service/internal/invoice/renderer.go:184-187` sees `rd == nil` and returns early with only the fallback fields populated → template's `{{else}}` branch fires (`template.html:182-201`) → the "Contributions for period · Total due" summary PDF ships anyway. This is exactly the CS-004418 shape.
- **The `POST /invoice-pdf/render` on-demand endpoint does NOT fall back** — it 502s on the same error (`cmd/main.go:126-131`). So the operator recovery path already exists: after this fix, the operator sees no PDF (or gets a 502 on re-fire) and can trigger a rerender once the underlying cause is addressed.
- **The label `"Balance carried forward"` is a Java string literal in two `StatementService` sites** (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java:212` for the period-based ledger; `:493` for the snapshot-based ledger). The PDF template renders `{{.ClosingRow.Description}}` verbatim and appends a static `C/F · amount due` marker span (`template.html:156`), so the row currently reads "Balance carried forward · C/F · amount due".
- **`"Balance carried forward"` also appears verbatim** in `StatementServiceTest.java:199`, `StatementExcelServiceTest.java:106,154,184,193`, `renderer_test.go:297,310,327`, `invoice-statement.component.spec.ts:64`, `billing-ledger.spec.ts:38`, and the `StatementLine.java:29` Javadoc. All are load-bearing string assertions or renderer inputs — every one needs the rename.
- **Two E2E fixtures already spell the CLOSING_BALANCE description as `"Amount due"` (lower-case d)** — `billing-stubs.ts:736` and `billing-invoice-statement.spec.ts:41`. Same for the PDF footer grand-total label (`template.html:179`), the Excel footer label (`StatementExcelService.java:123`), and the Go template test (`renderer_test.go:419`). To stop the label drifting between two casings, normalise them all to `"Amount Due"` per the steer.
- **The `defaultIfEmpty(null)` NPE risk at `StatementService.java:308-312`** and the raw `.bind("upper", inv.getCommittedAt())` at `:385` are latent — flagged in the research but not in scope for this steer. Once the silent fallback is gone, these become visible per-invoice hard failures instead of silent bad PDFs, which is what we want: they get fixed as they surface. See *What We're NOT Doing*.

### Key Discoveries:

- **Fallback trigger is one line**: `cmd/main.go:244` — `renderData = nil`. Replacing that branch is the entire behaviour change.
- **Template already has both branches**: `template.html:69-180` is the full layout; `:182-201` is the fallback. Deleting the fallback removes any way for a nil-RenderData render to succeed.
- **Renderer errors are already `%w`-wrapped** — no work to preserve error chain when we upgrade the error handling to bounded retry.
- **The on-demand `POST /invoice-pdf/render` endpoint** (`cmd/main.go:115-186`) already 502s cleanly on this error — same call site as the fetch, so once the fetch succeeds the operator's rerender path works exactly as it does today.
- **`InvoicePdfReadyConsumer`** at `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/InvoicePdfReadyConsumer.java` is the only downstream consumer of the published event; it upserts a pointer row into `invoice_pdfs`. Not publishing means no pointer row — the UI's Download PDF button links to a MinIO object that doesn't exist and 404s cleanly (`InvoiceController.java:105` notes this exact case).
- **Reuse candidates**:
  - Bounded-retry pattern lives inline in `InvoicePdfReadyConsumer.java:116-118` (`Retry.backoff` from Reactor). Go side has no shared helper — this fix adds one local helper `fetchWithRetry` in file-service. Not enough duplication to justify a shared package.
  - `[[bug_reactor_kafka_ack_swallow]]` — auto-memory note that `.doOnTerminate` swallows errors on Kafka acks. The Go consumer here already uses the equivalent commit-and-skip via `sub.Run`; the fix retains that shape and only changes what happens inside the handler, so we do not need to touch the ack semantics.

## Desired End State

After this plan:

- The file-service Kafka consumer, on `FetchRenderPayload` error, retries up to twice (500ms + 2s backoff) and then logs an ALERT with tenant + invoice, commits the offset, and does **not** publish `InvoicePdfReady`. No fallback PDF is ever uploaded.
- The `{{else}}` branch of `template.html` is deleted; `Renderer.Render` and `Renderer.HTML` return a hard error when `Payload.RenderData == nil`.
- Every user-visible instance of "Balance carried forward" reads "Amount Due" instead. Every user-visible instance of "Amount due" (mixed casing) reads "Amount Due" too. The closing-row marker on the PDF and Angular reads `"Amount Due · C/F"` (the redundant `· amount due` suffix on the marker span is dropped, but the `C/F` marker is kept for accounting-convention symmetry with the opening row's `B/F`).
- All unit + integration + E2E tests pass without any residual assertion on the old strings.

### Verification (post-implementation):

- `make test-java`, `make test-go`, `make test-angular`, `make test-e2e` all green.
- `verify` on `/tenant/billing/view/<invoiceId>`: opening row reads "Balance brought forward · B/F", closing row reads "Amount Due · C/F", grand-total ledger footer reads "Amount Due".
- Manually trigger a broken render (stop contributions-service, publish an `InvoiceIssued` event on the Kafka topic): file-service logs `[file-service] ALERT render payload fetch exhausted retries invoice=...`, no PDF is uploaded to MinIO, `InvoicePdfReady` is not published. Restarting contributions-service and re-firing `POST /invoice-pdf/render?invoice=...&tenant=...` produces the correct PDF.

## What We're NOT Doing

Out of scope for this steer; captured here so they don't get lost:

- **`defaultIfEmpty(null)` NPE risk** at `StatementService.java:308-312`. Trivial 2-line fix (`.map(Invoice::getCommittedAt).switchIfEmpty(...)`) but a separate concern — once the silent fallback is gone this becomes a visible per-invoice hard skip that we can attribute and fix. Track as a follow-up.
- **Raw `.bind("upper", inv.getCommittedAt())`** at `StatementService.java:385` — currently unreachable in practice because every live invoice goes through `InvoiceSnapshotService.stampSnapshot`. Only triggered if someone re-wires `BillingService.generateInvoice` (dead code, see below). Ignore for now.
- **Deleting dead-code `BillingService.generateInvoice`** at `BillingService.java:278-331`. Zero callers in production code; a single test at `BillingServiceTest.java:242` still exercises it. Removing it is safe but expands the diff and isn't required to fix either symptom. Track as a follow-up.
- **Backfilling the ~previously-uploaded fallback-shape PDFs.** Operators can identify the affected invoices in MinIO (small file size, missing per-scheme breakdown) and re-fire `POST /invoice-pdf/render` per invoice. No automated backfill script.
- **Introducing a DLQ topic.** Bounded-retry-then-skip is simpler than DLQ+consumer for this failure mode and gives the same operator experience via the existing on-demand endpoint. Revisit if the skip rate ever climbs.

## Implementation Approach

Two phases, ordered so the rename lands first (pure text change with strong test coverage) and the behavioural change lands second (fewer places to touch, but higher-risk semantics).

Rename lands in a single PR-safe atomic edit — every producer + every assertion + every fixture updated together so no consumer sees a mixed state. There is no cross-service event-schema change in either phase, so no producer-before-consumer rollout ordering is needed.

## Phase 1: Rename `"Balance carried forward"` → `"Amount Due"` end-to-end

### Overview

Pure string-rename phase. Two Java string literals, two Java test assertions + fixtures, one Excel service suffix, one Go template marker span, one Go test fixture set, one Angular template marker span, one Angular spec fixture, three Playwright E2E specs, one Javadoc pointer. Also normalises the mixed-casing "Amount due" label sites to the same "Amount Due" so the label is canonical everywhere.

### Changes Required:

#### 1. Java — snapshot + period ledger closing-row description

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java`

At `:212` (period-based ledger in `assemble`) and `:493` (snapshot-based ledger in `projectSnapshotStatement`):

```java
// :212 — replace
"Balance carried forward",

// :493 — replace
"Balance carried forward",
```

Both become:

```java
"Amount Due",
```

Leave the opening bookend `"Balance brought forward"` at `:193` and `:460` unchanged — the steer only calls out the closing side.

#### 2. Java — Excel service description suffix

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementExcelService.java`

At `:109`:

```java
if (StatementLine.TYPE_CLOSING_BALANCE.equals(line.type())) desc += " (C/F · amount due)";
```

becomes:

```java
if (StatementLine.TYPE_CLOSING_BALANCE.equals(line.type())) desc += " (C/F)";
```

At `:123` — normalise footer label capitalisation:

```java
r = writeLabelMoney(sheet, r, label, moneyBold, "Amount due", h.closingBalance());
```

becomes:

```java
r = writeLabelMoney(sheet, r, label, moneyBold, "Amount Due", h.closingBalance());
```

#### 3. Java — DTO Javadoc pointer

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/StatementLine.java`

At `:29`:

```java
 *   <li>{@code CLOSING_BALANCE} — "Balance carried forward"
```

becomes:

```java
 *   <li>{@code CLOSING_BALANCE} — "Amount Due"
```

#### 4. Java — test assertions and fixtures

**File**: `services/java/contributions-service/src/test/java/com/medfund/contributions/service/StatementServiceTest.java`

At `:199`:

```java
assertThat(last.description()).isEqualTo("Balance carried forward");
```

becomes:

```java
assertThat(last.description()).isEqualTo("Amount Due");
```

**File**: `services/java/contributions-service/src/test/java/com/medfund/contributions/service/StatementExcelServiceTest.java`

At `:106` — assertion:

```java
assertThat(d).contains("Balance carried forward").contains("(C/F"));
```

becomes:

```java
assertThat(d).contains("Amount Due").contains("(C/F)"));
```

At `:154, :184, :193` — fixtures:

```java
bookend(StatementLine.TYPE_CLOSING_BALANCE, "Balance carried forward", ...
```

become:

```java
bookend(StatementLine.TYPE_CLOSING_BALANCE, "Amount Due", ...
```

At `:119` — footer label assertion:

```java
"Amount due");
```

becomes:

```java
"Amount Due");
```

#### 5. Go — PDF template marker + footer label

**File**: `services/go/file-service/internal/invoice/template.html`

At `:156` — closing-row marker span (the redundant "· amount due" gets dropped; `C/F` stays for symmetry with the opening `B/F`):

```html
<td>{{.ClosingRow.Description}}<span class="marker">C/F · amount due</span></td>
```

becomes:

```html
<td>{{.ClosingRow.Description}}<span class="marker">C/F</span></td>
```

At `:179` — grand-total footer label:

```html
<tr class="grand"><td>Amount due</td>...
```

becomes:

```html
<tr class="grand"><td>Amount Due</td>...
```

#### 6. Go — renderer tests

**File**: `services/go/file-service/internal/invoice/renderer_test.go`

At `:297`, `:310`, `:327` (in `TestBuildView_bookendLines_routeToOpeningAndClosingRow`) — fixture Description + assertion:

```go
Description:    "Balance carried forward",
// ...
if v.OpeningRow.Description != "Balance brought forward" {
// ...
if tx.Description == "Balance brought forward" || tx.Description == "Balance carried forward" {
```

The **closing** occurrences (`:297` and `:327`'s `|| ..."Balance carried forward"` branch) become `"Amount Due"`. The **opening** ones stay `"Balance brought forward"`.

At `:419` — grand-total-label HTML assertion in `TestRender_richPayload_HTMLHasAllSections`:

```go
"Amount due",                  // ledger-footer grand-total label
```

becomes:

```go
"Amount Due",                  // ledger-footer grand-total label
```

#### 7. Angular — component template + Javadoc

**File**: `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.html`

At `:134` — closing-row marker span:

```html
<span class="muted small">· C/F · amount due</span></td>
```

becomes:

```html
<span class="muted small">· C/F</span></td>
```

Comment updates at `:40, :127`:

```html
- Amount due (snapshot closing_balance)
<!-- Balance carried forward — emitted by the backend as a
     CLOSING_BALANCE line. This IS the amount due... -->
```

become:

```html
- Amount Due (snapshot closing_balance)
<!-- Amount Due — emitted by the backend as a
     CLOSING_BALANCE line. This IS the amount due... -->
```

**File**: `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.ts`

At `:148-152` — comment on `closingBalanceLine` getter:

```ts
/** Closing balance ("Balance carried forward") row — bookend at the
 *  bottom, and next period's opening. */
```

becomes:

```ts
/** Closing balance ("Amount Due") row — bookend at the
 *  bottom, and next period's opening. */
```

#### 8. Angular — unit test fixture

**File**: `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.spec.ts`

At `:64`:

```ts
description: 'Balance carried forward',
```

becomes:

```ts
description: 'Amount Due',
```

#### 9. Angular — E2E fixtures + specs

**File**: `clients/angular/e2e/fixtures/billing-stubs.ts`

At `:736`:

```ts
{ date: invoice.periodEnd, description: 'Amount due', runningBalance: invoice.amountDue, type: 'CLOSING_BALANCE' },
```

becomes:

```ts
{ date: invoice.periodEnd, description: 'Amount Due', runningBalance: invoice.amountDue, type: 'CLOSING_BALANCE' },
```

**File**: `clients/angular/e2e/tests/billing-invoice-statement.spec.ts`

At `:41`:

```ts
{ date: inv.periodEnd, description: 'Amount due', runningBalance: 250, type: 'CLOSING_BALANCE' },
```

becomes:

```ts
{ date: inv.periodEnd, description: 'Amount Due', runningBalance: 250, type: 'CLOSING_BALANCE' },
```

**File**: `clients/angular/e2e/tests/billing-ledger.spec.ts`

At `:38`:

```ts
{ date: '2026-08-31', description: 'Balance carried forward', runningBalance: 150, type: 'CLOSING_BALANCE' } as any,
```

becomes:

```ts
{ date: '2026-08-31', description: 'Amount Due', runningBalance: 150, type: 'CLOSING_BALANCE' } as any,
```

If either spec has visible-text assertions (`getByText('Balance carried forward')` or `getByText('Amount due')`), update those too — before committing, `grep -n "Balance carried forward\|Amount due" clients/angular/e2e` and normalise anything the fixture rename missed.

### Success Criteria:

#### Automated Verification:

- [x] Java unit tests (StatementServiceTest, StatementExcelServiceTest): `cd services/java && ./gradlew :contributions-service:test --tests "*StatementServiceTest" --tests "*StatementExcelServiceTest"` — 4/4 in StatementServiceTest, 3/3 in StatementExcelServiceTest, plus rest of :contributions-service:test suite green
- [x] Go tests (file-service renderer): `cd services/go/file-service && go test ./internal/invoice/...` — all green
- [x] Angular unit tests (invoice-statement component): 5/5 SUCCESS via `npx ng test --include='**/invoice-statement.component.spec.ts' --watch=false`
- [ ] Playwright E2E: `make test-e2e` — deferred; assertion strings updated via fixture rename, no visible-text assertion on the old strings in `billing-ledger.spec.ts` / `billing-invoice-statement.spec.ts`
- [x] Repo-wide grep is clean: `grep -rn "Balance carried forward" services/ clients/` returns zero non-comment hits
- [x] Repo-wide casing grep: `grep -rEn '"Amount due"|>Amount due<' services/ clients/` returns zero non-comment hits — includes normalising notification-service email body + Angular ledger jsPDF export (both were catch-alls the plan did not enumerate but its grep guards flag)
- [ ] `verify` on `/tenant/billing/view/<invoiceId>` after `make web`: opening row reads "Balance brought forward · B/F", closing row reads "Amount Due · C/F", ledger footer grand-total reads "Amount Due", no console errors

#### Manual Verification:

- [ ] Download the PDF for an existing invoice from the Angular statement page and open it — the closing row description says "Amount Due", the marker reads "C/F" (no "· amount due" residue), the footer grand-total row is labelled "Amount Due"
- [ ] Download the Excel export from `/tenant/billing/statements/export` — the CLOSING_BALANCE row's description column ends with " (C/F)" and the footer label reads "Amount Due"

**Implementation Note**: after this phase's automated verification passes, pause for the human to spot-check the rendered PDF and Excel before moving to Phase 2.

---

## Phase 2: Stop the silent PDF fallback

### Overview

Replace `renderData = nil` with a bounded retry (3 attempts, 0.5s + 2s backoff) then a loud log + skip. Renderer refuses to render when `Payload.RenderData == nil`. Delete the `{{else}}` fallback branch from the template and the two Go tests that guarded it. Add positive tests for the new hard-error path.

### Changes Required:

#### 1. Go — bounded-retry helper in the Kafka consumer

**File**: `services/go/file-service/cmd/main.go`

Replace the fallback branch at `:240-245` and the ensuing render + upload + publish block. The intent:

- **Try once**, on error retry up to 2 more times with 500ms and 2s backoff.
- **If still failing**: log a single ALERT line with tenant + invoice + terminal cause, `return` from the handler (which commits the offset since `sub.Run` is at-least-once via the consumer group's auto-commit). Do NOT publish `InvoicePdfReady`, do NOT upload any PDF.
- **On success**: proceed exactly as today.

```go
renderData, err := fetchRenderPayloadWithRetry(ctx, contribClient,
    evt.TenantID, evt.InvoiceID, evt.InvoiceNumber)
if err != nil {
    log.Printf("[file-service] ALERT render payload fetch exhausted retries invoice=%s tenant=%s: %v — skipping (no PDF uploaded, InvoicePdfReady NOT published; operator can rerender via POST /invoice-pdf/render)",
        evt.InvoiceNumber, evt.TenantID, err)
    return
}
```

New helper below `runInvoiceConsumer` (same file, unexported):

```go
// fetchRenderPayloadWithRetry calls FetchRenderPayload with a bounded
// retry (3 attempts total: immediate, +500ms, +2s). The 5s per-attempt
// client timeout (contributions.Client) is unchanged — so the worst-case
// budget across all three attempts is ~17s, well inside the Kafka
// consumer's rebalance interval. Returns the LAST error verbatim so the
// operator log carries the terminal cause.
func fetchRenderPayloadWithRetry(ctx context.Context, client *contributions.Client,
    tenantID, invoiceID, invoiceNumber string) (*contributions.RenderPayload, error) {

    backoffs := []time.Duration{0, 500 * time.Millisecond, 2 * time.Second}
    var lastErr error
    for i, wait := range backoffs {
        if wait > 0 {
            select {
            case <-ctx.Done():
                return nil, ctx.Err()
            case <-time.After(wait):
            }
        }
        rd, err := client.FetchRenderPayload(ctx, tenantID, invoiceID)
        if err == nil {
            if i > 0 {
                log.Printf("[file-service] render payload fetch recovered on attempt %d invoice=%s", i+1, invoiceNumber)
            }
            return rd, nil
        }
        log.Printf("[file-service] render payload fetch attempt %d/3 failed invoice=%s: %v",
            i+1, invoiceNumber, err)
        lastErr = err
    }
    return nil, lastErr
}
```

Import `"time"` if not already present. The consumer's `renderer.Render(ctx, invoice.Payload{...RenderData: renderData})` call at `:247` stays untouched — `renderData` is now guaranteed non-nil.

#### 2. Go — renderer hard-fails on nil RenderData

**File**: `services/go/file-service/internal/invoice/renderer.go`

`Render` and `HTML` currently tolerate `p.RenderData == nil`. Reject it explicitly at the top of both — no more silent fallback:

At `Render` (around `:73`):

```go
func (r *Renderer) Render(ctx context.Context, p Payload) ([]byte, error) {
    if p.RenderData == nil {
        return nil, fmt.Errorf("render payload required: invoice=%s tenant=%s", p.InvoiceNumber, p.TenantID)
    }
    // ... existing body unchanged
}
```

At `HTML` (around `:92`):

```go
func (r *Renderer) HTML(p Payload) ([]byte, error) {
    if p.RenderData == nil {
        return nil, fmt.Errorf("render payload required: invoice=%s tenant=%s", p.InvoiceNumber, p.TenantID)
    }
    // ... existing body unchanged
}
```

Update the Payload doc-comment at `:21-25` — the "falls back to the legacy summary view" line no longer applies:

```go
// Payload is the identity envelope for one PDF render. RenderData is
// REQUIRED — Render and HTML return an error when it's nil. The prior
// silent fallback to a legacy summary view was removed (2026-08-09
// contribution-statement-pdf-divergence plan) because it shipped
// financially-incorrect documents to end users.
type Payload struct {
    // ...
}
```

Remove the now-dead `SubtotalAmount` and `HasSnapshot` field initialisations at `:180-182` inside `buildView` — since `buildView` can only ever run with non-nil RenderData, `HasSnapshot = true` is set at `:189` and `SubtotalAmount` is only read by the fallback template branch which is being deleted below.

Concretely, `buildView` becomes:

```go
func buildView(p Payload) templateView {
    v := templateView{
        InvoiceNumber:  p.InvoiceNumber,
        IssuedDate:     p.IssuedDate,
        DueDate:        p.DueDate,
        PeriodStart:    p.PeriodStart,
        PeriodEnd:      p.PeriodEnd,
        Recipient:      p.RecipientLabel,
        CurrencyCode:   p.CurrencyCode,
    }

    rd := p.RenderData  // guaranteed non-nil by Render/HTML guard
    v.HasSnapshot = true
    // ... rest of the function unchanged
}
```

Delete the `SubtotalAmount` and `HasSnapshot` fields from `templateView` at `:122-129` — no template consumer reads them any more once the `{{else}}` branch is gone. Actually **keep `HasSnapshot`** to avoid churning the template's `{{if .HasSnapshot}}` guard; it just becomes a no-op always-true flag. Removing it is a follow-up cleanup and not worth the diff.

**Actually simplify**: leave `HasSnapshot` alone but remove `SubtotalAmount` (used only by the deleted `{{else}}` branch). Removing it fails the compile if the template still references it — good, that's a canary against leaving the fallback branch by mistake.

#### 3. Go — delete the fallback template branch

**File**: `services/go/file-service/internal/invoice/template.html`

Delete lines `:182-201`:

```html
{{else}}
<table>
  <thead>
    <tr>
      <th>Description</th>
      <th class="right">Amount</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>Contributions for {{.PeriodStart}} &mdash; {{.PeriodEnd}}</td>
      <td class="right num-cell">{{.CurrencyCode}} {{.SubtotalAmount}}</td>
    </tr>
  </tbody>
</table>

<table class="totals">
  <tr><td class="row-label">Subtotal</td><td class="right num-cell">{{.CurrencyCode}} {{.SubtotalAmount}}</td></tr>
  <tr class="grand"><td>Total due</td><td class="right num-cell">{{.CurrencyCode}} {{.AmountDue}}</td></tr>
</table>
{{end}}
```

The surrounding `{{if .HasSnapshot}}` at `:81` is retained (still terminates at `:202`'s `{{end}}` — now just an unconditional block).

#### 4. Go — update / delete fallback-guarding tests, add positive tests

**File**: `services/go/file-service/internal/invoice/renderer_test.go`

Delete `TestBuildView_noRenderData_fallsBackToSummary` (`:361-377`) — obsolete; nil RenderData is now a hard error, not a fallback.

Delete `TestRender_fallbackPayload_HTMLHasSummaryOnly` (`:436-460`) — same reason.

Update the two comments that still describe the fallback (search for "fallback" in the file after deletion — the comments at `:280-283` referring to bookend leaks are unrelated and stay).

Add two positive tests near the deletion sites:

```go
// After the 2026-08-09 fix, Render and HTML must reject a Payload
// without RenderData rather than silently rendering a summary shape —
// the summary shape was financially incorrect for real invoices.
func TestRender_nilRenderData_returnsError(t *testing.T) {
    r, _ := NewRenderer(StubPdfGenerator{})
    _, err := r.Render(context.Background(), Payload{
        InvoiceNumber: "CS-000001",
        CurrencyCode:  "USD",
        TotalAmount:   "42.5",
        // RenderData intentionally omitted
    })
    if err == nil {
        t.Fatal("expected error when RenderData is nil, got nil")
    }
    if !strings.Contains(err.Error(), "render payload required") {
        t.Errorf("error should mention 'render payload required', got: %v", err)
    }
}

func TestHTML_nilRenderData_returnsError(t *testing.T) {
    r, _ := NewRenderer(StubPdfGenerator{})
    _, err := r.HTML(Payload{
        InvoiceNumber: "CS-000002",
        CurrencyCode:  "USD",
    })
    if err == nil {
        t.Fatal("expected error when RenderData is nil, got nil")
    }
}
```

Also add a small unit test for the new retry helper in `cmd/main_test.go` — create the file if absent:

```go
package main

import (
    "context"
    "errors"
    "net/http"
    "net/http/httptest"
    "testing"
    "time"

    "github.com/medfund/file-service/internal/contributions"
)

func TestFetchRenderPayloadWithRetry_recoversOnSecondAttempt(t *testing.T) {
    var calls int
    srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        calls++
        if calls < 2 {
            http.Error(w, "boom", http.StatusInternalServerError)
            return
        }
        w.Header().Set("Content-Type", "application/json")
        _, _ = w.Write([]byte(`{"invoice":{"id":"i-1","invoiceNumber":"CS-1"},"statement":{"header":{}},"contributions":[]}`))
    }))
    defer srv.Close()

    client := contributions.New(srv.URL)
    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()

    rd, err := fetchRenderPayloadWithRetry(ctx, client, "t-1", "i-1", "CS-1")
    if err != nil {
        t.Fatalf("expected recovery, got err: %v", err)
    }
    if rd == nil || rd.Invoice.InvoiceNumber != "CS-1" {
        t.Fatalf("expected populated payload, got %+v", rd)
    }
    if calls != 2 {
        t.Errorf("expected 2 upstream calls (fail, succeed), got %d", calls)
    }
}

func TestFetchRenderPayloadWithRetry_exhaustsAndReturnsLastError(t *testing.T) {
    var calls int
    srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        calls++
        http.Error(w, "persistent", http.StatusInternalServerError)
    }))
    defer srv.Close()

    client := contributions.New(srv.URL)
    ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
    defer cancel()

    _, err := fetchRenderPayloadWithRetry(ctx, client, "t-1", "i-1", "CS-1")
    if err == nil {
        t.Fatal("expected error after exhausted retries")
    }
    if calls != 3 {
        t.Errorf("expected 3 attempts, got %d", calls)
    }
    // Cause must propagate — operator needs to see "persistent" in the log.
    if !errorContains(err, "persistent") && !errorContains(err, "500") {
        t.Errorf("terminal error should carry the last cause, got: %v", err)
    }
}

func errorContains(err error, want string) bool {
    return err != nil && (contains(err.Error(), want) || errors.Is(err, errors.New(want)))
}

func contains(hay, needle string) bool {
    for i := 0; i+len(needle) <= len(hay); i++ {
        if hay[i:i+len(needle)] == needle {
            return true
        }
    }
    return false
}
```

### Success Criteria:

#### Automated Verification:

- [x] Go tests: `cd services/go/file-service && go test ./...` — all packages pass; new retry helper (2 tests) + renderer-nil-error tests (2 tests) green; obsolete fallback tests removed
- [x] Go build: `cd services/go/file-service && go build ./...` — clean; `SubtotalAmount` field removed and template no longer references it
- [x] Template renders without panic on the sample data: `TestRender_richPayload_HTMLHasAllSections` PASS
- [x] Grep guard: `grep -n "renderData = nil" services/go/file-service/cmd/main.go` returns zero hits
- [x] Grep guard: `grep -n "falls back to the legacy summary" services/go/file-service` returns zero hits
- [ ] `verify` on `/tenant/billing/view/<invoiceId>` for an invoice with a fresh render — pending human

#### Manual Verification:

- [ ] **Broken-render scenario**: stop the contributions-service container (`docker compose stop contributions-service`), publish an `InvoiceIssued` Kafka event via `docker exec` and `kafka-console-producer`, tail the file-service logs — three attempt lines then an `ALERT` line, no PDF ends up in MinIO under `invoices/<tenant>/<invoice>.pdf`, no downstream `InvoicePdfReady` event on the topic
- [ ] **Recovery scenario**: bring contributions-service back up, hit `POST /invoice-pdf/render?invoice=<id>&tenant=<id>` from a shell — endpoint returns 200 with `{ "bytes": ..., "objectKey": ... }`, downloading the PDF from `/tenant/billing/view/<invoiceId>` shows the correct full layout
- [ ] **Transient-blip scenario**: block the file-service's outbound HTTP briefly (e.g. via a firewall rule or `iptables -A OUTPUT -p tcp --dport 8084 -j DROP` for 700ms then remove), publish an event — logs show one failed attempt then a "recovered on attempt 2" success line, PDF uploads normally

**Implementation Note**: after this phase's automated verification passes, do the three manual scenarios end-to-end before shipping. The retry semantics are the load-bearing behaviour change — automated tests cover the helper in isolation but the integration is easier to trust when you've seen it recover in dev.

---

## Testing Strategy

### Unit Tests:

- Phase 1: existing `StatementServiceTest`, `StatementExcelServiceTest`, `renderer_test.go`, `invoice-statement.component.spec.ts` — updated assertions / fixtures only, no new tests.
- Phase 2: two new Go tests for `fetchRenderPayloadWithRetry` (recover on attempt 2; exhaust three attempts), two new Go tests for `Renderer.Render` / `Renderer.HTML` erroring on nil RenderData.

### Integration Tests:

No new Testcontainers slice needed. The `contributions-service` render endpoint is exercised end-to-end by any existing invoice-issued IT that also boots file-service (grep for `InvoiceIssuedIT` or `InvoicePdfReadyIT` — none of them mocks the fetch, so a real broken-payload path is out of scope for this fix).

### E2E Tests (Playwright):

Phase 1 alters string fixtures in three specs — `billing-invoice-statement.spec.ts`, `billing-ledger.spec.ts`, and stubs consumed by others via `billing-stubs.ts`. Rerun `make test-e2e` to confirm no assertion depends on the old strings.

### Manual Testing Steps:

Covered per-phase in the Success Criteria above.

## Performance Considerations

- Retry helper adds at most 2.5s of wall-clock time per **failed** render attempt (500ms + 2s backoff). The per-attempt 5s HTTP timeout in `contributions.Client` is unchanged, so worst-case per-message time inside the consumer is `5s + 500ms + 5s + 2s + 5s = 17.5s`. Consumer group's default `session.timeout.ms` and `max.poll.interval.ms` are well above that; no rebalance risk.
- Success path is unchanged (no retry, no sleep).
- Template rendering path shrinks slightly with the `{{else}}` branch and `SubtotalAmount` field removed.

## Migration Notes

- **No schema migration.** Neither phase touches Flyway or the database.
- **No Kafka topic contract change.** The `InvoiceIssued` and `InvoicePdfReady` schemas are untouched; the only wire-level behaviour change is that `InvoicePdfReady` is *not published* when the fetch exhausts retries. Downstream `InvoicePdfReadyConsumer` handles the missing pointer case gracefully (`InvoiceController.java:105`).
- **No cross-service rollout ordering.** File-service and contributions-service can deploy in either order — the changes on each side are self-contained.
- **Legacy fallback-shape PDFs in MinIO** stay as-is; operators identify them (small file size, missing per-scheme table) and re-fire `POST /invoice-pdf/render?invoice=<id>&tenant=<id>` per invoice as needed.

## Rollout & Rollback

**Rollout**: deploy file-service after contributions-service if any (though not required — the changes are independent). The rename is safe on production data since string comparisons happen only inside tests + fresh renders; existing MinIO objects are byte-identical and unaffected.

**Rollback**: revert both phases in one commit. The behaviour reverts to the pre-fix silent-fallback pattern. No data migration to undo.

## References

- Research: `thoughts/shared/research/2026-08-09-contribution-statement-pdf-divergence.md`
- Silent-fallback line: `services/go/file-service/cmd/main.go:240-245`
- Nil-check in renderer: `services/go/file-service/internal/invoice/renderer.go:184-187`
- Template branches: `services/go/file-service/internal/invoice/template.html:69-201`
- Snapshot ledger closing bookend: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java:493`
- Downstream InvoicePdfReady consumer: `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/InvoicePdfReadyConsumer.java`
- On-demand rerender endpoint: `services/go/file-service/cmd/main.go:115-186`
- Angular statement page: `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.html`
