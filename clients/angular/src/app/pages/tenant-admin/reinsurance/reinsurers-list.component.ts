import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CreateReinsurerPayload,
  Reinsurer,
  ReinsuranceService,
  UpdateReinsurerPayload,
} from '../../../core/services/reinsurance.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../shared/components/data-table/data-table.component';

interface ReinsurerDraft extends UpdateReinsurerPayload {
  id?: string;
}

@Component({
  selector: 'app-reinsurers-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, DataTableComponent],
  templateUrl: './reinsurers-list.component.html',
  styleUrl: './reinsurers-list.component.scss',
})
export class ReinsurersListComponent implements OnInit {
  rows: Reinsurer[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  draft: ReinsurerDraft = this.empty();

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  filterActive: '' | 'true' | 'false' = '';

  readonly columns: TableColumn[] = [
    { key: 'name',             label: 'Name',             sortable: false },
    { key: 'jurisdictionCode', label: 'Jurisdiction' },
    { key: 'homeCurrency',     label: 'Home ccy' },
    { key: 'creditRating',     label: 'Credit rating' },
    { key: 'contactEmail',     label: 'Contact' },
    { key: 'active',           label: 'Active',           type: 'boolean' },
  ];

  readonly actions: TableAction[] = [
    { label: 'Edit', icon: 'edit', color: 'default', handler: (row: Reinsurer) => this.startEdit(row) },
    {
      label: 'Deactivate',
      icon: 'trash',
      color: 'danger',
      visible: (row: Reinsurer) => row.active,
      handler: (row: Reinsurer) => this.deactivate(row),
    },
  ];

  constructor(private svc: ReinsuranceService) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    const active = this.filterActive === '' ? undefined : this.filterActive === 'true';
    this.svc.listReinsurers(this.page - 1, this.pageSize, active).subscribe({
      next: (resp) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load reinsurers';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onPageChange(page: number): void { this.page = page; this.fetchPage(); }
  onFilterChange(): void { this.page = 1; this.fetchPage(); }

  startCreate(): void { this.draft = this.empty(); this.showForm = true; this.clearMessages(); }

  startEdit(row: Reinsurer): void {
    this.draft = {
      id: row.id,
      name: row.name,
      contactEmail: row.contactEmail,
      contactAddress: row.contactAddress,
      jurisdictionCode: row.jurisdictionCode,
      homeCurrency: row.homeCurrency,
      creditRating: row.creditRating,
      active: row.active,
    };
    this.showForm = true;
    this.clearMessages();
  }

  cancel(): void { this.showForm = false; this.draft = this.empty(); }

  save(): void {
    if (!this.draft.name?.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    this.saving = true;
    this.clearMessages();

    const create: CreateReinsurerPayload = {
      name: this.draft.name.trim(),
      contactEmail: this.draft.contactEmail?.trim() || undefined,
      contactAddress: this.draft.contactAddress?.trim() || undefined,
      jurisdictionCode: this.draft.jurisdictionCode?.trim() || undefined,
      homeCurrency: this.draft.homeCurrency?.trim() || undefined,
      creditRating: this.draft.creditRating?.trim() || undefined,
    };
    const stream = this.draft.id
      ? this.svc.updateReinsurer(this.draft.id, { ...create, active: this.draft.active ?? true })
      : this.svc.createReinsurer(create);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Reinsurer saved';
        this.showForm = false;
        this.fetchPage();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  deactivate(row: Reinsurer): void {
    if (!confirm(`Deactivate ${row.name}? Treaties already referencing them keep working — no new placements.`)) return;
    const payload: UpdateReinsurerPayload = {
      name: row.name,
      contactEmail: row.contactEmail,
      contactAddress: row.contactAddress,
      jurisdictionCode: row.jurisdictionCode,
      homeCurrency: row.homeCurrency,
      creditRating: row.creditRating,
      active: false,
    };
    this.svc.updateReinsurer(row.id, payload).subscribe({
      next: () => { this.successMessage = 'Reinsurer deactivated'; this.fetchPage(); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Deactivate failed'; },
    });
  }

  private empty(): ReinsurerDraft {
    return { name: '', active: true };
  }

  private clearMessages(): void {
    this.errorMessage = null;
    this.successMessage = null;
  }
}
