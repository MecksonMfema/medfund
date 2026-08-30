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
import {
  CohortStatusHistoryRow,
  CohortStatusHistoryService,
} from '../../../core/services/cohort-status-history.service';
import {
  CohortLossComponentBalance,
  CohortLossComponentRow,
  CohortLossComponentService,
  LossComponentMovementType,
} from '../../../core/services/cohort-loss-component.service';
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
  styleUrls: ['./cohorts-list.component.scss'],
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

  // Status history modal state — Phase 15 §4 (I11).
  historyModalOpen = false;
  historyCohort: Ifrs17Cohort | null = null;
  historyRows: CohortStatusHistoryRow[] = [];
  historyLoading = false;
  historyError: string | null = null;

  // Loss component modal state — Phase 15 §5 (I19). ONEROUS cohorts only.
  readonly movementTypes: LossComponentMovementType[] = [
    'INITIAL_RECOGNITION',
    'RELEASE',
    'REVERSAL',
    'RECLASSIFICATION_TO_NON_ONEROUS',
  ];
  lossModalOpen = false;
  lossCohort: Ifrs17Cohort | null = null;
  lossRows: CohortLossComponentRow[] = [];
  lossBalance: CohortLossComponentBalance | null = null;
  lossLoading = false;
  lossError: string | null = null;
  lossBalanceCurrency = 'USD';
  lossFormOpen = false;
  lossFormSaving = false;
  lossDraft: {
    movementType: LossComponentMovementType;
    amount: string;
    currency: string;
    reasonNote: string;
  } = this.emptyLossDraft();

  constructor(
    private svc: Ifrs17CohortService,
    private portfolioSvc: Ifrs17PortfolioService,
    private historySvc: CohortStatusHistoryService,
    private lossSvc: CohortLossComponentService,
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

  openHistory(row: Ifrs17Cohort): void {
    this.historyCohort = row;
    this.historyRows = [];
    this.historyError = null;
    this.historyLoading = true;
    this.historyModalOpen = true;
    this.historySvc.listForCohort(row.id).subscribe({
      next: (rows) => { this.historyRows = rows; this.historyLoading = false; },
      error: (err) => {
        this.historyError = err?.error?.detail || err?.error?.title || 'Failed to load status history';
        this.historyLoading = false;
      },
    });
  }

  closeHistory(): void {
    this.historyModalOpen = false;
    this.historyCohort = null;
    this.historyRows = [];
    this.historyError = null;
  }

  openLossComponent(row: Ifrs17Cohort): void {
    this.lossCohort = row;
    this.lossRows = [];
    this.lossBalance = null;
    this.lossError = null;
    this.lossLoading = true;
    this.lossModalOpen = true;
    this.lossFormOpen = false;
    this.lossDraft = this.emptyLossDraft();
    this.loadLossRows(row.id);
    this.loadLossBalance(row.id, this.lossBalanceCurrency);
  }

  onLossBalanceCurrencyChange(currency: string): void {
    this.lossBalanceCurrency = currency;
    if (this.lossCohort) {
      this.loadLossBalance(this.lossCohort.id, currency);
    }
  }

  startRecordMovement(): void {
    this.lossDraft = this.emptyLossDraft();
    this.lossDraft.currency = this.lossBalanceCurrency;
    this.lossFormOpen = true;
  }

  cancelRecordMovement(): void {
    this.lossFormOpen = false;
    this.lossDraft = this.emptyLossDraft();
  }

  saveMovement(): void {
    if (!this.lossCohort) return;
    const amount = (this.lossDraft.amount || '').trim();
    if (!amount || Number(amount) <= 0) {
      this.lossError = 'Amount must be positive';
      return;
    }
    if (!/^[A-Z]{3}$/.test(this.lossDraft.currency)) {
      this.lossError = 'Currency must be an ISO-4217 3-letter code';
      return;
    }
    this.lossFormSaving = true;
    this.lossError = null;
    this.lossSvc
      .record(this.lossCohort.id, {
        movementType: this.lossDraft.movementType,
        amount,
        currency: this.lossDraft.currency,
        reasonNote: this.lossDraft.reasonNote || undefined,
      })
      .subscribe({
        next: () => {
          this.lossFormSaving = false;
          this.lossFormOpen = false;
          this.successMessage = 'Loss-component movement recorded';
          if (this.lossCohort) {
            this.loadLossRows(this.lossCohort.id);
            this.loadLossBalance(this.lossCohort.id, this.lossBalanceCurrency);
          }
        },
        error: (err) => {
          this.lossFormSaving = false;
          this.lossError =
            err?.error?.detail || err?.error?.title || 'Failed to record movement';
        },
      });
  }

  closeLossComponent(): void {
    this.lossModalOpen = false;
    this.lossCohort = null;
    this.lossRows = [];
    this.lossBalance = null;
    this.lossError = null;
    this.lossFormOpen = false;
  }

  private loadLossRows(cohortId: string): void {
    this.lossLoading = true;
    this.lossSvc.listForCohort(cohortId).subscribe({
      next: (rows) => {
        this.lossRows = rows;
        this.lossLoading = false;
      },
      error: (err) => {
        this.lossError =
          err?.error?.detail || err?.error?.title || 'Failed to load loss component';
        this.lossLoading = false;
      },
    });
  }

  private loadLossBalance(cohortId: string, currency: string): void {
    // Balance is a matview read — may be stale until a report run refreshes it.
    this.lossSvc.balance(cohortId, currency).subscribe({
      next: (b) => { this.lossBalance = b; },
      error: () => { this.lossBalance = null; },
    });
  }

  private emptyLossDraft() {
    return {
      movementType: 'INITIAL_RECOGNITION' as LossComponentMovementType,
      amount: '',
      currency: 'USD',
      reasonNote: '',
    };
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
