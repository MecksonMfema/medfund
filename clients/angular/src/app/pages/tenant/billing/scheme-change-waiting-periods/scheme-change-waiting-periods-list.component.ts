import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  SchemeChangeWaitingPeriod,
  WaitingPeriodPageResponse,
  WaitingPeriodService,
} from '../../../../core/services/waiting-period.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-scheme-change-waiting-periods-list',
  standalone: true,
  imports: [CommonModule, RouterLink, DataTableComponent],
  templateUrl: './scheme-change-waiting-periods-list.component.html',
  styleUrl: './scheme-change-waiting-periods-list.component.scss',
})
export class SchemeChangeWaitingPeriodsListComponent implements OnInit {
  rows: SchemeChangeWaitingPeriod[] = [];
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

  readonly columns: TableColumn[] = [
    { key: 'changeType',  label: 'Change',       sortable: true, type: 'label' },
    { key: 'benefitType', label: 'Benefit',      sortable: true },
    { key: 'waitingDays', label: 'Waiting (days)', sortable: true },
    { key: 'description', label: 'Description' },
    { key: 'isActive',    label: 'Active',       sortable: true, type: 'boolean' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      handler: (row: SchemeChangeWaitingPeriod) =>
        this.router.navigate(['/tenant/billing/scheme-change-waiting-periods', row.id, 'edit']),
    },
    {
      label: 'Delete',
      icon: 'trash',
      color: 'danger',
      handler: (row: SchemeChangeWaitingPeriod) => this.remove(row),
    },
  ];

  constructor(private service: WaitingPeriodService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.service.listSchemeChangePaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: WaitingPeriodPageResponse<SchemeChangeWaitingPeriod>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load scheme-change waiting periods';
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

  remove(r: SchemeChangeWaitingPeriod): void {
    if (!confirm(`Delete ${r.changeType.toLowerCase()} rule for ${r.benefitType || 'all benefits'}?`)) return;
    this.service.deleteSchemeChange(r.id).subscribe({
      next: () => this.fetchPage(),
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Delete failed';
      },
    });
  }
}
