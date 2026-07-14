import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  FinancePageResponse,
  FinanceService,
  PaymentRow,
  PaymentStatus,
} from '../../../../core/services/finance.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-payments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './payments-list.component.html',
  styleUrl: './payments-list.component.scss',
})
export class PaymentsListComponent implements OnInit {
  rows: PaymentRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  presetStatus: PaymentStatus | '' = '';
  pageTitle = 'Payments';
  pageDescription = 'Provider payouts. Filter by status. Click any row to inspect.';

  statusFilter: PaymentStatus | '' = '';

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
    { value: 'paid', label: 'Paid' },
    { value: 'cancelled', label: 'Cancelled' },
    { value: 'failed', label: 'Failed' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'paymentNumber', label: 'Payment #',   sortable: true },
    { key: 'providerName',  label: 'Provider',    sortable: true },
    { key: 'amount',        label: 'Amount',      sortable: true, type: 'currency' },
    { key: 'currencyCode',  label: 'Currency',    sortable: true },
    { key: 'paymentType',   label: 'Type',        sortable: true, type: 'label' },
    { key: 'status',        label: 'Status',      sortable: true, type: 'status' },
    { key: 'reference',     label: 'Reference' },
    { key: 'paidAt',        label: 'Paid at',     sortable: true, type: 'date' },
    { key: 'createdAt',     label: 'Created',     sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: PaymentRow) => this.router.navigate(['/tenant/finance/payments', row.id]),
    },
  ];

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const data = this.route.snapshot.data;
    if (data['presetStatus']) {
      this.presetStatus = data['presetStatus'];
      this.statusFilter = this.presetStatus;
    }
    if (data['title']) this.pageTitle = data['title'];
    if (data['description']) this.pageDescription = data['description'];

    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.finance.listPaymentsPaged({
      status: this.statusFilter || undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<PaymentRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load payments';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
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
}
