import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import {
  CohortType,
  CreateIfrs17CohortPayload,
  Ifrs17Cohort,
  Ifrs17CohortService,
} from '../../../core/services/ifrs17-cohort.service';
import {
  Ifrs17Portfolio,
  Ifrs17PortfolioService,
} from '../../../core/services/ifrs17-portfolio.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

interface CohortDraft {
  id?: string;
  portfolioId: string;
  cohortYear: number;
  cohortType: CohortType;
  name: string;
}

@Component({
  selector: 'app-cohorts-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './cohorts-list.component.html',
})
export class CohortsListComponent implements OnInit {
  rows: Ifrs17Cohort[] = [];
  loading = false;
  saving = false;
  showInactive = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  draft: CohortDraft = this.empty();

  // Portfolio picker state — debounced search-select per feedback_no_raw_id_inputs.
  portfolioQuery = '';
  portfolioResults: Ifrs17Portfolio[] = [];
  selectedPortfolioLabel = '';
  private readonly portfolioSearch$ = new Subject<string>();

  readonly cohortTypes: CohortType[] = ['ONEROUS', 'NON_ONEROUS', 'UNCERTAIN'];

  constructor(
    private svc: Ifrs17CohortService,
    private portfolioSvc: Ifrs17PortfolioService,
  ) {
    this.portfolioSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => this.portfolioSvc.search(q, 10)),
      )
      .subscribe({
        next: (results) => { this.portfolioResults = results; },
        error: () => { this.portfolioResults = []; },
      });
  }

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading = true;
    this.svc.list(this.showInactive).subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load cohorts';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onToggleInactive(): void { this.showInactive = !this.showInactive; this.load(); }

  startCreate(): void {
    this.draft = this.empty();
    this.portfolioQuery = '';
    this.selectedPortfolioLabel = '';
    this.portfolioResults = [];
    this.showForm = true;
  }

  startEdit(row: Ifrs17Cohort): void {
    this.draft = {
      id: row.id,
      portfolioId: row.portfolioId,
      cohortYear: row.cohortYear,
      cohortType: row.cohortType,
      name: row.name,
    };
    // Best-effort resolve the portfolio label for the picker.
    this.portfolioSvc.getById(row.portfolioId).subscribe({
      next: (p) => { this.selectedPortfolioLabel = p.name; },
      error: () => { this.selectedPortfolioLabel = row.portfolioId; },
    });
    this.showForm = true;
  }

  cancel(): void { this.showForm = false; this.draft = this.empty(); }

  onPortfolioQueryChange(q: string): void {
    this.portfolioQuery = q;
    if (q && q.trim().length >= 1) {
      this.portfolioSearch$.next(q.trim());
    } else {
      this.portfolioResults = [];
    }
  }

  pickPortfolio(p: Ifrs17Portfolio): void {
    this.draft.portfolioId = p.id;
    this.selectedPortfolioLabel = p.name;
    this.portfolioQuery = '';
    this.portfolioResults = [];
  }

  save(): void {
    if (!this.draft.portfolioId) {
      this.errorMessage = 'Portfolio is required';
      return;
    }
    if (!this.draft.name.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    if (!this.draft.cohortYear || this.draft.cohortYear < 1900 || this.draft.cohortYear > 2200) {
      this.errorMessage = 'Cohort year must be between 1900 and 2200';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    this.successMessage = null;
    const payload: CreateIfrs17CohortPayload = {
      portfolioId: this.draft.portfolioId,
      cohortYear: this.draft.cohortYear,
      cohortType: this.draft.cohortType,
      name: this.draft.name.trim(),
    };
    const stream = this.draft.id
      ? this.svc.update(this.draft.id, payload)
      : this.svc.create(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Cohort saved';
        this.showForm = false;
        this.load();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  softDelete(row: Ifrs17Cohort): void {
    if (!confirm(`Deactivate cohort "${row.name}"? Policies still referencing it stay linked.`)) return;
    this.svc.delete(row.id).subscribe({
      next: () => { this.successMessage = 'Cohort deactivated'; this.load(); },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Deactivate failed';
      },
    });
  }

  private empty(): CohortDraft {
    return {
      portfolioId: '',
      cohortYear: new Date().getFullYear(),
      cohortType: 'NON_ONEROUS',
      name: '',
    };
  }
}
