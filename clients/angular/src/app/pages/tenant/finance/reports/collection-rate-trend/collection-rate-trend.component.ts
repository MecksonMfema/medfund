import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  BillingReportParams,
  CollectionRateTrendResponse,
  FinanceService,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../../shared/components/skeleton/skeleton.component';
import { LineChartComponent } from '../../../../../shared/components/charts/line-chart/line-chart.component';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';
import { defaultReportPeriodStart, defaultReportPeriodEnd } from '../shared/report-date-defaults';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { composeWarningsToast, extractErrorMessage } from '../../../../../core/util/http-errors';

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * Portfolio-level collection-rate trend — the per-dimension
 * collection-rate stacks summed across all schemes/groups/members into
 * one monthly (billed, received, rate) strip per currency (D8-3).
 * Never cross-currency in the rate itself.
 */
@Component({
  selector: 'app-collection-rate-trend',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    SelectComponent,
    SkeletonComponent,
    LineChartComponent,
    ReportBackButtonComponent,
  ],
  templateUrl: './collection-rate-trend.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class CollectionRateTrendComponent implements OnInit {
  loading = false;
  exporting = false;

  envelope: ReportResponse<CollectionRateTrendResponse> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = defaultReportPeriodStart();
  periodEnd   = defaultReportPeriodEnd();
  reportingCurrency = '';

  constructor(
    private finance: FinanceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private toast: ToastService,
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
    if (!this.periodStart || !this.periodEnd) {
      this.toast.warning('Choose a start and end date.');
      return;
    }
    this.loading = true;
    this.finance.getCollectionRateTrend(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
        this.surfaceWarnings(env);
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load collection-rate trend'));
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.finance.exportCollectionRateTrendExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `collection-rate-trend-${this.periodStart}-to-${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to download workbook'));
        this.exporting = false;
      },
    });
  }

  private surfaceWarnings(env: ReportResponse<CollectionRateTrendResponse>): void {
    const warnings = env?.warnings ?? [];
    if (warnings.length === 0) return;
    this.toast.warning(composeWarningsToast(warnings, 'Collection-rate trend'), 8000);
  }

  onFilterChange(): void { this.fetch(); }

  private buildParams(): BillingReportParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      ...(this.reportingCurrency ? { reportingCurrency: this.reportingCurrency } : {}),
    };
  }

  /** One line per currency — x = month, y = ratePct. Null-rate months are skipped. */
  get chartSeries(): any[] {
    const months = this.envelope?.data?.months ?? [];
    const byCurrency = new Map<string, { name: string; series: { name: string; value: number }[] }>();
    for (const m of months) {
      if (m.ratePct === null || m.ratePct === undefined) continue;
      const entry = byCurrency.get(m.currencyCode) ?? { name: m.currencyCode, series: [] };
      entry.series.push({ name: m.month, value: Number(m.ratePct) });
      byCurrency.set(m.currencyCode, entry);
    }
    return Array.from(byCurrency.values());
  }
}
