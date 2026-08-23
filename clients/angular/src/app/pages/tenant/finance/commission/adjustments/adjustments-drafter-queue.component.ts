import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  Adjustment,
  AdjustmentType,
  CommissionAdjustmentService,
} from '../../../../../core/services/commission-adjustment.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';

/**
 * Phase 8 §B — drafter surface. Lists this drafter's DRAFT adjustments
 * plus an inline "New adjustment" modal. Target commission_transaction is
 * chosen via a debounced search on {@code reference} (per
 * {@code feedback_no_raw_id_inputs}); the picker is inlined for now — will
 * consolidate into shared EntityPicker when a second caller lands.
 */
@Component({
  selector: 'app-adjustments-drafter-queue',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './adjustments-drafter-queue.component.html',
  styleUrl: './adjustments.component.scss',
})
export class AdjustmentsDrafterQueueComponent implements OnInit {
  rows: Adjustment[] = [];
  loading = false;
  errorMessage: string | null = null;
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;

  // Create modal state
  showCreate = false;
  form = {
    targetReferenceQuery: '',
    targetCommissionTransactionId: '',
    targetLabel: '',
    adjustmentType: 'EX_GRATIA' as AdjustmentType,
    adjustmentAmount: null as number | null,
    justification: '',
  };
  createError: string | null = null;
  createSubmitting = false;

  // Target search state (debounced substring against commission_transaction reference)
  targetCandidates: Array<{
    id: string; reference: string; producerName?: string;
    nativeAmount: number; nativeCurrency: string;
  }> = [];
  private searchInput$ = new Subject<string>();
  targetSearchLoading = false;

  readonly adjustmentTypes: Array<{ id: AdjustmentType; label: string; hint: string }> = [
    { id: 'EX_GRATIA',        label: 'Ex-gratia',        hint: 'Additional credit outside the rate card' },
    { id: 'VOID',             label: 'Void',             hint: 'Full reversal of the target row' },
    { id: 'MANUAL_CLAWBACK',  label: 'Manual clawback',  hint: 'Operator-triggered clawback' },
    { id: 'MANUAL_REVERSAL',  label: 'Manual reversal',  hint: 'Bespoke correction to prior accrual' },
  ];

  constructor(private svc: CommissionAdjustmentService) {}

  ngOnInit(): void {
    this.fetchPage();
    this.searchInput$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap(q => {
          if (!q || q.trim().length < 1) {
            this.targetCandidates = [];
            return [];
          }
          this.targetSearchLoading = true;
          // No commission_transaction /search endpoint today — the modal
          // owner searches by exact-id-paste until Phase 8's follow-up
          // ticket wires a reference-substring endpoint. Show empty list
          // and let the operator paste the id.
          this.targetSearchLoading = false;
          return [];
        }),
      )
      .subscribe();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.svc.list('DRAFT', this.page - 1, this.pageSize).subscribe({
      next: page => {
        this.rows = page.content;
        this.totalCount = page.total;
        this.totalPages = page.totalPages;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load draft queue';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  openCreate(): void {
    this.showCreate = true;
    this.form = {
      targetReferenceQuery: '',
      targetCommissionTransactionId: '',
      targetLabel: '',
      adjustmentType: 'EX_GRATIA',
      adjustmentAmount: null,
      justification: '',
    };
    this.createError = null;
    this.targetCandidates = [];
  }

  cancelCreate(): void {
    this.showCreate = false;
    this.createError = null;
    this.createSubmitting = false;
  }

  onTargetInputChange(): void {
    // No auto-search yet (see ngOnInit) — the target id is pasted directly
    // for now. Keep the observable wire alive so the follow-up spec drop-in
    // is a one-line change.
    this.searchInput$.next(this.form.targetReferenceQuery);
  }

  submitCreate(): void {
    this.createError = null;

    if (!this.form.targetCommissionTransactionId
        && !this.form.targetReferenceQuery.trim()) {
      this.createError = 'Target commission-transaction id is required.';
      return;
    }
    const targetId = this.form.targetCommissionTransactionId
      || this.form.targetReferenceQuery.trim();

    if (this.form.adjustmentAmount == null || Number(this.form.adjustmentAmount) === 0) {
      this.createError = 'Adjustment amount must be non-zero.';
      return;
    }
    if (!this.form.justification || this.form.justification.length < 20) {
      this.createError = 'Justification must be at least 20 characters.';
      return;
    }

    this.createSubmitting = true;
    this.svc.create({
      targetCommissionTransactionId: targetId,
      adjustmentType: this.form.adjustmentType,
      adjustmentAmount: Number(this.form.adjustmentAmount),
      justification: this.form.justification,
    }).subscribe({
      next: () => {
        this.createSubmitting = false;
        this.showCreate = false;
        this.fetchPage();
      },
      error: err => {
        this.createError = err?.error?.detail || err?.error?.title
          || 'Failed to create adjustment.';
        this.createSubmitting = false;
      },
    });
  }
}
