import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  InsuranceLine,
  ProducerService,
  RateCard,
} from '../../../core/services/producer.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../shared/components/data-table/data-table.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';

@Component({
  selector: 'app-rate-cards-list',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    IconComponent, DataTableComponent, SelectComponent,
  ],
  templateUrl: './rate-cards-list.component.html',
  styleUrl: './producers-list.component.scss',
})
export class RateCardsListComponent implements OnInit {
  rows: RateCard[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  readonly insuranceLines: InsuranceLine[] = [
    'HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY',
  ];

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;

  // Filters. Status ("active") is server-side because listRateCards
  // only accepts an `active` boolean; the rest filter client-side over
  // the loaded page (same shape as producers + treaties lists).
  filterActive: '' | 'true' | 'false' = 'true';
  selectedLine = '';
  selectedTier = '';
  searchTerm = '';

  private allRows: RateCard[] = [];

  readonly columns: TableColumn[] = [
    { key: 'name',               label: 'Name' },
    { key: 'insuranceLine',      label: 'Line' },
    { key: 'producerTier',       label: 'Tier' },
    { key: 'baseRatePct',        label: 'Base rate %' },
    { key: 'clawbackWindowDays', label: 'Clawback (days)' },
    { key: 'effectiveFrom',      label: 'From' },
    { key: 'effectiveTo',        label: 'To' },
    { key: 'active',             label: 'Active', type: 'boolean' },
  ];

  readonly actions: TableAction[] = [
    { label: 'Edit', icon: 'edit', color: 'default',
      handler: (row: RateCard) => this.editRow(row) },
    { label: 'Deactivate', icon: 'trash', color: 'danger',
      visible: (row: RateCard) => row.active,
      handler: (row: RateCard) => this.deactivate(row) },
  ];

  readonly statusOptions: SelectOption[] = [
    { value: '',      label: 'Any status' },
    { value: 'true',  label: 'Active only' },
    { value: 'false', label: 'Inactive only' },
  ];

  get lineOptions(): SelectOption[] {
    return [
      { value: '', label: 'Any line' },
      ...this.insuranceLines.map(l => ({ value: l, label: l })),
    ];
  }

  get tierOptions(): SelectOption[] {
    const tiers = Array.from(new Set(
      this.allRows.map(r => r.producerTier).filter((t): t is string => !!t)
    )).sort();
    return [{ value: '', label: 'Any tier' }, ...tiers.map(t => ({ value: t, label: t }))];
  }

  constructor(private svc: ProducerService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    const active = this.filterActive === '' ? undefined : this.filterActive === 'true';
    this.svc.listRateCards(this.page - 1, this.pageSize, active).subscribe({
      next: (resp) => {
        this.allRows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.applyClientFilters();
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load rate cards';
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
    this.selectedLine = '';
    this.selectedTier = '';
    this.searchTerm = '';
    this.page = 1;
    this.fetchPage();
  }

  private applyClientFilters(): void {
    const q = this.searchTerm.trim().toLowerCase();
    this.rows = this.allRows.filter(r => {
      if (this.selectedLine && r.insuranceLine !== this.selectedLine) return false;
      if (this.selectedTier && r.producerTier !== this.selectedTier) return false;
      if (q) {
        const hay = `${r.name} ${r.producerTier ?? ''}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }

  editRow(row: RateCard): void {
    this.router.navigate(['/tenant/admin/producers/rate-cards', row.id, 'edit']);
  }

  deactivate(row: RateCard): void {
    if (!confirm(`Deactivate rate card "${row.name}"? Effective-to will snap to the last day of this month.`)) return;
    this.svc.deactivateRateCard(row.id).subscribe({
      next: () => { this.successMessage = 'Rate card deactivated'; this.fetchPage(); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Deactivate failed'; },
    });
  }
}
