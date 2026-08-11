import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import {
  FinanceService,
  ReceiptsDetailParams,
  ReceiptsDetailResponse,
  ReportResponse,
  TransactionLedgerRow,
} from '../../../../../core/services/finance.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

type Dimension = 'scheme' | 'group' | 'member';

/**
 * Receipts drill-down — one component reused across scheme / group /
 * member. Renders the monthly-buckets strip + a paginated transaction
 * ledger with month / type / currency filters. Selects dimension from
 * the route's {@code data.dimension} value.
 *
 * <p>Handles the synthetic "Unallocated group payments" bucket when
 * the {@code :id} path segment is the literal string {@code unallocated}
 * on the scheme dimension — forwards {@code unallocated=true} to the API.
 */
@Component({
  selector: 'app-receipts-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './receipts-detail.component.html',
  styleUrl: './receipts-report.component.scss',
})
export class ReceiptsDetailComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  dimension: Dimension = 'scheme';
  dimensionId = '';
  unallocated = false;

  envelope: ReportResponse<ReceiptsDetailResponse> | null = null;

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  transactionType = '';
  currency = '';
  reportingCurrency = '';

  pageIndex = 0;
  pageSize = 50;

  readonly txnTypeOptions: SelectOption[] = [
    { value: '', label: 'All types' },
    { value: 'PAYMENT',              label: 'Payment' },
    { value: 'COPAYMENT_RECEIPT',    label: 'Cost-share receipt' },
    { value: 'CTC_OFFSET',           label: 'CTC offset' },
    { value: 'REFUND',               label: 'Refund' },
    { value: 'PAYMENT_REVERSAL',     label: 'Payment reversal' },
    { value: 'CTC_OFFSET_REVERSAL',  label: 'CTC offset reversal' },
  ];

  readonly ledgerColumns: TableColumn[] = [
    { key: 'transactionDate',   label: 'Date',      sortable: false, type: 'date' },
    { key: 'transactionNumber', label: 'Number',    sortable: false },
    { key: 'transactionType',   label: 'Type',      sortable: false, type: 'label' },
    { key: 'paymentMethod',     label: 'Method',    sortable: false },
    { key: 'reference',         label: 'Reference', sortable: false },
    { key: 'amount',            label: 'Amount',    sortable: false, type: 'currency' },
    { key: 'currencyCode',      label: 'Currency',  sortable: false },
  ];

  constructor(
    private route: ActivatedRoute,
    private finance: FinanceService,
  ) {}

  ngOnInit(): void {
    // Route data provides the dimension; :id in the URL provides the target.
    this.dimension = (this.route.snapshot.data['dimension'] || 'scheme') as Dimension;
    const rawId = this.route.snapshot.paramMap.get('id') || '';
    this.unallocated = this.dimension === 'scheme' && rawId === 'unallocated';
    this.dimensionId = this.unallocated ? '' : rawId;

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
    if (!this.dimensionId && !this.unallocated) {
      this.errorMessage = 'Missing dimension id.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    // Route the unallocated case through the scheme drill-down with the flag.
    const idForCall = this.unallocated
      ? '00000000-0000-0000-0000-000000000000' // placeholder — backend ignores when unallocated=true
      : this.dimensionId;
    this.finance.getReceiptsDetail(this.dimension, idForCall, this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load receipts detail';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.dimensionId && !this.unallocated) return;
    this.exporting = true;
    const idForCall = this.unallocated
      ? '00000000-0000-0000-0000-000000000000'
      : this.dimensionId;
    this.finance.exportReceiptsDetailExcel(this.dimension, idForCall, this.buildParams()).subscribe({
      next: blob => {
        const suffix = this.unallocated ? 'unallocated' : `${this.dimension}-${this.dimensionId}`;
        downloadBlob(blob, `receipts-${suffix}-${this.periodStart}-to-${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to download workbook';
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

  private buildParams(): ReceiptsDetailParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      transactionType: this.transactionType || undefined,
      currency:        this.currency || undefined,
      reportingCurrency: this.reportingCurrency || undefined,
      unallocated: this.unallocated || undefined,
      page: this.pageIndex,
      size: this.pageSize,
    };
  }

  get ledger(): TransactionLedgerRow[] {
    return this.envelope?.data?.transactions?.content ?? [];
  }

  get ledgerTotal(): number {
    return this.envelope?.data?.transactions?.total ?? 0;
  }

  get ledgerTotalPages(): number {
    return this.envelope?.data?.transactions?.totalPages ?? 0;
  }

  get title(): string {
    if (this.unallocated) return 'Unallocated group payments — receipts';
    const name = this.envelope?.data?.dimensionName || '(unknown)';
    const label = this.dimension.charAt(0).toUpperCase() + this.dimension.slice(1);
    return `${label} receipts — ${name}`;
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
