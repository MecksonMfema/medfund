import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  BillingReportParams,
  FinanceService,
  LossRatioReportResponse,
  LossRatioRow,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../../shared/components/skeleton/skeleton.component';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';
import { defaultReportPeriodStart, defaultReportPeriodEnd } from '../shared/report-date-defaults';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { composeWarningsToast, extractErrorMessage } from '../../../../../core/util/http-errors';

/**
 * Loss ratio — billing vs claims at scheme level, per currency. Never
 * cross-currency conversion in the ratio itself (G34). Peer failure on
 * either billing or claims aggregate shows on the warnings banner;
 * the report still renders with whatever data came back.
 */
@Component({
  selector: 'app-loss-ratio-report',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    SelectComponent,
    SkeletonComponent,
    ReportBackButtonComponent,
  ],
  templateUrl: './loss-ratio-report.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class LossRatioReportComponent implements OnInit {
  loading = true;
  exporting = false;

  envelope: ReportResponse<LossRatioReportResponse> | null = null;
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
    this.finance.getLossRatio(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
        this.surfaceWarnings(env);
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load loss ratio'));
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.finance.exportLossRatioExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `loss-ratio-${this.periodStart}-to-${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to download workbook'));
        this.exporting = false;
      },
    });
  }

  private surfaceWarnings(env: ReportResponse<LossRatioReportResponse>): void {
    const warnings = env?.warnings ?? [];
    if (warnings.length === 0) return;
    this.toast.warning(composeWarningsToast(warnings, 'Loss ratio'), 8000);
  }

  onFilterChange(): void { this.fetch(); }

  private buildParams(): BillingReportParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  get activeRows(): LossRatioRow[] {
    return this.envelope?.data?.rows ?? [];
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
