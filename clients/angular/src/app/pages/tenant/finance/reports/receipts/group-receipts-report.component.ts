import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  BillingReportParams,
  FinanceService,
  ReceiptsSummaryRow,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

/**
 * Per-group receipts aggregate — one row per (group, currency). Ungrouped
 * members' receipts appear on the per-member surface, not here.
 */
@Component({
  selector: 'app-group-receipts-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './group-receipts-report.component.html',
  styleUrl: './receipts-report.component.scss',
})
export class GroupReceiptsReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  rows: ReceiptsSummaryRow[] = [];
  envelope: ReportResponse<ReceiptsSummaryRow[]> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  reportingCurrency = '';

  readonly columns: TableColumn[] = [
    { key: 'dimensionName',    label: 'Group',        sortable: false },
    { key: 'currencyCode',     label: 'Currency',     sortable: false },
    { key: 'totalReceived',    label: 'Net received', sortable: false, type: 'currency' },
    { key: 'transactionCount', label: 'Transactions', sortable: false },
  ];

  constructor(
    private finance: FinanceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private router: Router,
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
    this.finance.getGroupReceiptsReport(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.rows = env.data ?? [];
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load receipts report';
        this.rows = [];
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.finance.exportGroupReceiptsExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `receipts-groups-${this.periodStart}-to-${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: () => {
        this.errorMessage = 'Failed to download workbook';
        this.exporting = false;
      },
    });
  }

  onRowClick(row: ReceiptsSummaryRow): void {
    if (!row.dimensionId) return;
    this.router.navigate(['/tenant/finance/reports/receipts-group', row.dimensionId],
      { queryParams: this.periodParams() });
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

  private periodParams(): Record<string, string> {
    const p: Record<string, string> = {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
    };
    if (this.reportingCurrency) p['reportingCurrency'] = this.reportingCurrency;
    return p;
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
