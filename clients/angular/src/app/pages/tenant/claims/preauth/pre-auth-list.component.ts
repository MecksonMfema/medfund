import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import {
  PreAuthPageResponse,
  PreAuthService,
  PreAuthorizationRow,
} from '../../../../core/services/pre-auth.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

interface StatusTab {
  /** Lowercase status value, or null for "All". */
  value: string | null;
  label: string;
}

@Component({
  selector: 'app-pre-auth-list',
  standalone: true,
  imports: [CommonModule, DataTableComponent],
  templateUrl: './pre-auth-list.component.html',
  styleUrl: './pre-auth-list.component.scss',
})
export class PreAuthListComponent implements OnInit {
  rows: PreAuthorizationRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly statusTabs: StatusTab[] = [
    { value: null,       label: 'All'      },
    { value: 'pending',  label: 'Pending'  },
    { value: 'approved', label: 'Approved' },
    { value: 'rejected', label: 'Rejected' },
    { value: 'expired',  label: 'Expired'  },
  ];
  activeStatus: string | null = null;

  readonly columns: TableColumn[] = [
    { key: 'authNumber',       label: 'Auth #',    sortable: true },
    { key: 'memberName',       label: 'Member',    sortable: true },
    { key: 'providerName',     label: 'Provider',  sortable: true },
    { key: 'tariffCode',       label: 'Tariff',    sortable: true },
    { key: 'requestedAmount',  label: 'Requested', sortable: true, type: 'currency' },
    { key: 'approvedAmount',   label: 'Approved',  sortable: true, type: 'currency' },
    { key: 'status',           label: 'Status',    sortable: true, type: 'status' },
    { key: 'expiryDate',       label: 'Expires',   sortable: true },
    { key: 'createdAt',        label: 'Submitted', sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: PreAuthorizationRow) => this.router.navigate(['/tenant/claims/preauth', row.id]),
    },
  ];

  constructor(private service: PreAuthService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.service.listPaged({
      status: this.activeStatus ?? undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: PreAuthPageResponse) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load pre-authorizations';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  selectStatus(value: string | null): void {
    if (this.activeStatus === value) return;
    this.activeStatus = value;
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
