import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  Drug,
  DrugPageResponse,
  DrugsService,
} from '../../../../core/services/drugs.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-drugs-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, DataTableComponent],
  templateUrl: './drugs-list.component.html',
  styleUrl: './drugs-list.component.scss',
})
export class DrugsListComponent implements OnInit {
  rows: Drug[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'drugName';
  sortDirection: 'asc' | 'desc' = 'asc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'drugName',          label: 'Name',       sortable: true },
    { key: 'drugType',          label: 'Type',       sortable: true, type: 'label' },
    { key: 'unitOfMeasurement', label: 'Unit',       sortable: true },
    { key: 'tariffCode',        label: 'Tariff #',   sortable: true },
    { key: 'wholesaleCostUsd',  label: 'USD cost',   sortable: true, type: 'currency' },
    { key: 'paymentPercentage', label: 'Pay %',      sortable: true },
    { key: 'doNotPay',          label: 'Do not pay', sortable: true, type: 'boolean' },
    { key: 'isActive',          label: 'Active',     sortable: true, type: 'boolean' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      handler: (row: Drug) => this.router.navigate(['/tenant/claims/drugs', row.id, 'edit']),
    },
    {
      label: 'Remove',
      icon: 'trash',
      color: 'danger',
      handler: (row: Drug) => this.remove(row),
    },
  ];

  constructor(private drugs: DrugsService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.drugs.listPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: DrugPageResponse) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load drug catalogue';
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

  remove(d: Drug): void {
    if (!confirm(`Remove ${d.drugName} from the formulary?`)) return;
    this.drugs.delete(d.id).subscribe({
      next: () => this.fetchPage(),
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Delete failed';
      },
    });
  }
}
