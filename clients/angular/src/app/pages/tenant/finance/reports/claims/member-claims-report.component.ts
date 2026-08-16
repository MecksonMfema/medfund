import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';
import {
  ClaimsReportService,
  ClaimsSummaryRow,
  MemberClaimsSummaryParams,
  ReportPage,
} from '../../../../../core/services/claims-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { INSURANCE_LINES } from '../../../../../core/models/insurance-lines';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

/**
 * Per-member claims aggregate (§B G45) — paginated + searchable, mirrors the
 * Phase 3 receipts member component. One row per (member, insurance line,
 * currency). Search is a plain ILIKE over member number / first / last name
 * (pg_trgm absent). Clicking a row drills into the member's monthly buckets +
 * claim ledger.
 */
@Component({
  selector: 'app-member-claims-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './member-claims-report.component.html',
  styleUrl: './claims-report.component.scss',
})
export class MemberClaimsReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  page: ReportPage<ClaimsSummaryRow> = emptyPage();
  envelope: ReportResponse<ReportPage<ClaimsSummaryRow>> | null = null;
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
    { key: 'dimensionName',   label: 'Member',    sortable: false },
    { key: 'insuranceLine',   label: 'Line',      sortable: false, type: 'label' },
    { key: 'currencyCode',    label: 'Currency',  sortable: false },
    { key: 'claimCount',      label: 'Claims',    sortable: false },
    { key: 'totalClaimed',    label: 'Claimed',   sortable: false, type: 'currency' },
    { key: 'totalApproved',   label: 'Approved',  sortable: false, type: 'currency' },
    { key: 'totalPaid',       label: 'Paid',      sortable: false, type: 'currency' },
  ];

  get lineOptions(): SelectOption[] {
    return [
      { value: '', label: 'All lines' },
      ...INSURANCE_LINES.map(l => ({ value: l.value, label: l.label })),
    ];
  }

  constructor(
    private claimsReport: ClaimsReportService,
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
    this.claimsReport.getClaimsPerMember(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.page = env.data ?? emptyPage();
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load member claims report';
        this.page = emptyPage();
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.claimsReport.exportClaimsPerMemberExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `claims-members-${this.periodStart}-to-${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: () => {
        this.errorMessage = 'Failed to download workbook';
        this.exporting = false;
      },
    });
  }

  onRowClick(row: ClaimsSummaryRow): void {
    if (!row.dimensionId) return;
    this.router.navigate(['/tenant/finance/reports/claims-member', row.dimensionId],
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

  private buildParams(): MemberClaimsSummaryParams {
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
function emptyPage(): ReportPage<ClaimsSummaryRow> {
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
