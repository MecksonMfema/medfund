---
date: 2026-08-09
researcher: methuseli
git_commit: 117d24e07b8239534826dd4484dfa5b7adeb1e69
branch: main
repository: medfund
topic: "Why do two contribution-statement PDFs from 2026-08-08 render differently (CS-004418 stripped vs CS-236251 detailed), and where does the 'Balance carried forward' label live?"
tags: [research, codebase, contribution-statement, pdf, file-service, contributions-service, ledger]
status: complete
last_updated: 2026-08-09
last_updated_by: methuseli
---

# Research: Contribution-statement PDF divergence — CS-004418 vs CS-236251

**Date**: 2026-08-09 · **Researcher**: methuseli · **Commit**: 117d24e · **Branch**: main

## Research Question

Why are the two contribution statements coming out differently — comparing `contribution-statement-CS-004418.pdf` (Jane Moyo, "Contributions for 2026-09-01 — 2026-09-30 · Total due USD 161.00", single-line summary) with `contribution-statement-CS-236251.pdf` (Matthew Brown, opening/closing balances, per-member breakdown grouped by scheme, double-entry ledger with B/F & C/F, "Amount due")? The second one is correct. Also, the "Balance carried forward" label should just read "Amount Due" — where does that come from?

## Summary

There is **one PDF template with two branches**, not two renderers. The full layout only renders when the file-service Kafka consumer successfully calls `contributions-service`' internal render-payload endpoint; on any failure it **silently falls back** to a legacy summary layout — which is exactly the CS-004418 shape.

- **CS-236251 = success path.** `FetchRenderPayload` returned data → `Payload.RenderData != nil` → template enters the `{{if .HasSnapshot}}` branch → per-scheme table + ledger + totals footer.
- **CS-004418 = fallback path.** `FetchRenderPayload` failed → `renderData = nil` was passed on → template enters the `{{else}}` branch → single "Contributions for &lt;period&gt;" row + "Subtotal" + "Total due".

The failure is not logged into the PDF itself — the caller catches, prints one warning line, and keeps going. That is why the two documents look like different systems produced them.

The "Balance carried forward" text is a string literal in `StatementService.java` on the Java side (two sites — the period-based and the snapshot-based ledger assemblers). The PDF template shows it verbatim from `ClosingRow.Description`, followed by a static "C/F · amount due" marker span.

## Findings

### 1. Single template, two branches (file-service)

`services/go/file-service/internal/invoice/renderer.go:184-187` — the branch switch. Empty `RenderData` returns early with only the fallback fields populated:

```go
rd := p.RenderData
if rd == nil {
    return v
}

v.HasSnapshot = true
```

`services/go/file-service/internal/invoice/template.html:69` — full layout guarded by `{{if .HasSnapshot}}`, closing at line 180.
`services/go/file-service/internal/invoice/template.html:182-201` — the `{{else}}` fallback. The exact strings from CS-004418 are here:

```
<td>Contributions for {{.PeriodStart}} &mdash; {{.PeriodEnd}}</td>   (line 192)
<td class="row-label">Subtotal</td>                                   (line 199)
<tr class="grand"><td>Total due</td>...                               (line 200)
```

This proves CS-004418 went through the `{{else}}` branch.

### 2. Fallback trigger — the silent catch (file-service)

`services/go/file-service/cmd/main.go:240-245` — Kafka consumer swallows any error from the internal fetch and continues with a nil payload:

```go
renderData, err := contribClient.FetchRenderPayload(ctx, evt.TenantID, evt.InvoiceID)
if err != nil {
    log.Printf("[file-service] render payload fetch failed invoice=%s: %v — falling back to summary layout",
        evt.InvoiceNumber, err)
    renderData = nil
}
```

The one-line log is the only signal — the produced PDF is uploaded and `InvoicePdfReady` is published exactly the same way as a good render. There is no retry, no dead-letter, and no marker in the object metadata.

The **on-demand** rerender route does the same fetch but does NOT fall back — it 502s (`services/go/file-service/cmd/main.go:126-131`). So operators re-firing `POST /invoice-pdf/render?invoice=…` would get an error rather than a bad PDF.

### 3. The fetch client and its ceiling (file-service)

`services/go/file-service/internal/contributions/client.go:26-31` — HTTP client is created with a **5-second timeout**. Any slower response from contributions-service triggers the fallback.

`services/go/file-service/internal/contributions/client.go:37-66` — the failure modes surface as errors from `FetchRenderPayload`:
- non-2xx status → `contributions-service %d: %s`
- transport failure / timeout → `call contributions-service: <cause>`
- unparseable JSON → `decode payload: <cause>`

All three become `renderData = nil` at the consumer.

### 4. What the internal endpoint does (contributions-service)

`services/java/contributions-service/src/main/java/com/medfund/contributions/controller/InternalRenderController.java:46-55` — `Mono.zip` of three reactive pulls:

1. `invoiceRepository.findById(id)` — 404 as `InvoiceNotFoundException` if missing.
2. `statementService.generateForInvoice(id)` — assembles the snapshot ledger.
3. `invoiceListService.contributionsFor(id).collectList()` — per-member rows.

If any of the three errors, the zip errors, and the endpoint returns 500 → fallback fires in file-service.

The endpoint sits under `/api/v1/internal/**` (permitted without JWT in `SecurityConfig`) but is tenant-scoped via `X-Tenant-ID` (set at `services/go/file-service/internal/contributions/client.go:49`).

### 5. Where errors could originate in `generateForInvoice`

`services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java:295-358` — the assembly.

Two brittle spots worth flagging:

- **Line 308-312** — `defaultIfEmpty(null)` on `Mono<Instant>` is a Reactor foot-gun. Reactor rejects null values in `Mono`, so if `priorInvoiceId` points to an invoice that has since been hard-deleted (rare but possible via revoke flows), the `findById(...).map(::getCommittedAt).defaultIfEmpty(null)` line throws NPE at subscribe time → 500 out of `/render-payload`.
- **Line 385** — `.bind("upper", inv.getCommittedAt())`. If `committedAt` is null (any invoice inserted through a code path that did not run `InvoiceSnapshotService.stampSnapshot`), R2DBC throws on the raw `bind()`. See §7 for whether such a path is still live.

Both would produce a 500 for a specific invoice while other invoices of the same tenant/day continue rendering correctly — exactly the CS-004418 vs CS-236251 shape.

### 6. Where the "Balance carried forward" label lives (Java, not template)

`services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java:212` — **period-based** ledger (`generateStatement`, used by the `/statements` listing endpoint).
`services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java:493` — **snapshot-based** ledger (`projectSnapshotStatement`, used by `/invoices/{id}/render-payload` and thus by every PDF).

Both are string literals. The PDF template renders them verbatim:

`services/go/file-service/internal/invoice/template.html:156` — `<td>{{.ClosingRow.Description}}<span class="marker">C/F · amount due</span></td>`. So the row currently reads "Balance carried forward · C/F · amount due" (description + marker). The user wants the description part changed to "Amount Due" so the row just reads "Amount Due · C/F · amount due" — or the marker dropped so it just reads "Amount due".

Mirror sites that will drift if only one is updated:

- `services/java/contributions-service/src/test/java/com/medfund/contributions/service/StatementServiceTest.java:189,199` — asserts exact "Balance brought forward" / "Balance carried forward".
- `services/java/contributions-service/src/test/java/com/medfund/contributions/service/StatementExcelServiceTest.java:104,106,149,154,176,184,191,193` — asserts the same in the Excel export.
- `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.html:127` (and comments at :40, :63, :127) — on-screen version.
- `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.spec.ts:53,64` — Angular fixtures.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/StatementLine.java:20,29` — Javadoc pointer.

### 7. Legacy `generateInvoice` is dead code

`services/java/contributions-service/src/main/java/com/medfund/contributions/service/BillingService.java:278-331` — publishes `InvoiceIssued` with **null snapshot fields and null recipientName** (the comment at line 323-328 explicitly acknowledges "predates snapshot capture"). If anyone still called it, the resulting invoice would have `committed_at = null` and § 5's line-385 bind would 500 the internal endpoint → CS-004418-shape fallback for every PDF.

But — a grep for callers turned up **zero**. Neither controllers nor tests exercise it. It is dead code that can be removed (or, if kept for a compat reason we can't see, it should at least call `invoiceSnapshotService.stampSnapshot` before save).

The only live invoice-creating path is `commitBilling → doCommit → generateInvoicesFor → persistInvoiceFor` at `BillingService.java:1217-1287`, which **does** stamp the snapshot (line 1245) and does publish the rich payload with `committedAt / openingBalance / closingBalance / paymentsInWindow / adjustmentsInWindow / recipientName` (lines 1280-1285).

## Cross-service flow

```
BillingService.commitBilling                    (contributions-service, Java)
  → InvoiceSnapshotService.stampSnapshot        (sets committed_at, opening_balance, closing_balance, ...)
  → invoiceRepository.save
  → ContributionEventPublisher.publishInvoiceIssued  → topic medfund.contributions.invoice-issued

runInvoiceConsumer                              (file-service, Go — cmd/main.go:228)
  → contribClient.FetchRenderPayload            (5s HTTP GET, X-Tenant-ID header)
      → contributions-service GET /api/v1/internal/invoices/{id}/render-payload
      → InternalRenderController.renderPayload  (InternalRenderController.java:45)
      → Mono.zip(invoice, statement, contributions)
  → renderer.Render(Payload{RenderData: rd_or_nil})
      → template.html: {{if .HasSnapshot}} full layout {{else}} legacy summary {{end}}
  → MinIO PUT invoices/{tenantId}/{invoiceNumber}.pdf
  → Publisher → topic medfund.contributions.invoice-pdf-ready
```

The break point in every failure is a single boolean: `Payload.RenderData == nil`. That boolean is set to nil by `main.go:244` on **any** error from the client, and the resulting bad PDF is uploaded exactly like a good one.

## Architecture doc vs. code

Not covered by a `.claude/*.md` — statement-generation lives entirely inside the Java + Go source. The nearest architecture docs are `.claude/multi-currency.md` (currency handling) and the "audit-logged" rule in `.claude/CLAUDE.md`; neither speaks to the PDF fallback pattern. This is an implementation-only decision that has no design-doc counterpart to check for drift.

## Code References

- `services/go/file-service/internal/invoice/renderer.go:24-25` — comment describing the fallback contract
- `services/go/file-service/internal/invoice/renderer.go:184-187` — nil check that gates the full layout
- `services/go/file-service/internal/invoice/renderer.go:189-262` — full-layout view assembly
- `services/go/file-service/internal/invoice/template.html:69-180` — `{{if .HasSnapshot}}` block (CS-236251 shape)
- `services/go/file-service/internal/invoice/template.html:156` — `Balance carried forward · C/F · amount due` render site
- `services/go/file-service/internal/invoice/template.html:182-201` — `{{else}}` fallback (CS-004418 shape)
- `services/go/file-service/cmd/main.go:240-245` — silent fallback in Kafka consumer (the bug)
- `services/go/file-service/cmd/main.go:126-131` — non-silent 502 in on-demand endpoint
- `services/go/file-service/internal/contributions/client.go:26-31` — 5-second timeout
- `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/InternalRenderController.java:45-55` — the three-way zip that can 500
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java:212` — period-based "Balance carried forward"
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java:493` — snapshot-based "Balance carried forward"
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java:308-312` — `defaultIfEmpty(null)` NPE risk
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java:385` — raw `bind("upper", …)` fails on null `committed_at`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BillingService.java:278-331` — dead-code legacy `generateInvoice` publishing null snapshot payloads
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BillingService.java:1217-1287` — live `persistInvoiceFor` that stamps snapshot and publishes rich payload
- `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.html:127` — Angular mirror of C/F row
- `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.ts:141-152` — opening/closing bookend extraction

## Architecture Insights

- **The `fail-open on statement fetch` pattern is a footgun for this document.** For a financial document, silently downgrading to a "some PDF is better than no PDF" fallback ships an audit-visible artifact that misrepresents the customer's ledger. The consumer should either retry (bounded), dead-letter, or explicitly refuse to publish `InvoicePdfReady` when RenderData is missing — anything but silently upload a document labelled "Total due" that omits the balance history the compliance narrative requires. This is adjacent to Critical Rule #8 in `.claude/CLAUDE.md` ("Every entity mutation must be audit-logged") in spirit: the failure isn't audited, only logged.
- **Payment path also uses this pattern for receipts** (`services/go/file-service/internal/receipt/renderer.go`) — worth reviewing the same fallback semantics there before generalizing a fix.
- **`defaultIfEmpty(null)` on a `Mono<Instant>` is a live NPE.** Trivial to convert to `switchIfEmpty(Mono.just(...))` or use `.map(Optional::ofNullable).defaultIfEmpty(Optional.empty())`. See `StatementService.java:308-312`.
- **Snapshot columns are a hard schema dependency.** Every code path that inserts an `invoices` row must call `InvoiceSnapshotService.stampSnapshot` first — the internal render endpoint relies on `committed_at` being non-null. `generateInvoice` at `BillingService.java:278` is a stale ancestor that violates this and should be removed rather than allowed to lurk.
- **Label duplication across renderers.** "Balance brought forward" / "Balance carried forward" are string literals in four places (period ledger, snapshot ledger, Excel export tests, Angular fixtures). Any label change needs a coordinated edit; there's no shared constant to make that safe. Worth considering a `StatementLabels` shared class in `com.medfund.contributions.dto`.

## Historical Context (from thoughts/shared/)

Not previously researched — the PDF-render pipeline was described in commit messages `c113d5d` (pipeline landing, 2026-06-27), `7c5c59e` (snapshot-backed statement + rich payload, 2026-06-28), and `4cb2824` (double-entry bookends + template rewrite, 2026-07-05), but there is no prior doc under `thoughts/shared/research/` covering statement rendering or the fallback contract.

## Related Research

None directly related. Prior artifacts at `thoughts/shared/research/2026-08-08-advance-payments.md` and `thoughts/shared/research/2026-08-09-ctc-payments.md` cover adjacent finance flows (payment reconciliation, provider payouts) but not the invoice PDF renderer.

## Open Questions

1. **Which specific error surfaced for CS-004418?** The file-service log line (`[file-service] render payload fetch failed invoice=CS-004418 …`) would tell us. If the log rotation window still covers 2026-08-08, grepping for `CS-004418` in file-service stdout will confirm whether it was a 5xx from contributions-service, a 5-second timeout, a decode error, or a network transport failure. Without that log, we're guessing among the three §5 candidates.
2. **Should the fix be "no fallback ever" or "retry then hard-fail"?** Simply removing the `renderData = nil` branch would make the Kafka consumer redeliver the event (Kafka at-least-once semantics), which for a transient blip would eventually succeed. For a *persistent* per-invoice error it would poison-pill the topic. A bounded retry with a dead-letter would balance both, but adds infra.
3. **Does the user want the fallback template kept at all?** If the "some PDF is better than none" pretence is not defensible for a financial document, the `{{else}}` block can be deleted outright — a missing PDF is arguably clearer than a misleading one, since the operator can retry the render and get a correct document instead of an audit trail with two shapes.
4. **Renaming "Balance carried forward" to "Amount Due" — is the marker `C/F · amount due` also going?** Right now the row would read "Amount Due · C/F · amount due" if only the description is renamed. Likely the marker (`template.html:156`) should be simplified too — e.g. drop the "· amount due" suffix, since the description would already say it.
