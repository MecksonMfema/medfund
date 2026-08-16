import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import {
  BalanceHistoryParams,
  BalanceHistoryResponse,
  BalanceHistoryRow,
  FinanceService,
  PaymentRun,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';

/**
 * Provider balance history — freeze-frame of the provider's balance at
 * each executed payment run (V080, D6). Periodless: rows are native
 * per-currency (G34), newest first. ?asAtRun pins the row set to exactly
 * one run; ?currency narrows to one native currency.
 */
@Component({
  selector: 'app-provider-balance-history',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent],
  templateUrl: './provider-balance-history.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class ProviderBalanceHistoryComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  providerId = '';
  envelope: ReportResponse<BalanceHistoryResponse> | null = null;

  asAtRun = '';
  currency = '';
  runs: PaymentRun[] = [];

  constructor(
    private route: ActivatedRoute,
    private finance: FinanceService,
  ) {}

  ngOnInit(): void {
    this.providerId = this.route.snapshot.paramMap.get('id') ?? '';
    this.loadRuns();
    this.fetch();
  }

  private loadRuns(): void {
    this.finance.listRuns().subscribe({
      next: runs => {
        this.runs = runs.filter(r => r.status === 'executed').sort((a, b) =>
          (b.runNumber ?? '').localeCompare(a.runNumber ?? ''));
      },
      error: () => { /* non-fatal — the run filter is optional */ },
    });
  }

  get runOptions(): SelectOption[] {
    return [
      { value: '', label: 'All runs' },
      ...this.runs.map(r => ({ value: r.id, label: `#${r.runNumber}` })),
    ];
  }

  get currencyOptions(): SelectOption[] {
    const seen = new Set<string>();
    const codes = (this.envelope?.data?.rows ?? [])
      .map(r => r.currencyCode)
      .filter(c => (seen.has(c) ? false : (seen.add(c), true)))
      .sort();
    return [
      { value: '', label: 'All currencies' },
      ...codes.map(c => ({ value: c, label: c })),
    ];
  }

  fetch(): void {
    this.loading = true;
    this.errorMessage = null;
    this.finance.getProviderBalanceHistory(this.providerId, this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load balance history';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    this.exporting = true;
    this.finance.exportProviderBalanceHistoryExcel(this.providerId, this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `provider-balance-history-${this.providerId}.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to download workbook';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void { this.fetch(); }

  private buildParams(): BalanceHistoryParams {
    return {
      asAtRun: this.asAtRun || undefined,
      currency: this.currency || undefined,
    };
  }

  get activeRows(): BalanceHistoryRow[] {
    return this.envelope?.data?.rows ?? [];
  }

  get payeeName(): string {
    return this.envelope?.data?.payeeName ?? '';
  }

  get perCurrencyEntries(): { code: string; totalAmount: number; rowCount: number }[] {
    const perCurrency = this.envelope?.perCurrency ?? {};
    return Object.entries(perCurrency)
      .map(([code, t]) => ({ code, totalAmount: t.totalAmount, rowCount: t.rowCount }))
      .sort((a, b) => a.code.localeCompare(b.code));
  }
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
