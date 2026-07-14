import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  AdvancePaymentRow,
  FinancePageResponse,
  FinanceService,
} from '../../../../core/services/finance.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-advance-payments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent],
  templateUrl: './advance-payments-list.component.html',
  styleUrl: './advance-payments-list.component.scss',
})
export class AdvancePaymentsListComponent implements OnInit {
  rows: AdvancePaymentRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'recordedAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'providerName',  label: 'Provider',   sortable: true },
    { key: 'memberName',    label: 'Member',     sortable: true },
    { key: 'amount',        label: 'Amount',     sortable: true, type: 'currency' },
    { key: 'currencyCode',  label: 'Currency',   sortable: true },
    { key: 'paymentMethod', label: 'Method' },
    { key: 'reference',     label: 'Reference' },
    { key: 'recordedAt',    label: 'Recorded',   sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: AdvancePaymentRow) => this.router.navigate(['/tenant/finance/payments/advance', row.id]),
    },
  ];

  constructor(private finance: FinanceService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.finance.listAdvancePaymentsPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<AdvancePaymentRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load advance payments';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
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
