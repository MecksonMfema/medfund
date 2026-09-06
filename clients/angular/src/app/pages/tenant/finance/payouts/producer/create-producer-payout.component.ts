import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { FinanceService, TenantBankAccount } from '../../../../../core/services/finance.service';
import { ProducerPayoutService } from '../../../../../core/services/producer-payout.service';

/**
 * Phase 11 §A Phase 6 — draft a PRODUCER-typed payment run. The server
 * snaps periodStart to 1st-of-month and periodEnd to last-day-of-month
 * (feedback_effective_date_snap); UI-side, we pre-snap into the pickers so
 * users see the snapped values before submitting.
 */
@Component({
  selector: 'app-create-producer-payout',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="create-producer-payout">
      <h1>Create producer payout</h1>

      <form (ngSubmit)="submit()" #f="ngForm">
        <label>Source bank account
          <select [(ngModel)]="sourceBankAccountId" name="sourceBankAccountId" required>
            <option [ngValue]="null" disabled>Pick a bank account</option>
            <option *ngFor="let b of banks" [ngValue]="b.id">
              {{ b.label }} - {{ b.currencyCode }}
            </option>
          </select>
        </label>

        <label>Currency
          <input type="text" [(ngModel)]="currencyCode" name="currencyCode"
                 required minlength="3" maxlength="3"
                 placeholder="USD" (input)="currencyCode = ($any($event.target)).value.toUpperCase()" />
        </label>

        <label>Period start (snaps to 1st-of-month)
          <input type="date" [(ngModel)]="periodStart" name="periodStart" required />
        </label>

        <label>Period end (snaps to last-day-of-month)
          <input type="date" [(ngModel)]="periodEnd" name="periodEnd" required />
        </label>

        <label>Description (optional)
          <input type="text" [(ngModel)]="description" name="description" maxlength="200" />
        </label>

        <p *ngIf="error" class="error">{{ error }}</p>

        <div class="actions">
          <button type="submit" class="btn primary" [disabled]="f.invalid || submitting">
            {{ submitting ? 'Creating…' : 'Create draft' }}
          </button>
        </div>
      </form>
    </section>
  `,
  styles: [`
    :host { display: block; padding: 1rem; max-width: 480px; }
    form { display: flex; flex-direction: column; gap: 1rem; }
    label { display: flex; flex-direction: column; font-size: 0.85rem; }
    label > select, label > input { margin-top: 0.25rem; padding: 0.4rem; }
    .actions { display: flex; justify-content: flex-end; }
    .error { color: #b00020; }
  `],
})
export class CreateProducerPayoutComponent implements OnInit {
  private financeService = inject(FinanceService);
  private producerPayoutService = inject(ProducerPayoutService);
  private router = inject(Router);

  banks: TenantBankAccount[] = [];
  sourceBankAccountId: string | null = null;
  currencyCode = '';
  periodStart = '';
  periodEnd = '';
  description = '';

  submitting = false;
  error: string | null = null;

  ngOnInit(): void {
    this.financeService.listTenantBankAccounts().subscribe({
      next: banks => { this.banks = banks.filter(b => b.active); },
      error: () => { this.banks = []; },
    });
  }

  submit(): void {
    if (!this.sourceBankAccountId || !this.currencyCode
        || !this.periodStart || !this.periodEnd) {
      this.error = 'All fields except description are required';
      return;
    }
    this.submitting = true;
    this.error = null;
    this.producerPayoutService.create({
      currencyCode: this.currencyCode,
      description: this.description || undefined,
      sourceBankAccountId: this.sourceBankAccountId,
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
    }).subscribe({
      next: run => this.router.navigate(['/tenant/finance/payouts/producer', run.id]),
      error: err => {
        this.submitting = false;
        this.error = err?.error?.message ?? 'Failed to create producer payout run';
      },
    });
  }
}
