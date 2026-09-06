import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  Producer,
  ProducerService,
} from '../../../core/services/producer.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../shared/components/data-table/data-table.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';

@Component({
  selector: 'app-producers-list',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    IconComponent, DataTableComponent, SelectComponent,
  ],
  templateUrl: './producers-list.component.html',
  styleUrl: './producers-list.component.scss',
})
export class ProducersListComponent implements OnInit {
  rows: Producer[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;

  // Filters. Status ("active") is server-side because listProducers
  // only accepts an `active` boolean; the rest filter client-side over
  // the loaded page (same shape as treaties list).
  filterActive: '' | 'true' | 'false' = 'true';
  selectedCurrency = '';
  selectedJurisdiction = '';
  searchTerm = '';

  private allRows: Producer[] = [];

  readonly columns: TableColumn[] = [
    { key: 'producerCode',      label: 'Code' },
    { key: 'name',              label: 'Name' },
    { key: 'homeCurrency',      label: 'Home ccy' },
    { key: 'jurisdictionCode',  label: 'Jurisdiction' },
    { key: 'contactEmail',      label: 'Contact' },
    { key: 'whtPctOverride',    label: 'WHT %' },
    { key: 'active',            label: 'Active', type: 'boolean' },
  ];

  readonly actions: TableAction[] = [
    { label: 'Edit',       icon: 'edit',  color: 'default',
      handler: (row: Producer) => this.editRow(row) },
    { label: 'Assignments', icon: 'users', color: 'default',
      handler: (row: Producer) => this.viewAssignments(row) },
  ];

  readonly statusOptions: SelectOption[] = [
    { value: '',      label: 'Any status' },
    { value: 'true',  label: 'Active only' },
    { value: 'false', label: 'Inactive only' },
  ];

  get currencyOptions(): SelectOption[] {
    const codes = Array.from(new Set(
      this.allRows.map(r => r.homeCurrency).filter((c): c is string => !!c)
    )).sort();
    return [{ value: '', label: 'Any currency' }, ...codes.map(c => ({ value: c, label: c }))];
  }

  get jurisdictionOptions(): SelectOption[] {
    const codes = Array.from(new Set(
      this.allRows.map(r => r.jurisdictionCode).filter((c): c is string => !!c)
    )).sort();
    return [{ value: '', label: 'Any jurisdiction' }, ...codes.map(c => ({ value: c, label: c }))];
  }

  constructor(private svc: ProducerService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    const active = this.filterActive === '' ? undefined : this.filterActive === 'true';
    this.svc.listProducers(this.page - 1, this.pageSize, active).subscribe({
      next: (resp) => {
        this.allRows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.applyClientFilters();
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load producers';
        this.allRows = [];
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onPageChange(page: number): void { this.page = page; this.fetchPage(); }

  onServerFilterChange(): void { this.page = 1; this.fetchPage(); }

  onClientFilterChange(): void { this.applyClientFilters(); }

  onSearchInput(value: string): void {
    this.searchTerm = value ?? '';
    this.applyClientFilters();
  }

  clearFilters(): void {
    this.filterActive = 'true';
    this.selectedCurrency = '';
    this.selectedJurisdiction = '';
    this.searchTerm = '';
    this.page = 1;
    this.fetchPage();
  }

  private applyClientFilters(): void {
    const q = this.searchTerm.trim().toLowerCase();
    this.rows = this.allRows.filter(r => {
      if (this.selectedCurrency && r.homeCurrency !== this.selectedCurrency) return false;
      if (this.selectedJurisdiction && r.jurisdictionCode !== this.selectedJurisdiction) return false;
      if (q) {
        const hay = `${r.producerCode} ${r.name} ${r.contactEmail ?? ''}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }

  editRow(row: Producer): void {
    this.router.navigate(['/tenant/admin/producers', row.id, 'edit']);
  }

  viewAssignments(row: Producer): void {
    this.router.navigate(['/tenant/admin/producers', row.id, 'assignments']);
  }
}
