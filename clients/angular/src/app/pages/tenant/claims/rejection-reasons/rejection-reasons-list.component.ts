import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  ClaimsConfigService,
  PageResponse,
  RejectionReason,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-rejection-reasons-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, DataTableComponent],
  templateUrl: './rejection-reasons-list.component.html',
  styleUrl: './rejection-reasons-list.component.scss',
})
export class RejectionReasonsListComponent implements OnInit {
  rows: RejectionReason[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'code';
  sortDirection: 'asc' | 'desc' = 'asc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'code',        label: 'Code',        sortable: true },
    { key: 'description', label: 'Description', sortable: true },
    { key: 'category',    label: 'Category',    sortable: true },
    { key: 'isActive',    label: 'Active',      sortable: true, type: 'boolean' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      handler: (row: RejectionReason) => this.router.navigate(['/tenant/claims/rejection-reasons', row.id, 'edit']),
    },
    {
      label: 'Toggle',
      icon: 'refresh',
      color: 'default',
      labelFor: (row: RejectionReason) => row.isActive ? 'Deactivate' : 'Activate',
      handler: (row: RejectionReason) => this.toggleActive(row),
    },
    {
      label: 'Delete',
      icon: 'trash',
      color: 'danger',
      handler: (row: RejectionReason) => this.remove(row),
    },
  ];

  constructor(private config: ClaimsConfigService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.config.listRejectionReasonsPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: PageResponse<RejectionReason>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load rejection reasons';
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

  toggleActive(r: RejectionReason): void {
    const next = !r.isActive;
    if (!confirm(`${next ? 'Activate' : 'Deactivate'} ${r.code}?`)) return;
    this.config.updateRejectionReason(r.id, {
      code: r.code,
      description: r.description,
      category: r.category,
      isActive: next,
    }).subscribe({
      next: () => this.fetchPage(),
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Update failed';
      },
    });
  }

  remove(r: RejectionReason): void {
    if (!confirm(`Delete ${r.code} permanently? Consider deactivating instead.`)) return;
    this.config.deleteRejectionReason(r.id).subscribe({
      next: () => this.fetchPage(),
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Delete failed';
      },
    });
  }
}
