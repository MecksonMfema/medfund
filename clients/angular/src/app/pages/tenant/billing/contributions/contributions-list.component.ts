import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ContributionRow,
  ContributionsPageResponse,
  ContributionsService,
} from '../../../../core/services/contributions.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../shared/components/data-table/data-table.component';

const STATUSES = ['pending', 'paid', 'overdue', 'written_off'] as const;

@Component({
  selector: 'app-contributions-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './contributions-list.component.html',
  styleUrl: './contributions-list.component.scss',
})
export class ContributionsListComponent implements OnInit {
  status: typeof STATUSES[number] = 'pending';
  rows: ContributionRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'periodStart';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly statusOptions: SelectOption[] = STATUSES.map(s => ({ value: s, label: s.replace('_', ' ') }));

  readonly columns: TableColumn[] = [
    { key: 'memberName',       label: 'Member',      sortable: true },
    { key: 'groupName',        label: 'Group',       sortable: true },
    { key: 'schemeName',       label: 'Scheme',      sortable: true },
    { key: 'periodStart',      label: 'Period from', sortable: true },
    { key: 'periodEnd',        label: 'Period to',   sortable: true },
    { key: 'amount',           label: 'Amount',      sortable: true, type: 'currency' },
    { key: 'status',           label: 'Status',      sortable: true, type: 'status' },
    { key: 'paidAt',           label: 'Paid at',     sortable: true, type: 'date' },
  ];

  constructor(private contributions: ContributionsService) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.contributions.listContributionsPaged({
      status: this.status,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: ContributionsPageResponse<ContributionRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load contributions';
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
