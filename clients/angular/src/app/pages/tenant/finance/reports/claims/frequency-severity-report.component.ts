import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ClaimsReportService,
  FrequencySeverityParams,
  FrequencySeverityRow,
} from '../../../../../core/services/claims-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { INSURANCE_LINES } from '../../../../../core/models/insurance-lines';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

/**
 * Frequency + severity (G48) — per (scheme × insurance line × currency) over
 * the service-date window. Severity = Postgres PERCENTILE_CONT mean / median /
 * P95 (server-side); frequency = claims ÷ exposure member-months, annualised.
 * Exposure is the documented fallback (active members × days ÷ 30.4375) — the
 * envelope carries the caveat in {@link ReportResponse.warnings}.
 */
@Component({
  selector: 'app-frequency-severity-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './frequency-severity-report.component.html',
  styleUrl: './claims-report.component.scss',
})
export class FrequencySeverityReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  rows: FrequencySeverityRow[] = [];
  envelope: ReportResponse<FrequencySeverityRow[]> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  serviceFrom = firstOfPriorMonth();
  serviceTo   = lastOfPriorMonth();
  reportingCurrency = '';
  insuranceLine = '';

  readonly columns: TableColumn[] = [
    { key: 'schemeName',          label: 'Scheme',     sortable: false },
    { key: 'insuranceLine',       label: 'Line',       sortable: false, type: 'label' },
    { key: 'currencyCode',        label: 'Currency',   sortable: false },
    { key: 'exposureMemberMonths',label: 'Exposure (member-mo)', sortable: false },
    { key: 'claimCount',          label: 'Claims',     sortable: false },
    { key: 'frequency',           label: 'Frequency/yr', sortable: false },
    { key: 'severityMean',        label: 'Mean severity',  sortable: false },
    { key: 'severityMedian',      label: 'Median severity', sortable: false },
    { key: 'severityP95',         label: 'P95 severity',    sortable: false },
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

  get lineOptions(): SelectOption[] {
    return [
      { value: '', label: 'All lines' },
      ...INSURANCE_LINES.map(l => ({ value: l.value, label: l.label })),
    ];
  }

  fetch(): void {
    if (!this.serviceFrom || !this.serviceTo) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.claimsReport.getFrequencySeverity(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.rows = env.data ?? [];
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load frequency & severity';
        this.rows = [];
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.serviceFrom || !this.serviceTo) return;
    this.exporting = true;
    this.claimsReport.exportFrequencySeverityExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `claims-frequency-severity-${this.serviceFrom}-to-${this.serviceTo}.xlsx`);
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

  private buildParams(): FrequencySeverityParams {
    return {
      serviceFrom: this.serviceFrom,
      serviceTo:   this.serviceTo,
      reportingCurrency: this.reportingCurrency || undefined,
      insuranceLine:     this.insuranceLine || undefined,
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
