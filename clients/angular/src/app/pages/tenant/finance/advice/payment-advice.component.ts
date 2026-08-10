import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  FinanceService,
  PaymentAdviceFilter,
  PaymentAdviceRecord,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-payment-advice',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './payment-advice.component.html',
  styleUrl: './payment-advice.component.scss',
})
export class PaymentAdviceComponent implements OnInit {
  history: PaymentAdviceRecord[] = [];
  loading = false;
  errorMessage: string | null = null;

  /** Month picker value in `YYYY-MM` form (native `<input type="month">`). */
  monthFilter = '';

  constructor(private finance: FinanceService) {}

  ngOnInit(): void {
    this.refreshHistory();
  }

  refreshHistory(): void {
    this.loading = true;
    const filter: PaymentAdviceFilter = {};
    if (this.monthFilter) {
      const bounds = monthToPeriodBounds(this.monthFilter);
      if (bounds) {
        filter.periodStart = bounds.start;
        filter.periodEnd = bounds.end;
      }
    }
    this.finance.listAdviceRecords(filter).subscribe({
      next: (rows) => {
        this.history = rows.slice(0, 200);
        this.loading = false;
      },
      error: () => {
        this.history = [];
        this.loading = false;
        this.errorMessage = 'Failed to load payment advices';
      },
    });
  }

  onMonthChange(): void {
    this.refreshHistory();
  }

  clearMonth(): void {
    this.monthFilter = '';
    this.refreshHistory();
  }
}

/**
 * Translate the browser's `<input type="month">` value (`YYYY-MM`) into
 * the inclusive ISO-8601 bounds the API expects.
 * Uses UTC — matches how period_end_at is stored server-side (TIMESTAMPTZ
 * from finance-service's clock, which is UTC). Local-time framing would
 * make cross-timezone operators disagree on which month a boundary
 * advice belongs to.
 */
function monthToPeriodBounds(month: string): { start: string; end: string } | null {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m) return null;
  const year = Number(m[1]);
  const monthIdx = Number(m[2]) - 1;
  const start = new Date(Date.UTC(year, monthIdx, 1, 0, 0, 0, 0));
  // Last millisecond of the month — Date.UTC with day 0 of next month
  // returns the last day of the current month.
  const end = new Date(Date.UTC(year, monthIdx + 1, 0, 23, 59, 59, 999));
  return { start: start.toISOString(), end: end.toISOString() };
}
