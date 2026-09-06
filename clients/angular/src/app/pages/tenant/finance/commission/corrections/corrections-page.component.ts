import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  Adjustment,
  AdjustmentStatus,
  AdjustmentType,
  CommissionAdjustmentService,
} from '../../../../../core/services/commission-adjustment.service';
import { PermissionService } from '../../../../../core/security/permission.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';

/**
 * Merged Commission corrections page. Single view, status-filtered list.
 *
 * <p>The toolbar's "New correction" button is gated on
 * {@code finance.commission:draft_adjustment}; the row-level Approve /
 * Commit / Void buttons are gated on
 * {@code finance.commission:approve_adjustment}. Viewers with only
 * {@code finance.commission:view} see a read-only list.
 *
 * <p>Four-eyes invariant is enforced server-side (a drafter approving
 * their own row is rejected with 409); the client only decorates.
 *
 * <p>Internally the class still uses the CommissionAdjustment domain
 * types (Adjustment / AdjustmentStatus / AdjustmentType). Only the
 * user-visible strings say "correction" — the API URL and backend types
 * stay {@code CommissionAdjustment} to match the wire.
 */
@Component({
  selector: 'app-corrections-page',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './corrections-page.component.html',
  styleUrl: './corrections-page.component.scss',
})
export class CorrectionsPageComponent implements OnInit {
  rows: Adjustment[] = [];
  loading = false;
  errorMessage: string | null = null;
  statusFilter: '' | AdjustmentStatus = '';

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;

  actionInProgress: Record<string, boolean> = {};

  readonly canDraft: boolean;
  readonly canApprove: boolean;

  readonly statusOptions: Array<{ value: '' | AdjustmentStatus; label: string }> = [
    { value: '',          label: 'All open (DRAFT + APPROVED)' },
    { value: 'DRAFT',     label: 'Draft' },
    { value: 'APPROVED',  label: 'Approved' },
    { value: 'COMMITTED', label: 'Committed' },
    { value: 'VOIDED',    label: 'Voided' },
  ];

  // ── Create modal state ────────────────────────────────────────────────
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

  // ── Void modal state ──────────────────────────────────────────────────
  voidTargetId: string | null = null;
  voidReason = '';
  voidSubmitting = false;
  voidError: string | null = null;

  constructor(
    private svc: CommissionAdjustmentService,
    perms: PermissionService,
  ) {
    this.canDraft = perms.hasAny(['finance.commission:draft_adjustment']);
    this.canApprove = perms.hasAny(['finance.commission:approve_adjustment']);
  }

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
          this.targetSearchLoading = false;
          return [];
        }),
      )
      .subscribe();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.svc.list(this.statusFilter || undefined, this.page - 1, this.pageSize).subscribe({
      next: page => {
        this.rows = page.content;
        this.totalCount = page.total;
        this.totalPages = page.totalPages;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load corrections queue';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.page = 1; this.fetchPage(); }

  // ── Create actions ────────────────────────────────────────────────────
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
    this.searchInput$.next(this.form.targetReferenceQuery);
  }

  submitCreate(): void {
    this.createError = null;

    if (!this.form.targetCommissionTransactionId
        && !this.form.targetReferenceQuery.trim()) {
      this.createError = 'Target commission_transaction id is required.';
      return;
    }
    const targetId = this.form.targetCommissionTransactionId
      || this.form.targetReferenceQuery.trim();

    if (this.form.adjustmentAmount == null || Number(this.form.adjustmentAmount) === 0) {
      this.createError = 'Correction amount must be non-zero.';
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
          || 'Failed to create correction.';
        this.createSubmitting = false;
      },
    });
  }

  // ── Row actions ───────────────────────────────────────────────────────
  approve(row: Adjustment): void {
    if (this.actionInProgress[row.id]) return;
    this.actionInProgress[row.id] = true;
    this.svc.approve(row.id).subscribe({
      next: () => { this.actionInProgress[row.id] = false; this.fetchPage(); },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Approve failed.';
        this.actionInProgress[row.id] = false;
      },
    });
  }

  commit(row: Adjustment): void {
    if (this.actionInProgress[row.id]) return;
    this.actionInProgress[row.id] = true;
    this.svc.commit(row.id).subscribe({
      next: () => { this.actionInProgress[row.id] = false; this.fetchPage(); },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Commit failed.';
        this.actionInProgress[row.id] = false;
      },
    });
  }

  openVoid(row: Adjustment): void {
    this.voidTargetId = row.id;
    this.voidReason = '';
    this.voidError = null;
  }

  cancelVoid(): void {
    this.voidTargetId = null;
    this.voidReason = '';
    this.voidError = null;
    this.voidSubmitting = false;
  }

  submitVoid(): void {
    if (!this.voidTargetId) return;
    if (!this.voidReason.trim() || this.voidReason.trim().length < 5) {
      this.voidError = 'Reason must be at least 5 characters.';
      return;
    }
    this.voidSubmitting = true;
    this.voidError = null;
    this.svc.void(this.voidTargetId, this.voidReason.trim()).subscribe({
      next: () => {
        this.voidSubmitting = false;
        this.cancelVoid();
        this.fetchPage();
      },
      error: err => {
        this.voidError = err?.error?.detail || err?.error?.title || 'Void failed.';
        this.voidSubmitting = false;
      },
    });
  }

  isTerminal(row: Adjustment): boolean {
    return row.status === 'COMMITTED' || row.status === 'VOIDED';
  }
}
