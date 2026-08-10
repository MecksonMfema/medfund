import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  FinancePageResponse,
  FinanceService,
  PayeeType,
  PaymentRun,
  PaymentRunStatus,
} from '../../../../core/services/finance.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-payment-runs-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './payment-runs-list.component.html',
  styleUrl: './payment-runs-list.component.scss',
})
export class PaymentRunsListComponent implements OnInit {
  rows: PaymentRun[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Route-driven preset (e.g. current-payment-run preset = draft).
  presetStatus: PaymentRunStatus | '' = '';
  pageTitle = 'Payment runs';
  pageDescription = 'Batched payouts to providers. Each run aggregates eligible adjudicated claims into a draft, then commits on execute.';

  statusFilter: PaymentRunStatus | '' = '';
  payeeTypeFilter: PayeeType | '' = '';

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
    { value: 'draft', label: 'Draft' },
    { value: 'approved', label: 'Approved' },
    { value: 'executing', label: 'Executing' },
    { value: 'executed', label: 'Executed' },
    { value: 'cancelled', label: 'Cancelled' },
  ];

  readonly payeeTypeOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'PROVIDER', label: 'Providers' },
    { value: 'MEMBER',   label: 'Members' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'runNumber',              label: 'Run #',        sortable: true },
    { key: 'status',                 label: 'Status',       sortable: true, type: 'status' },
    { key: 'payeeType',              label: 'Payee type',   sortable: true, type: 'label' },
    { key: 'totalAmount',            label: 'Total',        sortable: true, type: 'currency' },
    { key: 'currencyCode',           label: 'Currency',     sortable: true },
    { key: 'sourceBankAccountLabel', label: 'From account' },
    { key: 'paymentCount',           label: 'Payments',     sortable: true },
    { key: 'description',            label: 'Description' },
    { key: 'executedAt',             label: 'Executed',     sortable: true, type: 'date' },
    { key: 'createdAt',              label: 'Created',      sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: PaymentRun) => this.router.navigate(['/tenant/finance/runs', row.id]),
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
    this.finance.listRunsPaged({
      status: this.statusFilter || undefined,
      payeeType: this.payeeTypeFilter || undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<PaymentRun>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load payment runs';
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

  onPayeeTypeChange(): void {
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
