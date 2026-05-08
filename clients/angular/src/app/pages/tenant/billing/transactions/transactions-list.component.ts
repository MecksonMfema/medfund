import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

/**
 * Lightweight placeholder list — wires up the route while the backend
 * search endpoint lands. The Add Transaction form is fully functional.
 */
@Component({
  selector: 'app-transactions-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  template: `
    <div class="page">
      <header class="page-header">
        <div>
          <h1>Transactions</h1>
          <p class="page-sub">Recorded payments and adjustments. Use the Add Transaction form to record a new entry.</p>
        </div>
        <a class="btn btn-primary" routerLink="/tenant/billing/transactions/add">
          <app-icon name="plus" [size]="14"></app-icon>
          Record transaction
        </a>
      </header>

      <p class="empty-state">
        Transaction history list is wired to the contribution payment endpoint.
        Browse contribution-level history under
        <a routerLink="/tenant/billing/view">View contributions</a>
        and drill into a row to see its transaction history. A dedicated cross-tenant
        transaction search lands with the statements work.
      </p>
    </div>
  `,
  styles: [`
    .page { padding: 1.5rem; }
    .page-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      margin-bottom: 1.5rem;
    }
    .page-header h1 { margin: 0 0 0.25rem; font-size: 1.5rem; }
    .page-sub { margin: 0; color: var(--text-muted, #6b7280); font-size: 0.9rem; max-width: 65ch; }
    .empty-state { color: var(--text-muted, #6b7280); }
    .empty-state a { color: var(--primary, #2563eb); }
  `],
})
export class TransactionsListComponent {}
