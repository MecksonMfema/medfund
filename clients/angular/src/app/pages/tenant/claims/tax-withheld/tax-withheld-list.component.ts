import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import {
  AdjustmentRow,
  AdjustmentStatus,
  FinancePageResponse,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { DataTableComponent, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-tax-withheld-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    SelectComponent,
    DataTableComponent,
  ],
  templateUrl: './tax-withheld-list.component.html',
  styleUrl: './tax-withheld-list.component.scss',
})
export class TaxWithheldListComponent implements OnInit {
  rows: AdjustmentRow[] = [];
  loading = false;
  statusFilter: AdjustmentStatus | '' = '';
  variant: 'medical' | 'drug' = 'medical';

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All statuses' },
    { value: 'pending', label: 'Pending' },
    { value: 'approved', label: 'Approved' },
    { value: 'applied', label: 'Applied' },
    { value: 'cancelled', label: 'Cancelled' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'adjustmentNumber', label: 'Adj #',      sortable: true },
    { key: 'memberName',       label: 'Member',     sortable: true },
    { key: 'providerName',     label: 'Provider',   sortable: true },
    { key: 'amount',           label: 'Amount',     sortable: true, type: 'currency' },
    { key: 'currencyCode',     label: 'Currency',   sortable: true },
    { key: 'status',           label: 'Status',     sortable: true, type: 'status' },
    { key: 'reason',           label: 'Reason' },
    { key: 'createdAt',        label: 'Created',    sortable: true, type: 'date' },
  ];

  constructor(
    private finance: FinanceService,
    private toast: ToastService,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.variant = this.route.snapshot.data?.['variant'] === 'drug' ? 'drug' : 'medical';
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.finance.listAdjustmentsPaged({
      adjustmentType: 'TAX_WITHHELD',
      status: this.statusFilter || undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<AdjustmentRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.toast.error(err?.error?.detail || err?.error?.title || 'Failed to load tax-withheld records');
      },
    });
  }

  onStatusChange(): void {
    this.page = 1;
    this.fetchPage();
  }

  onPageChange(page: number): void {
    this.page = page;
    this.fetchPage();
  }

  onSearchChange(term: string): void {
    this.searchTerm = term;
    this.page = 1;
    this.fetchPage();
  }

  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }

  get pageTitle(): string {
    return this.variant === 'drug' ? 'Tax-withheld drug claims' : 'Tax-withheld claims';
  }
}
