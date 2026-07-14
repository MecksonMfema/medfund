import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  FinancePageResponse,
  FinanceService,
  ProviderBalanceRow,
} from '../../../../core/services/finance.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-creditors-list',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent],
  templateUrl: './creditors-list.component.html',
  styleUrl: './creditors-list.component.scss',
})
export class CreditorsListComponent implements OnInit {
  rows: ProviderBalanceRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'outstandingBalance';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'providerName',       label: 'Provider',    sortable: true },
    { key: 'currencyCode',       label: 'Currency',    sortable: true },
    { key: 'totalClaimed',       label: 'Claimed',     sortable: true, type: 'currency' },
    { key: 'totalApproved',      label: 'Approved',    sortable: true, type: 'currency' },
    { key: 'totalPaid',          label: 'Paid',        sortable: true, type: 'currency' },
    { key: 'outstandingBalance', label: 'Outstanding', sortable: true, type: 'currency' },
    { key: 'lastUpdatedAt',      label: 'Updated',     sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: ProviderBalanceRow) =>
        this.router.navigate(['/tenant/finance/creditors/provider', row.providerId]),
    },
  ];

  constructor(private finance: FinanceService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.finance.listProviderBalancesPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<ProviderBalanceRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load provider balances';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onPageChange(page: number): void { this.page = page; this.fetchPage(); }
  onSearchChange(term: string): void { this.searchTerm = term; this.page = 1; this.fetchPage(); }
  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }
}
