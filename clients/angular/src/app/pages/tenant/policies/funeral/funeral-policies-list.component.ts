import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  FuneralPolicyRow,
  PoliciesPageResponse,
  PoliciesService,
} from '../../../../core/services/policies.service';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-funeral-policies-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DataTableComponent],
  templateUrl: './funeral-policies-list.component.html',
  styleUrl: './funeral-policies-list.component.scss',
})
export class FuneralPoliciesListComponent implements OnInit {
  rows: FuneralPolicyRow[] = [];
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
    { key: 'policyNumber',        label: 'Policy #',      sortable: true },
    { key: 'principalMemberName', label: 'Principal',     sortable: true },
    { key: 'schemeName',          label: 'Scheme',        sortable: true },
    { key: 'coverAmount',         label: 'Cover amount',  sortable: true, type: 'currency' },
    { key: 'livesCovered',        label: 'Lives covered', sortable: true },
    { key: 'status',              label: 'Status',        sortable: true },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Open',
      icon: 'eye',
      color: 'default',
      handler: (row: FuneralPolicyRow) =>
        this.router.navigate(['/tenant/policies/funeral', row.id]),
    },
  ];

  constructor(private policies: PoliciesService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.policies.listFuneralPoliciesPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: PoliciesPageResponse<FuneralPolicyRow>) => {
        this.rows = resp.content ?? [];
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load funeral policies';
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
