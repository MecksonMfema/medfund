import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CreateRateCardPayload,
  InsuranceLine,
  ProducerService,
  RateCard,
  UpdateRateCardPayload,
} from '../../../core/services/producer.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../shared/components/data-table/data-table.component';

interface RateCardDraft extends UpdateRateCardPayload {
  id?: string;
}

@Component({
  selector: 'app-rate-cards-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, DataTableComponent],
  templateUrl: './rate-cards-list.component.html',
  styleUrl: './producers-list.component.scss',
})
export class RateCardsListComponent implements OnInit {
  rows: RateCard[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  draft: RateCardDraft = this.empty();

  readonly insuranceLines: InsuranceLine[] = [
    'HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY',
  ];

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  filterActive: '' | 'true' | 'false' = 'true';

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
      handler: (row: RateCard) => this.startEdit(row) },
    { label: 'Deactivate', icon: 'trash', color: 'danger',
      visible: (row: RateCard) => row.active,
      handler: (row: RateCard) => this.deactivate(row) },
  ];

  constructor(private svc: ProducerService) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    const active = this.filterActive === '' ? undefined : this.filterActive === 'true';
    this.svc.listRateCards(this.page - 1, this.pageSize, active).subscribe({
      next: (resp) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load rate cards';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onPageChange(page: number): void { this.page = page; this.fetchPage(); }
  onFilterChange(): void { this.page = 1; this.fetchPage(); }

  startCreate(): void { this.draft = this.empty(); this.showForm = true; this.clearMessages(); }

  startEdit(row: RateCard): void {
    this.draft = {
      id: row.id,
      name: row.name,
      insuranceLine: row.insuranceLine,
      producerTier: row.producerTier,
      baseRatePct: row.baseRatePct,
      clawbackWindowDays: row.clawbackWindowDays,
      effectiveFrom: row.effectiveFrom,
      effectiveTo: row.effectiveTo,
      active: row.active,
    };
    this.showForm = true;
    this.clearMessages();
  }

  cancel(): void { this.showForm = false; this.draft = this.empty(); }

  save(): void {
    if (!this.draft.name?.trim()) { this.errorMessage = 'Name is required'; return; }
    if (this.draft.baseRatePct == null || this.draft.baseRatePct < 0 || this.draft.baseRatePct > 100) {
      this.errorMessage = 'Base rate must be between 0 and 100'; return;
    }
    if (!this.draft.effectiveFrom) { this.errorMessage = 'Effective-from date is required'; return; }
    this.saving = true;
    this.clearMessages();

    const base: CreateRateCardPayload = {
      name: this.draft.name.trim(),
      insuranceLine: this.draft.insuranceLine,
      producerTier: this.draft.producerTier?.trim() || null,
      baseRatePct: this.draft.baseRatePct,
      clawbackWindowDays: this.draft.clawbackWindowDays ?? null,
      effectiveFrom: this.draft.effectiveFrom,
      effectiveTo: this.draft.effectiveTo || null,
    };
    const stream = this.draft.id
      ? this.svc.updateRateCard(this.draft.id, { ...base, active: this.draft.active ?? true })
      : this.svc.createRateCard(base);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Rate card saved';
        this.showForm = false;
        this.fetchPage();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  deactivate(row: RateCard): void {
    if (!confirm(`Deactivate rate card "${row.name}"? Effective-to will snap to the last day of this month.`)) return;
    this.svc.deactivateRateCard(row.id).subscribe({
      next: () => { this.successMessage = 'Rate card deactivated'; this.fetchPage(); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Deactivate failed'; },
    });
  }

  private empty(): RateCardDraft {
    return {
      name: '',
      insuranceLine: 'HEALTH',
      baseRatePct: 0,
      effectiveFrom: new Date().toISOString().slice(0, 10),
      active: true,
    };
  }

  private clearMessages(): void {
    this.errorMessage = null;
    this.successMessage = null;
  }
}
