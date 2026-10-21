import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PolicyMovementParams,
  PolicyMovementReportService,
  PolicyMovementResult,
} from '../../../../../../core/services/policy-movement-report.service';
import { ReportResponse } from '../../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../../core/services/currency.service';
import { TenantService } from '../../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../../shared/components/select/select.component';
import { ReportBackButtonComponent } from '../../shared/report-back-button.component';
import { defaultReportPeriodStart, defaultReportPeriodEnd } from '../../shared/report-date-defaults';
import { ToastService } from '../../../../../../shared/components/toast/toast.service';
import { composeWarningsToast, extractErrorMessage } from '../../../../../../core/util/http-errors';

/**
 * Phase 13 §C Phase 10: POLICY_MOVEMENT report page. Native rows per
 * (policy_source, currency) with the six-count roll and the
 * |written_premium| add/remove columns. XLSX export lives on the backend.
 */
@Component({
  selector: 'app-policy-movement-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, ReportBackButtonComponent],
  templateUrl: './movement.component.html',
  styleUrl: '../../receipts/receipts-report.component.scss',
})
export class PolicyMovementReportComponent implements OnInit {
  loading = false;
  exporting = false;

  envelope: ReportResponse<PolicyMovementResult> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = defaultReportPeriodStart();
  periodEnd   = defaultReportPeriodEnd();
  reportingCurrency = '';

  constructor(
    private reportSvc: PolicyMovementReportService,
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
    this.reportSvc.get(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
        this.surfaceWarnings(env);
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load policy movement report'));
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
        downloadBlob(blob, `policy-movement-${this.periodStart}_${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to export policy movement report'));
        this.exporting = false;
      },
    });
  }

  private surfaceWarnings(env: ReportResponse<PolicyMovementResult>): void {
    const warnings = env?.warnings ?? [];
    if (warnings.length === 0) return;
    this.toast.warning(composeWarningsToast(warnings, 'Policy movement'), 8000);
  }

  onFilterChange(): void { this.fetch(); }

  get rows() { return this.envelope?.data?.rows ?? []; }

  private buildParams(): PolicyMovementParams {
    return {
      periodStart:       this.periodStart,
      periodEnd:         this.periodEnd,
      reportingCurrency: this.reportingCurrency || undefined,
    };
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
