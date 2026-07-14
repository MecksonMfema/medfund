import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  BankReconciliation,
  FinancePageResponse,
  FinanceService,
  ReconciliationStatus,
} from '../../../../core/services/finance.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-reconciliations-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './reconciliations-list.component.html',
  styleUrl: './reconciliations-list.component.scss',
})
export class ReconciliationsListComponent implements OnInit {
  rows: BankReconciliation[] = [];
  loading = false;
  busyId: string | null = null;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  statusFilter: ReconciliationStatus | '' = '';

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'statementDate';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'unmatched', label: 'Unmatched' },
    { value: 'matched', label: 'Matched' },
    { value: 'investigating', label: 'Investigating' },
    { value: 'resolved', label: 'Resolved' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'referenceNumber', label: 'Reference #',      sortable: true },
    { key: 'statementDate',   label: 'Statement date',   sortable: true },
    { key: 'statementAmount', label: 'Statement amount', sortable: true, type: 'currency' },
    { key: 'systemAmount',    label: 'System amount',    sortable: true, type: 'currency' },
    { key: 'difference',      label: 'Difference',       sortable: true, type: 'currency' },
    { key: 'currencyCode',    label: 'Currency',         sortable: true },
    { key: 'status',          label: 'Status',           sortable: true, type: 'status' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Match',
      icon: 'check',
      color: 'success',
      visible: (r: BankReconciliation) => r.status === 'unmatched' || r.status === 'investigating',
      handler: (r: BankReconciliation) => this.transition(r, 'matched', () => this.finance.matchReconciliation(r.id)),
    },
    {
      label: 'Investigate',
      icon: 'search',
      color: 'warning',
      visible: (r: BankReconciliation) => r.status === 'unmatched',
      handler: (r: BankReconciliation) => this.transition(r, 'investigating', () => this.finance.investigateReconciliation(r.id)),
    },
    {
      label: 'Resolve',
      icon: 'check-circle',
      color: 'success',
      visible: (r: BankReconciliation) => r.status === 'investigating',
      handler: (r: BankReconciliation) => this.transition(r, 'resolved', () => this.finance.resolveReconciliation(r.id)),
    },
  ];

  constructor(private finance: FinanceService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.finance.listReconciliationsPaged({
      status: this.statusFilter || undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<BankReconciliation>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load reconciliations';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.page = 1; this.fetchPage(); }
  onPageChange(page: number): void { this.page = page; this.fetchPage(); }
  onSearchChange(term: string): void { this.searchTerm = term; this.page = 1; this.fetchPage(); }
  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }

  private transition(
    row: BankReconciliation,
    target: string,
    fn: () => import('rxjs').Observable<BankReconciliation>,
  ): void {
    if (!confirm(`Mark reconciliation ${row.referenceNumber} as ${target}?`)) return;
    this.busyId = row.id;
    fn().subscribe({
      next: () => {
        this.successMessage = `Reconciliation ${row.referenceNumber} → ${target}.`;
        this.busyId = null;
        this.fetchPage();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || `Failed to mark ${target}`;
        this.busyId = null;
      },
    });
  }
}
