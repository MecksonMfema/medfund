import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  AdjustmentRow,
  AdjustmentStatus,
  AdjustmentType,
  FinancePageResponse,
  FinanceService,
} from '../../../../core/services/finance.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-adjustments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './adjustments-list.component.html',
  styleUrl: './adjustments-list.component.scss',
})
export class AdjustmentsListComponent implements OnInit {
  rows: AdjustmentRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  presetType: AdjustmentType | '' = '';
  pageTitle = 'Adjustments';
  pageDescription = 'Financial adjustments awaiting approval and application.';

  statusFilter: AdjustmentStatus | '' = 'pending';
  typeFilter: AdjustmentType | '' = '';

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'pending', label: 'Pending' },
    { value: 'approved', label: 'Approved' },
    { value: 'applied', label: 'Applied' },
    { value: 'cancelled', label: 'Cancelled' },
  ];

  readonly typeOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'IN_PAYMENT', label: 'In payment' },
    { value: 'PAYOUT', label: 'Payout' },
    { value: 'NON_CASH_IN', label: 'Non-cash in' },
    { value: 'NON_CASH_OUT', label: 'Non-cash out' },
    { value: 'TAX_WITHHELD', label: 'Tax withheld' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'adjustmentNumber', label: 'Adj #',       sortable: true },
    { key: 'memberName',       label: 'Member',      sortable: true },
    { key: 'providerName',     label: 'Provider',    sortable: true },
    { key: 'adjustmentType',   label: 'Type',        sortable: true, type: 'label' },
    { key: 'amount',           label: 'Amount',      sortable: true, type: 'currency' },
    { key: 'status',           label: 'Status',      sortable: true, type: 'status' },
    { key: 'reason',           label: 'Reason' },
    { key: 'createdAt',        label: 'Created',     sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: AdjustmentRow) => this.router.navigate(['/tenant/finance/adjustments', row.id]),
    },
  ];

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const data = this.route.snapshot.data;
    if (data['presetType']) {
      this.presetType = data['presetType'];
      this.typeFilter = this.presetType;
    }
    if (data['title']) this.pageTitle = data['title'];
    if (data['description']) this.pageDescription = data['description'];

    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.finance.listAdjustmentsPaged({
      status: this.statusFilter || undefined,
      adjustmentType: this.typeFilter || undefined,
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
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load adjustments';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.page = 1; this.fetchPage(); }
  onTypeChange(): void   { this.page = 1; this.fetchPage(); }

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
}
