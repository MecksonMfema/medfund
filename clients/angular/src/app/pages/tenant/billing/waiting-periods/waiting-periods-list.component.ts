import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  ContributionsService,
  Scheme,
} from '../../../../core/services/contributions.service';
import {
  WaitingPeriodPageResponse,
  WaitingPeriodRow,
  WaitingPeriodService,
} from '../../../../core/services/waiting-period.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-waiting-periods-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './waiting-periods-list.component.html',
  styleUrl: './waiting-periods-list.component.scss',
})
export class WaitingPeriodsListComponent implements OnInit {
  schemes: Scheme[] = [];
  selectedSchemeId = '';
  rows: WaitingPeriodRow[] = [];
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
    { key: 'schemeName',    label: 'Scheme',        sortable: true },
    { key: 'conditionType', label: 'Condition',     sortable: true },
    { key: 'waitingDays',   label: 'Waiting (days)', sortable: true },
    { key: 'minAge',        label: 'Min age',       sortable: true },
    { key: 'maxAge',        label: 'Max age',       sortable: true },
    { key: 'description',   label: 'Description' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      handler: (row: WaitingPeriodRow) => this.router.navigate(['/tenant/billing/waiting-periods', row.id, 'edit']),
    },
    {
      label: 'Delete',
      icon: 'trash',
      color: 'danger',
      handler: (row: WaitingPeriodRow) => this.remove(row),
    },
  ];

  get schemeOptions(): SelectOption[] {
    return [
      { value: '', label: 'All schemes' },
      ...this.schemes.map(s => ({ value: s.id, label: s.name })),
    ];
  }

  constructor(
    private waitingService: WaitingPeriodService,
    private contributions: ContributionsService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    // Load the scheme picker options once; the page itself is server-
    // paginated so we don't need every scheme's waiting-period rules.
    this.contributions.getSchemes().subscribe({
      next: (schemes) => { this.schemes = schemes; },
      error: () => { /* filter dropdown just stays empty */ },
    });
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.waitingService.listPaged({
      schemeId: this.selectedSchemeId || undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: WaitingPeriodPageResponse<WaitingPeriodRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load waiting periods';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onSchemeChange(): void {
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

  remove(r: WaitingPeriodRow): void {
    if (!confirm(`Delete waiting period "${r.conditionType}" (${r.waitingDays} days)?`)) return;
    this.waitingService.delete(r.id).subscribe({
      next: () => this.fetchPage(),
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Delete failed';
      },
    });
  }
}
