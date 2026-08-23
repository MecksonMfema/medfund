import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  CreateProducerPayload,
  Producer,
  ProducerService,
  UpdateProducerPayload,
} from '../../../core/services/producer.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../shared/components/data-table/data-table.component';

interface ProducerDraft extends UpdateProducerPayload {
  id?: string;
  producerCode?: string;
  parentProducerLabel?: string | null;
}

@Component({
  selector: 'app-producers-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, DataTableComponent],
  templateUrl: './producers-list.component.html',
  styleUrl: './producers-list.component.scss',
})
export class ProducersListComponent implements OnInit {
  rows: Producer[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  draft: ProducerDraft = this.empty();

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  filterActive: '' | 'true' | 'false' = 'true';

  parentSearchQuery = '';
  parentMatches: Producer[] = [];
  parentSearching = false;
  private parentSearchTimer: ReturnType<typeof setTimeout> | null = null;

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
      handler: (row: Producer) => this.startEdit(row) },
    { label: 'Assignments', icon: 'users', color: 'default',
      handler: (row: Producer) => this.viewAssignments(row) },
  ];

  constructor(private svc: ProducerService) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    const active = this.filterActive === '' ? undefined : this.filterActive === 'true';
    this.svc.listProducers(this.page - 1, this.pageSize, active).subscribe({
      next: (resp) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load producers';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onPageChange(page: number): void { this.page = page; this.fetchPage(); }
  onFilterChange(): void { this.page = 1; this.fetchPage(); }

  startCreate(): void {
    this.draft = this.empty();
    this.showForm = true;
    this.parentSearchQuery = '';
    this.parentMatches = [];
    this.clearMessages();
  }

  startEdit(row: Producer): void {
    this.draft = {
      id: row.id,
      producerCode: row.producerCode,
      name: row.name,
      contactEmail: row.contactEmail,
      contactPhone: row.contactPhone,
      jurisdictionCode: row.jurisdictionCode,
      homeCurrency: row.homeCurrency,
      parentProducerId: row.parentProducerId ?? null,
      parentProducerLabel: null,
      whtPctOverride: row.whtPctOverride,
      bankingDetailsJson: row.bankingDetailsJson,
      active: row.active,
    };
    if (row.parentProducerId) {
      this.svc.getProducer(row.parentProducerId).subscribe({
        next: p => { this.draft.parentProducerLabel = `${p.producerCode} — ${p.name}`; },
      });
    }
    this.showForm = true;
    this.clearMessages();
  }

  viewAssignments(row: Producer): void {
    window.location.href = `/tenant/admin/producers/${row.id}/assignments`;
  }

  cancel(): void { this.showForm = false; this.draft = this.empty(); }

  onParentSearchChange(): void {
    if (this.parentSearchTimer) clearTimeout(this.parentSearchTimer);
    const q = this.parentSearchQuery.trim();
    if (!q) { this.parentMatches = []; return; }
    this.parentSearching = true;
    this.parentSearchTimer = setTimeout(() => {
      this.svc.searchProducers(q, 10).subscribe({
        next: rows => { this.parentMatches = rows; this.parentSearching = false; },
        error: () => { this.parentMatches = []; this.parentSearching = false; },
      });
    }, 300);
  }

  pickParent(p: Producer): void {
    this.draft.parentProducerId = p.id;
    this.draft.parentProducerLabel = `${p.producerCode} — ${p.name}`;
    this.parentSearchQuery = '';
    this.parentMatches = [];
  }

  clearParent(): void {
    this.draft.parentProducerId = null;
    this.draft.parentProducerLabel = null;
  }

  save(): void {
    if (!this.draft.name?.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    if (!this.draft.homeCurrency?.trim() || this.draft.homeCurrency.length !== 3) {
      this.errorMessage = 'Home currency must be a 3-letter ISO code';
      return;
    }
    if (!this.draft.id && !this.draft.producerCode?.trim()) {
      this.errorMessage = 'Producer code is required';
      return;
    }
    this.saving = true;
    this.clearMessages();

    const base = {
      name: this.draft.name.trim(),
      contactEmail: this.draft.contactEmail?.trim() || undefined,
      contactPhone: this.draft.contactPhone?.trim() || undefined,
      jurisdictionCode: this.draft.jurisdictionCode?.trim() || undefined,
      homeCurrency: this.draft.homeCurrency.trim().toUpperCase(),
      parentProducerId: this.draft.parentProducerId || null,
      whtPctOverride: this.draft.whtPctOverride ?? null,
      bankingDetailsJson: this.draft.bankingDetailsJson?.trim() || null,
    };

    const stream = this.draft.id
      ? this.svc.updateProducer(this.draft.id,
          { ...base, active: this.draft.active ?? true })
      : this.svc.createProducer({
          ...base,
          producerCode: this.draft.producerCode!.trim(),
        } as CreateProducerPayload);

    stream.subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Producer saved';
        this.showForm = false;
        this.fetchPage();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  private empty(): ProducerDraft {
    return {
      name: '',
      producerCode: '',
      homeCurrency: 'USD',
      active: true,
      parentProducerId: null,
      parentProducerLabel: null,
    };
  }

  private clearMessages(): void {
    this.errorMessage = null;
    this.successMessage = null;
  }
}
