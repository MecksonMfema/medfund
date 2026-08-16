import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ClaimsReportService,
  PreAuthActivityParams,
  PreAuthActivityResponse,
} from '../../../../../core/services/claims-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

/**
 * Pre-auth activity (G43). Reads pre_authorizations on the requested-date
 * clock: one row per (status, currency) with count, requested/approved
 * totals and avg decision time, plus the claims-side R04/R05 rejection
 * signal as a proxy-utilisation companion metric. Classical utilisation
 * is un-computable from stored data (F55), so this report surfaces
 * activity directly.
 */
@Component({
  selector: 'app-pre-auth-activity-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './pre-auth-activity-report.component.html',
  styleUrl: './claims-report.component.scss',
})
export class PreAuthActivityReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<PreAuthActivityResponse> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  reportingCurrency = '';
  status = '';
  providerId = '';

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All statuses' },
    { value: 'PENDING',  label: 'Pending' },
    { value: 'APPROVED', label: 'Approved' },
    { value: 'REJECTED', label: 'Rejected' },
    { value: 'EXPIRED',  label: 'Expired' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'status',           label: 'Status',         sortable: false },
    { key: 'currencyCode',     label: 'Currency',       sortable: false },
    { key: 'count',            label: 'Count',          sortable: false },
    { key: 'totalRequested',   label: 'Requested',      sortable: false, type: 'currency' },
    { key: 'totalApproved',    label: 'Approved',       sortable: false, type: 'currency' },
    { key: 'avgDecisionDays',  label: 'Avg decision',   sortable: false },
    { key: 'approvalRatePct',  label: 'Approval rate',  sortable: false },
    { key: 'expiryRatePct',    label: 'Expiry rate',    sortable: false },
  ];

  constructor(
    private claimsReport: ClaimsReportService,
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
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.claimsReport.getPreAuthActivity(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load pre-auth activity';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.claimsReport.exportPreAuthActivityExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `pre-auth-activity-${this.periodStart}-to-${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: () => {
        this.errorMessage = 'Failed to download workbook';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void {
    this.fetch();
  }

  private buildParams(): PreAuthActivityParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      reportingCurrency: this.reportingCurrency || undefined,
      status:        this.status || undefined,
      providerId:    this.providerId || undefined,
    };
  }

  get rows() {
    return this.envelope?.data?.byStatus ?? [];
  }

  get signal() {
    return this.envelope?.data?.r04r05Signal ?? null;
  }
}

function firstOfPriorMonth(): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  return d.toISOString().slice(0, 10);
}
function lastOfPriorMonth(): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0));
  return d.toISOString().slice(0, 10);
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
