import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ClaimsConfigService,
  PageResponse,
  TariffModifier,
} from '../../../../core/services/claims-config.service';
import { DataTableComponent, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-modifiers-list',
  standalone: true,
  imports: [CommonModule, DataTableComponent],
  templateUrl: './modifiers-list.component.html',
  styleUrl: './modifiers-list.component.scss',
})
export class ModifiersListComponent implements OnInit {
  rows: TariffModifier[] = [];
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
    { key: 'code',            label: 'Code',        sortable: true },
    { key: 'name',            label: 'Name',        sortable: true },
    { key: 'description',     label: 'Description' },
    { key: 'adjustmentType',  label: 'Type',        sortable: true, type: 'label' },
    { key: 'adjustmentValue', label: 'Value',       sortable: true },
    { key: 'isActive',        label: 'Active',      sortable: true, type: 'boolean' },
  ];

  constructor(private config: ClaimsConfigService) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.config.listModifiersPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: PageResponse<TariffModifier>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load modifiers';
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
