import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CreateIfrs17PortfolioPayload,
  Ifrs17Portfolio,
  Ifrs17PortfolioService,
  InsuranceLine,
} from '../../../core/services/ifrs17-portfolio.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

interface PortfolioDraft extends CreateIfrs17PortfolioPayload {
  id?: string;
}

@Component({
  selector: 'app-portfolios-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './portfolios-list.component.html',
})
export class PortfoliosListComponent implements OnInit {
  rows: Ifrs17Portfolio[] = [];
  loading = false;
  saving = false;
  showInactive = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  draft: PortfolioDraft = this.empty();

  readonly insuranceLines: (InsuranceLine | '')[] = [
    '', 'HEALTH', 'LIFE', 'FUNERAL', 'GROUP', 'TRAVEL', 'DISABILITY', 'VEHICLE', 'PROPERTY',
  ];

  constructor(private svc: Ifrs17PortfolioService) {}

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading = true;
    this.svc.list(this.showInactive).subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load portfolios';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  startCreate(): void { this.draft = this.empty(); this.showForm = true; }

  startEdit(row: Ifrs17Portfolio): void {
    this.draft = {
      id: row.id,
      name: row.name,
      description: row.description ?? null,
      insuranceLine: row.insuranceLine ?? null,
    };
    this.showForm = true;
  }

  cancel(): void { this.showForm = false; this.draft = this.empty(); }

  save(): void {
    if (!this.draft.name.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    this.successMessage = null;
    const payload: CreateIfrs17PortfolioPayload = {
      name: this.draft.name.trim(),
      description: this.draft.description?.trim() || null,
      insuranceLine: this.draft.insuranceLine || null,
    };
    const stream = this.draft.id
      ? this.svc.update(this.draft.id, payload)
      : this.svc.create(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Portfolio saved';
        this.showForm = false;
        this.load();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  softDelete(row: Ifrs17Portfolio): void {
    if (!confirm(`Deactivate portfolio "${row.name}"? Policies still referencing it stay linked.`)) return;
    this.svc.delete(row.id).subscribe({
      next: () => { this.successMessage = 'Portfolio deactivated'; this.load(); },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Deactivate failed';
      },
    });
  }

  onToggleInactive(): void { this.showInactive = !this.showInactive; this.load(); }

  private empty(): PortfolioDraft {
    return { name: '', description: null, insuranceLine: null };
  }
}
