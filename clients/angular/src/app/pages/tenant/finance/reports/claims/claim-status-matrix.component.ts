import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ClaimStatusMatrixCell,
  ClaimStatusMatrixParams,
  ClaimStatusMatrixResponse,
  ClaimsLedgerRow,
  ClaimsReportService,
  ReportPage,
  StatusMatrixDrillParams,
} from '../../../../../core/services/claims-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { INSURANCE_LINES } from '../../../../../core/models/insurance-lines';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

/** Fixed age buckets per G49 — the CASE order the server emits. */
const AGE_BUCKETS = ['0-3', '4-7', '8-14', '15-30', '>30'];

/**
 * Claim pipeline aging matrix (G49) — one cell per (status × age-bucket ×
 * currency) over the submission window. Ages are relative to the server clock
 * (`asOf`). Clicking a cell drills into the exact ledger that built it — the
 * server repeats the age-bucket CASE in the drill WHERE, so the drill is not a
 * client-side filter.
 */
@Component({
  selector: 'app-claim-status-matrix',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './claim-status-matrix.component.html',
  styleUrl: './claims-report.component.scss',
})
export class ClaimStatusMatrixComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<ClaimStatusMatrixResponse> | null = null;
  tenantCurrencies: TenantCurrencyConfig[] = [];

  submittedFrom = firstOfPriorMonth();
  submittedTo   = lastOfPriorMonth();
  reportingCurrency = '';
  insuranceLine = '';

  // ── Drill state ──────────────────────────────────────────────────────────
  drillOpen = false;
  drillLoading = false;
  drillStatus = '';
  drillBucket = '';
  drillCurrency = '';
  drillPage: ReportPage<ClaimsLedgerRow> = emptyPage();

  pageIndex = 0;
  pageSize = 50;

  readonly drillColumns: TableColumn[] = [
    { key: 'serviceDate',     label: 'Service date',  sortable: false, type: 'date' },
    { key: 'submissionDate',  label: 'Submitted',     sortable: false, type: 'date' },
    { key: 'claimNumber',     label: 'Number',        sortable: false },
    { key: 'memberName',      label: 'Member',        sortable: false },
    { key: 'providerName',    label: 'Provider',      sortable: false },
    { key: 'status',          label: 'Status',        sortable: false, type: 'label' },
    { key: 'rejectionCode',   label: 'Rejection',     sortable: false },
    { key: 'claimedAmount',   label: 'Claimed',       sortable: false, type: 'currency' },
    { key: 'approvedAmount',  label: 'Approved',      sortable: false, type: 'currency' },
    { key: 'paidAmount',      label: 'Paid',          sortable: false, type: 'currency' },
    { key: 'currencyCode',    label: 'Currency',      sortable: false },
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
        this.tenantCurrencies = cs.filter(c => c.isActive);
        const def = this.tenantCurrencies.find(c => c.isDefault);
        if (def && !this.reportingCurrency) this.reportingCurrency = def.currencyCode;
      },
      error: () => { /* non-fatal */ },
    });
  }

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Tenant default' },
      ...this.tenantCurrencies.map(c => ({
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

  get cells(): ClaimStatusMatrixCell[] {
    return this.envelope?.data?.cells ?? [];
  }

  get currencies(): string[] {
    return [...new Set(this.cells.map(c => c.currencyCode))];
  }

  get statuses(): string[] {
    return [...new Set(this.cells.map(c => c.status))];
  }

  get buckets(): string[] {
    return AGE_BUCKETS.filter(b => this.cells.some(c => c.ageBucket === b));
  }

  get asOf(): string | null {
    return this.envelope?.data?.asOf ?? null;
  }

  cell(status: string, bucket: string, currency: string): ClaimStatusMatrixCell | undefined {
    return this.cells.find(c => c.status === status && c.ageBucket === bucket && c.currencyCode === currency);
  }

  fetch(): void {
    if (!this.submittedFrom || !this.submittedTo) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.claimsReport.getClaimStatusMatrix(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load status matrix';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.submittedFrom || !this.submittedTo) return;
    this.exporting = true;
    this.claimsReport.exportClaimStatusMatrixExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `claim-status-matrix-${this.submittedFrom}-to-${this.submittedTo}.xlsx`);
        this.exporting = false;
      },
      error: () => {
        this.errorMessage = 'Failed to download workbook';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void {
    this.drillOpen = false;
    this.fetch();
  }

  drill(status: string, bucket: string, currency: string): void {
    this.drillOpen = true;
    this.drillStatus = status;
    this.drillBucket = bucket;
    this.drillCurrency = currency;
    this.pageIndex = 0;
    this.fetchDrill();
  }

  closeDrill(): void {
    this.drillOpen = false;
  }

  onDrillPageChange(index: number): void {
    this.pageIndex = index;
    this.fetchDrill();
  }

  private fetchDrill(): void {
    this.drillLoading = true;
    this.claimsReport.getClaimStatusMatrixDrill(this.buildDrillParams()).subscribe({
      next: env => {
        this.drillPage = env.data ?? emptyPage();
        this.drillLoading = false;
      },
      error: () => {
        this.drillPage = emptyPage();
        this.drillLoading = false;
      },
    });
  }

  private buildParams(): ClaimStatusMatrixParams {
    return {
      submittedFrom: this.submittedFrom,
      submittedTo:   this.submittedTo,
      reportingCurrency: this.reportingCurrency || undefined,
      insuranceLine:     this.insuranceLine || undefined,
    };
  }

  private buildDrillParams(): StatusMatrixDrillParams {
    return {
      submittedFrom: this.submittedFrom,
      submittedTo:   this.submittedTo,
      reportingCurrency: this.reportingCurrency || undefined,
      status: this.drillStatus || undefined,
      ageBucket: this.drillBucket || undefined,
      page: this.pageIndex,
      size: this.pageSize,
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
function emptyPage(): ReportPage<ClaimsLedgerRow> {
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
