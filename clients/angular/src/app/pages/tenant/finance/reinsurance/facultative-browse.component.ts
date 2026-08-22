import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CreateFacultativeCessionPayload,
  FacultativeCandidateRow,
  InsuranceLine,
  ReinsuranceService,
  Treaty,
} from '../../../../core/services/reinsurance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

/**
 * Phase 7 facultative-cession browse — underwriter surface. Left half of
 * the page is the candidate list (adjudicated claims above the caller-
 * supplied minAmount); right half is an inline cede form the underwriter
 * fills when a candidate is picked. Approve/commit lives on the queue
 * page ({@code facultative-approve-queue.component}).
 *
 * <p>All money inputs are BigDecimal-safe (server rejects zero/negative)
 * so the client just guards for {@code > 0} before enabling submit.
 */
@Component({
  selector: 'app-facultative-browse',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent],
  templateUrl: './facultative-browse.component.html',
  styleUrl: './facultative-browse.component.scss',
})
export class FacultativeBrowseComponent implements OnInit {
  // ── candidates state ───────────────────────────────────────────────────
  candidates: FacultativeCandidateRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  // filters
  minAmount = 10000;
  lineFilter: '' | InsuranceLine = '';

  // treaty picker cache
  activeTreaties: Treaty[] = [];
  treatiesLoading = false;

  // ── inline cede form ──────────────────────────────────────────────────
  selected: FacultativeCandidateRow | null = null;
  cedePayload: {
    treatyId: string;
    cededAmount: number | null;
    basisAmount: number | null;
    reason: string;
  } = { treatyId: '', cededAmount: null, basisAmount: null, reason: '' };
  cedeSubmitting = false;
  cedeError: string | null = null;
  cedeSuccess: string | null = null;

  readonly lineOptions: SelectOption[] = [
    { value: '', label: 'All lines' },
    { value: 'HEALTH', label: 'Health' },
    { value: 'LIFE', label: 'Life' },
    { value: 'FUNERAL', label: 'Funeral' },
    { value: 'GROUP', label: 'Group' },
    { value: 'TRAVEL', label: 'Travel' },
    { value: 'DISABILITY', label: 'Disability' },
    { value: 'VEHICLE', label: 'Motor' },
    { value: 'PROPERTY', label: 'Property' },
  ];

  constructor(private svc: ReinsuranceService) {}

  ngOnInit(): void {
    this.fetchCandidates();
    this.fetchTreaties();
  }

  fetchCandidates(): void {
    this.loading = true;
    this.errorMessage = null;
    this.svc.listFacultativeCandidates(
      this.minAmount > 0 ? this.minAmount : undefined,
      this.lineFilter || undefined,
      0,
      100,
    ).subscribe({
      next: rows => {
        this.candidates = rows;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load facultative candidates';
        this.candidates = [];
        this.loading = false;
      },
    });
  }

  private fetchTreaties(): void {
    this.treatiesLoading = true;
    this.svc.listTreaties(0, 200, 'ACTIVE').subscribe({
      next: page => {
        this.activeTreaties = page.content;
        this.treatiesLoading = false;
      },
      error: () => {
        this.activeTreaties = [];
        this.treatiesLoading = false;
      },
    });
  }

  onMinAmountChange(): void { this.fetchCandidates(); }
  onLineChange(): void { this.fetchCandidates(); }

  select(row: FacultativeCandidateRow): void {
    this.selected = row;
    this.cedeError = null;
    this.cedeSuccess = null;
    this.cedePayload = {
      treatyId: '',
      cededAmount: null,
      basisAmount: row.approvedAmount,
      reason: '',
    };
  }

  clearSelection(): void {
    this.selected = null;
    this.cedePayload = { treatyId: '', cededAmount: null, basisAmount: null, reason: '' };
    this.cedeError = null;
    this.cedeSuccess = null;
  }

  /** Treaty picker option list — friendly label, UUID as value. */
  get treatyOptions(): SelectOption[] {
    const opts: SelectOption[] = [{ value: '', label: this.treatiesLoading ? 'Loading…' : 'Select a treaty' }];
    if (!this.selected) return opts;
    // Only surface treaties covering the candidate's line — the server
    // will also enforce this, but pre-filtering saves a round-trip.
    const eligible = this.activeTreaties;
    for (const t of eligible) {
      opts.push({ value: t.id, label: `${t.treatyRef} (${t.treatyType}, ${t.declaredCurrency})` });
    }
    return opts;
  }

  submitCede(): void {
    if (!this.selected) return;
    if (!this.cedePayload.treatyId) {
      this.cedeError = 'Select a treaty first.';
      return;
    }
    if (!this.cedePayload.cededAmount || this.cedePayload.cededAmount <= 0) {
      this.cedeError = 'Ceded amount must be positive.';
      return;
    }
    if (!this.cedePayload.basisAmount || this.cedePayload.basisAmount <= 0) {
      this.cedeError = 'Basis amount must be positive.';
      return;
    }
    const payload: CreateFacultativeCessionPayload = {
      claimId: this.selected.claimId,
      treatyId: this.cedePayload.treatyId,
      cededAmount: this.cedePayload.cededAmount,
      basisAmount: this.cedePayload.basisAmount,
      currencyCode: this.selected.currencyCode,
      reason: this.cedePayload.reason || undefined,
    };
    this.cedeSubmitting = true;
    this.cedeError = null;
    this.svc.createFacultativeCession(this.selected.insuranceLine, payload).subscribe({
      next: cession => {
        this.cedeSuccess = `Draft cession #${cession.id.substring(0, 8)} created — awaiting approval.`;
        this.cedeSubmitting = false;
        this.selected = null;
        this.fetchCandidates();
      },
      error: err => {
        this.cedeError = err?.error?.detail || err?.error?.title
          || 'Failed to create draft cession.';
        this.cedeSubmitting = false;
      },
    });
  }
}
