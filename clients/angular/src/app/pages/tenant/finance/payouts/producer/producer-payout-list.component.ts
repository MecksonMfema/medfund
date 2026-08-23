import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ProducerPayoutRun, ProducerPayoutService } from '../../../../../core/services/producer-payout.service';

/**
 * Phase 11 §A Phase 6 — PRODUCER-typed payment runs list. Filters by
 * status + currency. Row-click routes to /tenant/finance/payouts/producer/{id}.
 */
@Component({
  selector: 'app-producer-payout-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <section class="producer-payout-list">
      <header>
        <h1>Producer payouts</h1>
        <a routerLink="new" class="btn primary">New producer payout</a>
      </header>

      <div class="filters">
        <label>Status
          <select [(ngModel)]="statusFilter" (change)="reload()">
            <option value="">All</option>
            <option value="draft">Draft</option>
            <option value="approved">Approved</option>
            <option value="executing">Executing</option>
            <option value="executed">Executed</option>
            <option value="cancelled">Cancelled</option>
          </select>
        </label>
        <label>Currency
          <input type="text" [(ngModel)]="currencyFilter" (change)="reload()" placeholder="USD" maxlength="3" />
        </label>
      </div>

      <p *ngIf="loading" class="loading">Loading…</p>
      <p *ngIf="error" class="error">{{ error }}</p>

      <table *ngIf="!loading && rows.length > 0">
        <thead>
          <tr>
            <th>Run #</th>
            <th>Status</th>
            <th>Currency</th>
            <th>Period</th>
            <th>Producer count</th>
            <th>Total</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let r of rows" [routerLink]="[r.id]" class="row-link">
            <td>{{ r.runNumber }}</td>
            <td><span class="status" [attr.data-status]="r.status">{{ r.status }}</span></td>
            <td>{{ r.currencyCode }}</td>
            <td>{{ r.periodStart }} → {{ r.periodEnd }}</td>
            <td>{{ r.paymentCount }}</td>
            <td>{{ r.totalAmount }}</td>
            <td>{{ r.createdAt | date: 'short' }}</td>
          </tr>
        </tbody>
      </table>

      <p *ngIf="!loading && rows.length === 0" class="empty">No producer payout runs match the current filters.</p>
    </section>
  `,
  styles: [`
    :host { display: block; padding: 1rem; }
    header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
    .filters { display: flex; gap: 1rem; margin-bottom: 1rem; }
    .filters label { display: flex; flex-direction: column; font-size: 0.85rem; }
    table { width: 100%; border-collapse: collapse; }
    th, td { border-bottom: 1px solid #eee; padding: 0.5rem; text-align: left; font-size: 0.9rem; }
    .row-link { cursor: pointer; }
    .row-link:hover { background: #fafafa; }
    .status { padding: 2px 8px; border-radius: 4px; background: #eee; font-size: 0.8rem; }
    .status[data-status="executed"] { background: #d4edda; }
    .status[data-status="cancelled"] { background: #f8d7da; }
    .error { color: #b00020; }
    .empty { color: #666; font-style: italic; }
  `],
})
export class ProducerPayoutListComponent implements OnInit {
  private service = inject(ProducerPayoutService);

  rows: ProducerPayoutRun[] = [];
  loading = false;
  error: string | null = null;

  statusFilter = '';
  currencyFilter = '';

  ngOnInit(): void { this.reload(); }

  reload(): void {
    this.loading = true;
    this.error = null;
    this.service.list(this.statusFilter || undefined,
                      this.currencyFilter?.trim().toUpperCase() || undefined)
      .subscribe({
        next: rows => { this.rows = rows; this.loading = false; },
        error: err => {
          this.error = err?.error?.message ?? 'Failed to load producer payouts';
          this.loading = false;
        },
      });
  }
}
