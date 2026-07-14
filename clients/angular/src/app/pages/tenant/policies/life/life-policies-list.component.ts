import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  LifePolicyRow,
  PoliciesPageResponse,
  PoliciesService,
} from '../../../../core/services/policies.service';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-life-policies-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DataTableComponent],
  templateUrl: './life-policies-list.component.html',
  styleUrl: './life-policies-list.component.scss',
})
export class LifePoliciesListComponent implements OnInit {
  rows: LifePolicyRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'policyNumber';
  sortDirection: 'asc' | 'desc' = 'asc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'policyNumber',      label: 'Policy #',    sortable: true },
    { key: 'insuredMemberName', label: 'Insured',     sortable: true },
    { key: 'schemeName',        label: 'Scheme',      sortable: true },
    { key: 'sumAssured',        label: 'Sum assured', sortable: true, type: 'currency' },
    { key: 'termMonths',        label: 'Term (mo)',   sortable: true },
    { key: 'status',            label: 'Status',      sortable: true },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Open',
      icon: 'eye',
      color: 'default',
      handler: (row: LifePolicyRow) =>
        this.router.navigate(['/tenant/policies/life', row.id]),
    },
  ];

  constructor(private policies: PoliciesService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.policies.listLifePoliciesPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: PoliciesPageResponse<LifePolicyRow>) => {
        this.rows = resp.content ?? [];
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load life policies';
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
