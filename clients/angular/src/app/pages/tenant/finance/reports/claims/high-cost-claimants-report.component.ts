import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ClaimsReportService,
  ClaimsSummaryParams,
  HighCostClaimantRow,
} from '../../../../../core/services/claims-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

/**
 * High-cost claimants (G46). Members whose cumulative paid claims across
 * the window clear the tenant-configured threshold. The threshold lives in
 * tenancy-service (V132); a missing config row renders an empty report with
 * a warning banner — surface {@link ReportResponse.warnings} prominently.
 * Period clock is adjudicated_at; rows stay native-currency with the
 * converted {@code cumulativePaidReporting} alongside.
 */
@Component({
  selector: 'app-high-cost-claimants-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './high-cost-claimants-report.component.html',
  styleUrl: './claims-report.component.scss',
})
export class HighCostClaimantsReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  rows: HighCostClaimantRow[] = [];
  envelope: ReportResponse<HighCostClaimantRow[]> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  reportingCurrency = '';

  readonly columns: TableColumn[] = [
    { key: 'memberName',          label: 'Member',            sortable: false },
    { key: 'memberNumber',        label: 'Member no.',        sortable: false },
    { key: 'currencyCode',        label: 'Currency',          sortable: false },
    { key: 'cumulativePaid',      label: 'Cumulative paid',   sortable: false, type: 'currency' },
    { key: 'contributingClaims',  label: 'Claims',            sortable: false },
    { key: 'cumulativePaidReporting', label: 'Paid (reporting)', sortable: false },
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
    this.claimsReport.getHighCostClaimants(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.rows = env.data ?? [];
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load high-cost claimants';
        this.rows = [];
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.claimsReport.exportHighCostClaimantsExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `high-cost-claimants-${this.periodStart}-to-${this.periodEnd}.xlsx`);
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

  private buildParams(): ClaimsSummaryParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      reportingCurrency: this.reportingCurrency || undefined,
    };
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
