import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';
import {
  FinancePageResponse,
  FinanceService,
  MemberBillingReportParams,
  ReceiptsSummaryRow,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

/**
 * Per-member receipts aggregate — paginated + searchable. Covers
 * individual-line policies (LIFE / TRAVEL / DISABILITY / VEHICLE /
 * PROPERTY / individual HEALTH) plus direct top-up payments from
 * grouped-line members.
 */
@Component({
  selector: 'app-member-receipts-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './member-receipts-report.component.html',
  styleUrl: './receipts-report.component.scss',
})
export class MemberReceiptsReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  page: FinancePageResponse<ReceiptsSummaryRow> = emptyPage();
  envelope: ReportResponse<FinancePageResponse<ReceiptsSummaryRow>> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  reportingCurrency = '';
  search = '';
  insuranceLine = '';

  pageIndex = 0;
  pageSize = 50;

  private search$ = new Subject<string>();

  readonly columns: TableColumn[] = [
    { key: 'dimensionName',    label: 'Member',       sortable: false },
    { key: 'insuranceLine',    label: 'Line',         sortable: false, type: 'label' },
    { key: 'currencyCode',     label: 'Currency',     sortable: false },
    { key: 'totalReceived',    label: 'Net received', sortable: false, type: 'currency' },
    { key: 'transactionCount', label: 'Transactions', sortable: false },
  ];

  readonly insuranceLineOptions: SelectOption[] = [
    { value: '',           label: 'All lines' },
    { value: 'HEALTH',     label: 'Health' },
    { value: 'LIFE',       label: 'Life' },
    { value: 'FUNERAL',    label: 'Funeral' },
    { value: 'DISABILITY', label: 'Disability' },
    { value: 'TRAVEL',     label: 'Travel' },
    { value: 'GROUP',      label: 'Group' },
    { value: 'VEHICLE',    label: 'Motor' },
    { value: 'PROPERTY',   label: 'Property' },
  ];

  constructor(
    private finance: FinanceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.loadCurrencies();
    this.search$.pipe(debounceTime(300), distinctUntilChanged()).subscribe(() => {
      this.pageIndex = 0;
      this.fetch();
    });
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
    this.finance.getMemberReceiptsReport(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.page = env.data ?? emptyPage();
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load receipts report';
        this.page = emptyPage();
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.finance.exportMemberReceiptsExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `receipts-members-${this.periodStart}-to-${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to download workbook';
        this.exporting = false;
      },
    });
  }

  onRowClick(row: ReceiptsSummaryRow): void {
    if (!row.dimensionId) return;
    this.router.navigate(['/tenant/finance/reports/receipts-member', row.dimensionId],
      { queryParams: this.periodParams() });
  }

  onFilterChange(): void {
    this.pageIndex = 0;
    this.fetch();
  }

  onSearchInput(): void {
    this.search$.next(this.search);
  }

  onPageChange(index: number): void {
    this.pageIndex = index;
    this.fetch();
  }

  private buildParams(): MemberBillingReportParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      reportingCurrency: this.reportingCurrency || undefined,
      search: this.search.trim() || undefined,
      insuranceLine: this.insuranceLine || undefined,
      page: this.pageIndex,
      size: this.pageSize,
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
function emptyPage(): FinancePageResponse<ReceiptsSummaryRow> {
  return { content: [], total: 0, page: 0, size: 50, totalPages: 0 };
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
