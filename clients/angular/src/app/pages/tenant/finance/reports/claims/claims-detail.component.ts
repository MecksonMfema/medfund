import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import {
  ClaimsDetailParams,
  ClaimsDetailResponse,
  ClaimsReportService,
  ReportDimension,
} from '../../../../../core/services/claims-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

type Dimension = ReportDimension;

/**
 * Claims drill-down — one component reused across scheme / provider (§A)
 * and group / member (§B). Renders the monthly funnel-buckets strip + a
 * paginated claim ledger with status / provider / currency filters. Selects
 * dimension from the route's {@code data.dimension} value. Period clock is
 * adjudicated_at.
 */
@Component({
  selector: 'app-claims-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './claims-detail.component.html',
  styleUrl: './claims-report.component.scss',
})
export class ClaimsDetailComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  dimension: Dimension = 'scheme';
  dimensionId = '';

  envelope: ReportResponse<ClaimsDetailResponse> | null = null;

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  status = '';
  providerId = '';
  currency = '';
  reportingCurrency = '';

  pageIndex = 0;
  pageSize = 50;

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All statuses' },
    { value: 'SUBMITTED',       label: 'Submitted' },
    { value: 'VERIFIED',        label: 'Verified' },
    { value: 'IN_ADJUDICATION', label: 'In adjudication' },
    { value: 'ADJUDICATED',     label: 'Adjudicated' },
    { value: 'REJECTED',        label: 'Rejected' },
    { value: 'PENDING_INFO',    label: 'Pending info' },
    { value: 'COMMITTED',       label: 'Committed' },
    { value: 'PAID',            label: 'Paid' },
    { value: 'CANCELLED',       label: 'Cancelled' },
  ];

  readonly ledgerColumns: TableColumn[] = [
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
    private route: ActivatedRoute,
    private claimsReport: ClaimsReportService,
  ) {}

  ngOnInit(): void {
    this.dimension = (this.route.snapshot.data['dimension'] || 'scheme') as Dimension;
    this.dimensionId = this.route.snapshot.paramMap.get('id') || '';

    const qp = this.route.snapshot.queryParamMap;
    if (qp.get('periodStart')) this.periodStart = qp.get('periodStart')!;
    if (qp.get('periodEnd'))   this.periodEnd   = qp.get('periodEnd')!;
    if (qp.get('reportingCurrency')) this.reportingCurrency = qp.get('reportingCurrency')!;

    this.fetch();
  }

  fetch(): void {
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    if (!this.dimensionId) {
      this.errorMessage = 'Missing dimension id.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.claimsReport.getClaimsDetail(this.dimension, this.dimensionId, this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load claims detail';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.dimensionId) return;
    this.exporting = true;
    this.claimsReport.exportClaimsDetailExcel(this.dimension, this.dimensionId, this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `claims-${this.dimension}-${this.dimensionId}-${this.periodStart}-to-${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: () => {
        this.errorMessage = 'Failed to download workbook';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void {
    this.pageIndex = 0;
    this.fetch();
  }

  onPageChange(index: number): void {
    this.pageIndex = index;
    this.fetch();
  }

  private buildParams(): ClaimsDetailParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      status:        this.status || undefined,
      providerId:    this.dimension === 'provider' ? undefined : (this.providerId || undefined),
      currency:      this.currency || undefined,
      reportingCurrency: this.reportingCurrency || undefined,
      page: this.pageIndex,
      size: this.pageSize,
    };
  }

  get ledger() {
    return this.envelope?.data?.claims?.content ?? [];
  }

  get ledgerTotal(): number {
    return this.envelope?.data?.claims?.total ?? 0;
  }

  get ledgerTotalPages(): number {
    return this.envelope?.data?.claims?.totalPages ?? 0;
  }

  get title(): string {
    const name = this.envelope?.data?.dimensionName || '(unknown)';
    const label = this.dimension.charAt(0).toUpperCase() + this.dimension.slice(1);
    return `${label} claims — ${name}`;
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
