import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  EndorsementRegisterParams,
  EndorsementRegisterReportService,
  EndorsementRegisterRow,
} from '../../../../../core/services/endorsement-register-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { INSURANCE_LINES, insuranceLineLabel } from '../../../../../core/models/insurance-lines';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';
import { defaultReportPeriodStart, defaultReportPeriodEnd } from '../shared/report-date-defaults';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { composeWarningsToast, extractErrorMessage } from '../../../../../core/util/http-errors';

/**
 * Phase 12 §C endorsement register: one row per endorsement whose
 * {@code effectiveFrom} falls within the reporting window. Rows stay
 * native-currency (parent-plan invariant #1); the per-currency strip
 * shows |premiumDelta| subtotals with best-effort FX to the reporting
 * currency. Every status flows through the report: the four-eyes
 * lifecycle is visible in the audit trail columns.
 */
@Component({
  selector: 'app-endorsement-register-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent, ReportBackButtonComponent],
  templateUrl: './endorsement-register.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class EndorsementRegisterReportComponent implements OnInit {
  loading = false;
  exporting = false;

  envelope: ReportResponse<EndorsementRegisterRow[]> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = defaultReportPeriodStart();
  periodEnd   = defaultReportPeriodEnd();
  insuranceLine = '';
  status = '';
  reportingCurrency = '';

  readonly insuranceLineLabel = insuranceLineLabel;

  constructor(
    private reportSvc: EndorsementRegisterReportService,
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

  get lineOptions(): SelectOption[] {
    return [
      { value: '', label: 'All lines' },
      ...INSURANCE_LINES.map(l => ({ value: l.value, label: l.label })),
    ];
  }

  get statusOptions(): SelectOption[] {
    return [
      { value: '',          label: 'All statuses' },
      { value: 'DRAFT',     label: 'Draft' },
      { value: 'APPROVED',  label: 'Approved' },
      { value: 'COMMITTED', label: 'Committed' },
      { value: 'COMPUTED',  label: 'Computed' },
      { value: 'VOIDED',    label: 'Voided' },
    ];
  }

  fetch(): void {
    if (!this.periodStart || !this.periodEnd) {
      this.toast.warning('Choose a start and end date.');
      return;
    }
    this.loading = true;
    this.reportSvc.get(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
        this.surfaceWarnings(env);
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load endorsement register'));
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.reportSvc.exportExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, this.filename());
        this.exporting = false;
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to export endorsement register'));
        this.exporting = false;
      },
    });
  }

  private surfaceWarnings(env: ReportResponse<EndorsementRegisterRow[]>): void {
    const warnings = env?.warnings ?? [];
    if (warnings.length === 0) return;
    this.toast.warning(composeWarningsToast(warnings, 'Endorsement register'), 8000);
  }

  onFilterChange(): void { this.fetch(); }

  onLineChange(value: string): void {
    this.insuranceLine = value || '';
    this.fetch();
  }

  onStatusChange(value: string): void {
    this.status = value || '';
    this.fetch();
  }

  perCurrencyList(): { currency: string; totalAmount: number; rowCount: number }[] {
    if (!this.envelope?.perCurrency) return [];
    return Object.entries(this.envelope.perCurrency)
      .map(([currency, v]) => ({ currency, totalAmount: v.totalAmount, rowCount: v.rowCount }))
      .sort((a, b) => a.currency.localeCompare(b.currency));
  }

  fxRateList(): { currency: string; rate: number }[] {
    if (!this.envelope?.fxRates) return [];
    return Object.entries(this.envelope.fxRates)
      .map(([currency, rate]) => ({ currency, rate }))
      .sort((a, b) => a.currency.localeCompare(b.currency));
  }

  get activeRows(): EndorsementRegisterRow[] {
    return this.envelope?.data ?? [];
  }

  private buildParams(): EndorsementRegisterParams {
    return {
      periodStart:       this.periodStart,
      periodEnd:         this.periodEnd,
      insuranceLine:     this.insuranceLine || null,
      status:            this.status        || null,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  private filename(): string {
    const parts = ['endorsement-register', `${this.periodStart}_${this.periodEnd}`];
    if (this.insuranceLine) parts.push(this.insuranceLine.toLowerCase());
    if (this.status)        parts.push(this.status.toLowerCase());
    return `${parts.join('-')}.xlsx`;
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
