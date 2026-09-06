import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import {
  Ifrs17OpeningBalanceSeedRow,
  Ifrs17OpeningBalanceSeedService,
  CreateIfrs17OpeningBalanceSeed,
  UpdateIfrs17OpeningBalanceSeed,
  BalanceType,
} from '../../../../core/services/ifrs17-opening-balance-seed.service';
import { Ifrs17PortfolioService, Ifrs17Portfolio } from '../../../../core/services/ifrs17-portfolio.service';
import { Ifrs17CohortService, Ifrs17Cohort } from '../../../../core/services/ifrs17-cohort.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

interface EditableRow extends Ifrs17OpeningBalanceSeedRow {
  editing?: boolean;
  draftAmount?: string;
  draftReasonNote?: string;
  portfolioName?: string;
  cohortName?: string;
}

const BALANCE_TYPES: SelectOption[] = [
  { value: 'LRC', label: 'LRC (Liability for Remaining Coverage)' },
  { value: 'LIC', label: 'LIC (Liability for Incurred Claims)' },
];

/**
 * Phase 15 §7 (I29) admin sub-tab — CRUD for tenant-admin overrides on
 * auto-derived IFRS 17 opening balances. §17 shaping consults the seed
 * first; falls back to the auto-derived value when nothing matches.
 *
 * <p>Follows the Phase 2 grouping deviation: this sub-tab lives inside
 * the IFRS 17 Config parent so the tenant-admin settings tab bar stays
 * readable. Portfolio + cohort pickers are select dropdowns backed by
 * the loaded lists (small cardinality) — no raw ID inputs per
 * {@code feedback_no_raw_id_inputs}. Payload holds the ID; the row
 * shows portfolio/cohort names.
 */
@Component({
  selector: 'app-ifrs17-opening-balances-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './opening-balances-tab.component.html',
  styleUrl: './ifrs17-tab-shared.scss',
})
export class OpeningBalancesTabComponent implements OnInit {
  rows: EditableRow[] = [];
  portfolios: Ifrs17Portfolio[] = [];
  cohorts: Ifrs17Cohort[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  pendingId: string | null = null;

  addingOpen = false;
  adding = false;
  newRow: CreateIfrs17OpeningBalanceSeed = this.blankNewRow();

  readonly balanceTypeOptions = BALANCE_TYPES;

  constructor(
    private service: Ifrs17OpeningBalanceSeedService,
    private portfolioService: Ifrs17PortfolioService,
    private cohortService: Ifrs17CohortService,
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.errorMessage = null;
    forkJoin({
      seeds: this.service.list(),
      portfolios: this.portfolioService.list(),
      cohorts: this.cohortService.list(),
    }).subscribe({
      next: ({ seeds, portfolios, cohorts }) => {
        this.portfolios = portfolios;
        this.cohorts = cohorts;
        this.rows = seeds.map(s => ({
          ...s,
          portfolioName: this.portfolioName(s.portfolioId, portfolios),
          cohortName: this.cohortName(s.cohortId, cohorts),
        }));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load opening balance seeds';
        this.loading = false;
      },
    });
  }

  openAdd(): void {
    this.addingOpen = true;
    this.newRow = this.blankNewRow();
  }

  cancelAdd(): void {
    this.addingOpen = false;
    this.newRow = this.blankNewRow();
  }

  cohortOptionsForSelected(): SelectOption[] {
    if (!this.newRow.portfolioId) return [];
    return this.cohorts
      .filter(c => c.portfolioId === this.newRow.portfolioId)
      .map(c => ({ value: c.id, label: `${c.name} (${c.cohortYear} / ${c.cohortType})` }));
  }

  portfolioOptions(): SelectOption[] {
    return this.portfolios.map(p => ({ value: p.id, label: p.name }));
  }

  onPortfolioChange(): void {
    // Clear cohort when portfolio changes so the two never disagree.
    this.newRow.cohortId = '';
  }

  add(): void {
    if (!this.newRow.portfolioId || !this.newRow.cohortId
        || !this.newRow.currency || !this.newRow.balanceType
        || !this.newRow.amount || !this.newRow.effectiveFrom
        || !this.newRow.reasonNote?.trim()) {
      this.errorMessage = 'Portfolio, cohort, currency, balance type, amount, effective date, and reason note are all required';
      return;
    }
    this.adding = true;
    this.errorMessage = null;
    this.service.add(this.trimAddPayload(this.newRow)).subscribe({
      next: () => {
        this.adding = false;
        this.addingOpen = false;
        this.successMessage =
          `Added ${this.newRow.balanceType} ${this.newRow.amount} ${this.newRow.currency}`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.newRow = this.blankNewRow();
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to add opening balance seed';
      },
    });
  }

  startEdit(row: EditableRow): void {
    row.editing = true;
    row.draftAmount = row.amount;
    row.draftReasonNote = row.reasonNote;
  }

  cancelEdit(row: EditableRow): void {
    row.editing = false;
    row.draftAmount = undefined;
    row.draftReasonNote = undefined;
  }

  saveEdit(row: EditableRow): void {
    if (!(row.draftAmount ?? '').trim() || !(row.draftReasonNote ?? '').trim()) {
      this.errorMessage = 'Amount and reason note are required';
      return;
    }
    const payload: UpdateIfrs17OpeningBalanceSeed = {
      amount: (row.draftAmount ?? '').trim(),
      reasonNote: (row.draftReasonNote ?? '').trim(),
    };
    this.pendingId = row.id;
    this.service.update(row.id, payload).subscribe({
      next: (updated) => {
        Object.assign(row, updated, {
          editing: false,
          portfolioName: this.portfolioName(updated.portfolioId, this.portfolios),
          cohortName: this.cohortName(updated.cohortId, this.cohorts),
        });
        row.draftAmount = undefined;
        row.draftReasonNote = undefined;
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to update opening balance seed';
        this.pendingId = null;
      },
    });
  }

  remove(row: EditableRow): void {
    const label = `${row.balanceType} ${row.amount} ${row.currency} @ ${row.effectiveFrom}`;
    if (!confirm(`Delete opening balance seed for ${label}?`)) return;
    this.pendingId = row.id;
    this.service.delete(row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete opening balance seed';
        this.pendingId = null;
      },
    });
  }

  private portfolioName(id: string, portfolios: Ifrs17Portfolio[]): string {
    return portfolios.find(p => p.id === id)?.name ?? id;
  }

  private cohortName(id: string, cohorts: Ifrs17Cohort[]): string {
    const c = cohorts.find(x => x.id === id);
    return c ? `${c.name} (${c.cohortYear} / ${c.cohortType})` : id;
  }

  private blankNewRow(): CreateIfrs17OpeningBalanceSeed {
    return {
      portfolioId: '',
      cohortId: '',
      currency: 'USD',
      balanceType: 'LRC',
      amount: '',
      effectiveFrom: '',
      reasonNote: '',
    };
  }

  private trimAddPayload(p: CreateIfrs17OpeningBalanceSeed): CreateIfrs17OpeningBalanceSeed {
    return {
      portfolioId: p.portfolioId,
      cohortId: p.cohortId,
      currency: (p.currency ?? '').toString().trim().toUpperCase(),
      balanceType: p.balanceType as BalanceType,
      amount: (p.amount ?? '').toString().trim(),
      effectiveFrom: p.effectiveFrom,
      reasonNote: (p.reasonNote ?? '').toString().trim(),
    };
  }
}
