import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  Adjustment,
  BankReconciliation,
  FinanceService,
  Payment,
  PaymentRun,
  ProviderBalance,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';
import { CurrencyTotal, aggregateByCurrency } from '../../../../shared/utils/currency-totals';

@Component({
  selector: 'app-finance-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './finance-dashboard.component.html',
  styleUrl: './finance-dashboard.component.scss',
})
export class FinanceDashboardComponent implements OnInit {
  loading = false;

  weeklyVolume: CurrencyTotal[] = [];
  topCreditors: ProviderBalance[] = [];
  pendingAdjustments: Adjustment[] = [];
  unmatchedReconciliations: BankReconciliation[] = [];
  recentRuns: PaymentRun[] = [];

  constructor(private finance: FinanceService) {}

  ngOnInit(): void {
    this.loading = true;
    forkJoin({
      payments: this.finance.listPayments().pipe(catchError(() => of([] as Payment[]))),
      balances: this.finance.listProviderBalances().pipe(catchError(() => of([] as ProviderBalance[]))),
      pendingAdj: this.finance.getAdjustmentsByStatus('pending').pipe(catchError(() => of([] as Adjustment[]))),
      reconciliations: this.finance.getReconciliationsByStatus('unmatched').pipe(catchError(() => of([] as BankReconciliation[]))),
      runs: this.finance.listRuns().pipe(catchError(() => of([] as PaymentRun[]))),
    }).subscribe({
      next: ({ payments, balances, pendingAdj, reconciliations, runs }) => {
        this.weeklyVolume = this.aggregateWeeklyVolume(payments);
        this.topCreditors = balances
          .filter(b => Number(b.outstandingBalance) > 0)
          .sort((a, b) => Number(b.outstandingBalance) - Number(a.outstandingBalance))
          .slice(0, 10);
        this.pendingAdjustments = pendingAdj.slice(0, 5);
        this.unmatchedReconciliations = reconciliations.slice(0, 5);
        this.recentRuns = runs
          .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
          .slice(0, 5);
        this.loading = false;
      },
      error: () => { this.loading = false; },
    });
  }

  private aggregateWeeklyVolume(payments: Payment[]): CurrencyTotal[] {
    const oneWeekAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const recent = payments.filter(p => p.status === 'paid'
      && p.paidAt
      && Date.parse(p.paidAt) >= oneWeekAgo);
    return aggregateByCurrency(recent, 'amount', 'currencyCode');
  }
}
