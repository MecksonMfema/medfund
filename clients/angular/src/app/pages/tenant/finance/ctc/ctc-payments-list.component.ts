import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  CtcPayment,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-ctc-payments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './ctc-payments-list.component.html',
  styleUrl: './ctc-payments-list.component.scss',
})
export class CtcPaymentsListComponent implements OnInit {
  rows: CtcPayment[] = [];
  loading = false;
  busyId: string | null = null;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  committedFilter: '' | 'true' | 'false' = '';

  constructor(private finance: FinanceService) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    const committed = this.committedFilter ? this.committedFilter === 'true' : undefined;
    this.finance.listCtcPayments(committed).subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load CTC payments';
        this.loading = false;
      },
    });
  }

  commit(row: CtcPayment, event: Event): void {
    event.stopPropagation();
    if (!confirm('Commit this CTC payment? This is final.')) return;
    this.busyId = row.id;
    this.finance.commitCtcPayment(row.id).subscribe({
      next: (updated) => {
        const idx = this.rows.findIndex(r => r.id === updated.id);
        if (idx >= 0) this.rows[idx] = updated;
        this.successMessage = 'CTC payment committed.';
        this.busyId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to commit';
        this.busyId = null;
      },
    });
  }
}
