import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CashFlowForecastResponse,
  FinanceService,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { LineChartComponent } from '../../../../../shared/components/charts/line-chart/line-chart.component';

function isoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * 13-week rolling cash-flow forecast — inflow from unpaid invoices
 * (due-date bucketed), outflow from draft/approved payment runs
 * (created-at bucketed), per currency, never cross-currency (D8-7).
 * Finance-service downtime shows on the warnings banner and the
 * outflow side renders as all-zero.
 */
@Component({
  selector: 'app-cash-flow-forecast',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, LineChartComponent],
  templateUrl: './cash-flow-forecast.component.html',
  styleUrls: ['../receipts/receipts-report.component.scss', './cash-flow-forecast.component.scss'],
})
export class CashFlowForecastComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<CashFlowForecastResponse> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  asOf = isoDate(new Date());
  rollingWeeks = 13;
  reportingCurrency = '';

  readonly weekOptions: SelectOption[] = [4, 8, 13, 26, 52].map(w => ({
    value: String(w),
    label: `${w} weeks`,
  }));

  constructor(
    private finance: FinanceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.loadCurrencies();
    this.fetch();
  }

  private loadCurrencies(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.currencyService.listForTenant(tenantId).subscribe({
      next: cs => {
        this.currencies = cs.filter(c => c.isActive);
        const def = this.currencies.find(c => c.isDefault);
        if (def && !this.reportingCurrency) this.reportingCurrency = def.currencyCode;
      },
      error: () => { /* non-fatal */ },
    });
  }

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Tenant default' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  fetch(): void {
    if (!this.asOf) {
      this.errorMessage = 'Choose an as-of date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.finance.getCashFlowForecast(this.asOf, this.rollingWeeks, this.reportingCurrency || undefined).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load cash-flow forecast';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.asOf) return;
    this.exporting = true;
    this.finance.exportCashFlowForecastExcel(this.asOf, this.rollingWeeks).subscribe({
      next: blob => {
        downloadBlob(blob, `cash-flow-forecast-${this.asOf}-${this.rollingWeeks}w.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to download workbook';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void { this.fetch(); }

  /** One net line per currency — x = ISO week start, y = net. */
  get chartSeries(): any[] {
    const data = this.envelope?.data;
    if (!data) return [];
    return data.series.map(s => ({
      name: s.currencyCode,
      series: s.buckets.map(b => ({ name: b.weekStart, value: Number(b.net) })),
    }));
  }
}
