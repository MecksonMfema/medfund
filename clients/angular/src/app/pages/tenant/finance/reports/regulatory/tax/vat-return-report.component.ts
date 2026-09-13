import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  JobStatusResponse,
  ReportJobPollingService,
} from '../../../../../../core/services/report-job-polling.service';
import {
  TaxReportsService,
  TaxReturnReportRequest,
} from '../../../../../../core/services/tax-reports.service';
import {
  CurrencyService,
  TenantCurrencyConfig,
} from '../../../../../../core/services/currency.service';
import { TenantService } from '../../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../../shared/components/icon/icon.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../../../shared/components/select/select.component';
import {
  ActuarialJobProgressComponent,
} from '../../../../../../shared/components/actuarial-job-progress/actuarial-job-progress.component';
import { ReportBackButtonComponent } from '../../shared/report-back-button.component';

/**
 * VAT return report page. Same submit → poll → XLSX shape as the PMB
 * spend page: the whole point is the ZIMRA VAT7 / SARS VAT201 workbook.
 * The currency picker scopes the return: multi-currency tenants file
 * one return per operating currency, each in native units.
 */
@Component({
  selector: 'app-vat-return-report',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    SelectComponent,
    ActuarialJobProgressComponent,
    ReportBackButtonComponent,
  ],
  templateUrl: './vat-return-report.component.html',
  styleUrls: [
    '../../receipts/receipts-report.component.scss',
    './tax-return-report.component.scss',
  ],
})
export class VatReturnReportComponent implements OnInit, OnDestroy {
  readonly reportKey = 'VAT_RETURN';
  readonly pageTitle = 'VAT return';
  readonly pageSubtitle =
    'Country-native VAT return (ZIMRA VAT7 / SARS VAT201). Pick a currency ' +
    'to scope the return: multi-currency tenants file one return per ' +
    'operating currency in native units. The workbook is produced ' +
    'asynchronously; download when the job completes.';

  periodStart = firstOfPreviousMonth();
  periodEnd = lastOfPreviousMonth();
  reportingCurrency = '';
  currencies: TenantCurrencyConfig[] = [];

  submitting = false;
  errorMessage: string | null = null;
  currentJob: JobStatusResponse | null = null;
  startedAt: number | null = null;
  private pollSub: Subscription | null = null;

  constructor(
    private reports: TaxReportsService,
    private polling: ReportJobPollingService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.currencyService.listForTenant(tenantId).subscribe({
      next: (cs) => { this.currencies = cs.filter((c) => c.isActive); },
      error: () => { /* non-fatal */ },
    });
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Country default' },
      ...this.currencies.map((c) => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  onCurrencyChange(v: string): void { this.reportingCurrency = v; }

  submit(): void {
    if (this.submitting) return;
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.errorMessage = null;
    this.submitting = true;
    this.currentJob = null;
    this.startedAt = Date.now();

    const body: TaxReturnReportRequest = {
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      reportingCurrency: this.reportingCurrency || null,
    };

    this.reports.submitVatReturn(body).subscribe({
      next: (resp) => {
        this.submitting = false;
        this.beginPolling(resp.jobId);
      },
      error: (err) => {
        this.submitting = false;
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to submit the VAT return';
      },
    });
  }

  cancelPolling(): void {
    this.pollSub?.unsubscribe();
    this.pollSub = null;
  }

  exportXlsx(): void {
    if (!this.currentJob || this.currentJob.status !== 'completed') return;
    window.open(this.reports.vatReturnXlsxUrl(this.currentJob.jobId), '_blank');
  }

  private beginPolling(jobId: string): void {
    this.pollSub?.unsubscribe();
    this.pollSub = this.polling.poll(jobId).subscribe({
      next: (snap) => { this.currentJob = snap; },
      error: (err) => {
        this.errorMessage = err?.message || 'Polling failed';
        this.pollSub = null;
      },
      complete: () => { this.pollSub = null; },
    });
  }
}

function firstOfPreviousMonth(): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))
    .toISOString().slice(0, 10);
}

function lastOfPreviousMonth(): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0))
    .toISOString().slice(0, 10);
}
