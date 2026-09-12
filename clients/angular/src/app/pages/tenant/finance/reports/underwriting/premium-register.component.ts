import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PremiumRegisterParams,
  PremiumRegisterReportService,
  PremiumRegisterRow,
} from '../../../../../core/services/premium-register-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { INSURANCE_LINES, insuranceLineLabel } from '../../../../../core/models/insurance-lines';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';
import { defaultReportPeriodStart, defaultReportPeriodEnd } from '../shared/report-date-defaults';

/**
 * Phase 12 §B Premium register — one row per (policy × source × period)
 * in the reporting window. Native amounts (parent-plan invariant #1);
 * the per-currency strip shows native subtotals with best-effort FX
 * conversion. isNewBusiness flag comes from the renewal chain for
 * annual-bind lines and from member_first_contribution for HEALTH.
 */
@Component({
  selector: 'app-premium-register-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, ReportBackButtonComponent],
  templateUrl: './premium-register.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class PremiumRegisterReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<PremiumRegisterRow[]> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = defaultReportPeriodStart();
  periodEnd   = defaultReportPeriodEnd();
  insuranceLine: string = '';
  reportingCurrency = '';

  readonly insuranceLineLabel = insuranceLineLabel;

  constructor(
    private reportSvc: PremiumRegisterReportService,
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

  get lineOptions(): SelectOption[] {
    return [
      { value: '', label: 'All lines' },
      ...INSURANCE_LINES.map(l => ({ value: l.value, label: l.label })),
    ];
  }

  fetch(): void {
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.reportSvc.get(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load premium register';
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
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to export premium register';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void { this.fetch(); }

  onLineChange(value: string): void {
    this.insuranceLine = value || '';
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

  get activeRows(): PremiumRegisterRow[] {
    return this.envelope?.data ?? [];
  }

  private buildParams(): PremiumRegisterParams {
    return {
      periodStart:       this.periodStart,
      periodEnd:         this.periodEnd,
      insuranceLine:     this.insuranceLine || null,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  private filename(): string {
    const parts = ['premium-register', `${this.periodStart}_${this.periodEnd}`];
    if (this.insuranceLine) parts.push(this.insuranceLine.toLowerCase());
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
