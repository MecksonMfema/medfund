import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  ProducerPayoutRun,
  ProducerPayoutRunItem,
  ProducerPayoutService,
} from '../../../../../core/services/producer-payout.service';

/**
 * Phase 11 §A Phase 6 — Producer payout detail. Shows run metadata + item
 * table (one row per producer). Approve/execute actions gated by
 * finance.commission:approve_payout_run on the server side (403 surfaces
 * as an error banner).
 */
@Component({
  selector: 'app-producer-payout-detail',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="producer-payout-detail" *ngIf="run">
      <header>
        <h1>{{ run.runNumber }}</h1>
        <span class="status" [attr.data-status]="run.status">{{ run.status }}</span>
      </header>

      <dl class="meta">
        <dt>Currency</dt><dd>{{ run.currencyCode }}</dd>
        <dt>Period</dt><dd>{{ run.periodStart }} → {{ run.periodEnd }}</dd>
        <dt>Source bank</dt><dd>{{ run.sourceBankAccountLabel ?? run.sourceBankAccountId }}</dd>
        <dt>Producer count</dt><dd>{{ run.paymentCount }}</dd>
        <dt>Total</dt><dd>{{ run.totalAmount }}</dd>
        <dt>Description</dt><dd>{{ run.description || '-' }}</dd>
      </dl>

      <div class="actions" *ngIf="run.status === 'draft' || run.status === 'approved'">
        <button *ngIf="run.status === 'draft'"    class="btn"        (click)="approve()">Approve</button>
        <button *ngIf="run.status === 'approved' || run.status === 'draft'"
                class="btn primary" (click)="execute()">Execute</button>
      </div>

      <h2>Items</h2>
      <table *ngIf="items.length > 0">
        <thead>
          <tr>
            <th>Producer id</th>
            <th>Gross amount</th>
            <th>WHT %</th>
            <th>Currency</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let it of items">
            <td>{{ it.producerId }}</td>
            <td>{{ it.amount }}</td>
            <td>{{ it.withholdingTaxPct ?? '-' }}</td>
            <td>{{ it.currencyCode }}</td>
            <td>{{ it.status }}</td>
          </tr>
        </tbody>
      </table>
      <p *ngIf="items.length === 0" class="empty">No items on this run.</p>

      <p *ngIf="error" class="error">{{ error }}</p>
    </section>

    <p *ngIf="!run && loading" class="loading">Loading…</p>
  `,
  styles: [`
    :host { display: block; padding: 1rem; }
    header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
    .status { padding: 4px 10px; border-radius: 4px; background: #eee; font-size: 0.85rem; }
    .status[data-status="executed"] { background: #d4edda; }
    .status[data-status="cancelled"] { background: #f8d7da; }
    dl.meta { display: grid; grid-template-columns: max-content 1fr; gap: 0.25rem 1rem; margin-bottom: 1rem; }
    dl.meta dt { font-weight: 600; }
    .actions { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
    table { width: 100%; border-collapse: collapse; }
    th, td { border-bottom: 1px solid #eee; padding: 0.5rem; text-align: left; font-size: 0.9rem; }
    .error { color: #b00020; }
    .empty { color: #666; font-style: italic; }
  `],
})
export class ProducerPayoutDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private service = inject(ProducerPayoutService);

  run: ProducerPayoutRun | null = null;
  items: ProducerPayoutRunItem[] = [];
  loading = false;
  error: string | null = null;

  ngOnInit(): void { this.reload(); }

  private reload(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) return;
    this.loading = true;
    forkJoin({
      run: this.service.get(id),
      items: this.service.items(id),
    }).subscribe({
      next: ({ run, items }) => { this.run = run; this.items = items; this.loading = false; },
      error: err => {
        this.error = err?.error?.message ?? 'Failed to load producer payout run';
        this.loading = false;
      },
    });
  }

  approve(): void {
    if (!this.run) return;
    this.service.approve(this.run.id).subscribe({
      next: run => { this.run = run; },
      error: err => { this.error = err?.error?.message ?? 'Approve failed'; },
    });
  }

  execute(): void {
    if (!this.run) return;
    this.service.execute(this.run.id).subscribe({
      next: run => { this.run = run; this.reload(); },
      error: err => { this.error = err?.error?.message ?? 'Execute failed'; },
    });
  }
}
