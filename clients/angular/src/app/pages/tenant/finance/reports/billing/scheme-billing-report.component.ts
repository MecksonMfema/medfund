import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  BillingReportParams,
  FinanceService,
  ReportResponse,
  SchemeBillingSummaryRow,
} from '../../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

/**
 * Per-scheme billing aggregate — one row per (scheme, currency). Rows stay
 * native-currency (G25). The envelope's {@code perCurrency} + {@code fxRates}
 * drive the KPI strip (ledger totals per currency) and the warning banner
 * when an FX rate is missing.
 */
@Component({
  selector: 'app-scheme-billing-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './scheme-billing-report.component.html',
  styleUrl: './billing-report.component.scss',
})
export class SchemeBillingReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  rows: SchemeBillingSummaryRow[] = [];
  envelope: ReportResponse<SchemeBillingSummaryRow[]> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  // Default the window to the last full month — the most common "how did we
  // do last month" question. Tenant admin can widen to a quarter or year.
  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  reportingCurrency = '';

  readonly columns: TableColumn[] = [
    { key: 'schemeName',       label: 'Scheme',      sortable: false },
    { key: 'insuranceLine',    label: 'Line',        sortable: false, type: 'label' },
    { key: 'currencyCode',     label: 'Currency',    sortable: false },
    { key: 'principalCount',   label: 'Principals',  sortable: false },
    { key: 'dependantCount',   label: 'Dependants',  sortable: false },
    { key: 'livesCovered',     label: 'Lives',       sortable: false },
    { key: 'ageBand0_18',      label: '0-18',        sortable: false, type: 'currency' },
    { key: 'ageBand19_35',     label: '19-35',       sortable: false, type: 'currency' },
    { key: 'ageBand36_55',     label: '36-55',       sortable: false, type: 'currency' },
    { key: 'ageBand56Plus',    label: '56+',         sortable: false, type: 'currency' },
    { key: 'totalBilled',      label: 'Total billed',sortable: false, type: 'currency' },
    { key: 'totalPaid',        label: 'Total paid',  sortable: false, type: 'currency' },
  ];

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
      error: () => { /* non-fatal — envelope still returns the tenant default */ },
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
    this.finance.getSchemeBillingReport(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.rows = env.data ?? [];
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load billing report';
        this.rows = [];
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.finance.exportSchemeBillingExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `billing-schemes-${this.periodStart}-to-${this.periodEnd}.xlsx`);
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

  private buildParams(): BillingReportParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  get perCurrencyEntries(): { currency: string; totalAmount: string; rowCount: number }[] {
    if (!this.envelope) return [];
    return Object.entries(this.envelope.perCurrency ?? {}).map(([currency, total]) => ({
      currency,
      totalAmount: String(total.totalAmount),
      rowCount: total.rowCount,
    }));
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
