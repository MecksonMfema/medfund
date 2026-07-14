import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TariffCategoriesService,
  TariffCategory,
  TariffCategoryPageResponse,
  UpsertTariffCategoryPayload,
} from '../../../core/services/tariff-categories.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../shared/components/data-table/data-table.component';

interface CategoryDraft extends UpsertTariffCategoryPayload {
  id?: string;
}

@Component({
  selector: 'app-tariff-categories-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, DataTableComponent],
  templateUrl: './tariff-categories-list.component.html',
  styleUrl: './tariff-categories-list.component.scss',
})
export class TariffCategoriesListComponent implements OnInit {
  rows: TariffCategory[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  draft: CategoryDraft = this.empty();

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'sortOrder';
  sortDirection: 'asc' | 'desc' = 'asc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'code',        label: 'Code',        sortable: true },
    { key: 'label',       label: 'Label',       sortable: true },
    { key: 'description', label: 'Description' },
    { key: 'sortOrder',   label: 'Sort',        sortable: true },
    { key: 'isCapOnly',   label: 'Cap-only',    sortable: true, type: 'boolean' },
    { key: 'isActive',    label: 'Active',      sortable: true, type: 'boolean' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      handler: (row: TariffCategory) => this.startEdit(row),
    },
    {
      label: 'Deactivate',
      icon: 'trash',
      color: 'danger',
      visible: (row: TariffCategory) => row.isActive,
      handler: (row: TariffCategory) => this.deactivate(row),
    },
  ];

  constructor(private svc: TariffCategoriesService) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.svc.listPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: TariffCategoryPageResponse) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load categories';
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

  startCreate(): void {
    this.draft = this.empty();
    this.showForm = true;
  }

  startEdit(row: TariffCategory): void {
    this.draft = {
      id: row.id,
      code: row.code,
      label: row.label,
      description: row.description,
      isCapOnly: row.isCapOnly,
      isActive: row.isActive,
      sortOrder: row.sortOrder,
    };
    this.showForm = true;
  }

  cancel(): void {
    this.showForm = false;
    this.draft = this.empty();
  }

  save(): void {
    if (!this.draft.code.trim() || !this.draft.label.trim()) {
      this.errorMessage = 'Code and label are required';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    this.successMessage = null;
    const payload: UpsertTariffCategoryPayload = {
      code: this.draft.code.trim(),
      label: this.draft.label.trim(),
      description: this.draft.description?.trim() || undefined,
      isCapOnly: this.draft.isCapOnly,
      isActive: this.draft.isActive,
      sortOrder: this.draft.sortOrder,
    };
    const stream = this.draft.id
      ? this.svc.update(this.draft.id, payload)
      : this.svc.create(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Category saved';
        this.showForm = false;
        this.fetchPage();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  deactivate(row: TariffCategory): void {
    if (!confirm(`Deactivate "${row.label}"? Tariffs and benefits still referencing it stay linked.`)) return;
    this.svc.deactivate(row.id).subscribe({
      next: () => {
        this.successMessage = 'Category deactivated';
        this.fetchPage();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Deactivate failed';
      },
    });
  }

  private empty(): CategoryDraft {
    return {
      code: '',
      label: '',
      description: '',
      isCapOnly: false,
      isActive: true,
      sortOrder: 0,
    };
  }
}
