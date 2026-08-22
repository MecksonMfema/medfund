import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ReinsuranceService,
  ResolveReviewTaskPayload,
  ReviewTaskResolution,
  ReviewTaskRow,
  ReviewTaskStatus,
} from '../../../../core/services/reinsurance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

/**
 * Phase 8 review queue. Populated primarily by claim-regression detection
 * in the loss cession consumer; operators pick tasks up, then resolve
 * with one of RESOLVED_VOID (cascade-voids cession + recovery),
 * RESOLVED_KEEP (leaves cession alone), or DISMISSED (false positive).
 */
@Component({
  selector: 'app-reinsurance-review-queue',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent],
  templateUrl: './review-queue.component.html',
  styleUrl: './review-queue.component.scss',
})
export class ReinsuranceReviewQueueComponent implements OnInit {
  rows: ReviewTaskRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  statusFilter: '' | ReviewTaskStatus = '';

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;

  resolveTargetId: string | null = null;
  resolveResolution: ReviewTaskResolution = 'RESOLVED_KEEP';
  resolveNotes = '';
  resolveSubmitting = false;
  resolveError: string | null = null;

  actionInProgress: Record<string, boolean> = {};

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'Open queue (OPEN + IN_PROGRESS)' },
    { value: 'OPEN', label: 'Open' },
    { value: 'IN_PROGRESS', label: 'In progress' },
    { value: 'RESOLVED_VOID', label: 'Resolved — voided' },
    { value: 'RESOLVED_KEEP', label: 'Resolved — kept' },
    { value: 'DISMISSED', label: 'Dismissed' },
  ];

  readonly resolutionOptions: SelectOption[] = [
    { value: 'RESOLVED_KEEP', label: 'Keep cession — the re-adjudication is acceptable' },
    { value: 'RESOLVED_VOID', label: 'Void cession + write off recovery' },
    { value: 'DISMISSED', label: 'Dismiss — false positive' },
  ];

  constructor(private svc: ReinsuranceService) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.svc.listReviewTasks(
      this.statusFilter || undefined,
      this.page - 1,
      this.pageSize,
    ).subscribe({
      next: pageResp => {
        this.rows = pageResp.content;
        this.totalCount = pageResp.total;
        this.totalPages = pageResp.totalPages;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load review queue';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.page = 1; this.fetchPage(); }

  openResolve(row: ReviewTaskRow): void {
    this.resolveTargetId = row.id;
    this.resolveResolution = 'RESOLVED_KEEP';
    this.resolveNotes = '';
    this.resolveError = null;
  }

  cancelResolve(): void {
    this.resolveTargetId = null;
    this.resolveNotes = '';
    this.resolveError = null;
    this.resolveSubmitting = false;
  }

  submitResolve(): void {
    if (!this.resolveTargetId) return;
    if (this.resolveResolution === 'RESOLVED_VOID' && !this.resolveNotes.trim()) {
      this.resolveError = 'Notes are required when voiding a cession.';
      return;
    }
    this.resolveSubmitting = true;
    this.resolveError = null;
    const payload: ResolveReviewTaskPayload = {
      resolution: this.resolveResolution,
      notes: this.resolveNotes.trim() || undefined,
    };
    this.svc.resolveReviewTask(this.resolveTargetId, payload).subscribe({
      next: () => {
        this.resolveSubmitting = false;
        this.cancelResolve();
        this.fetchPage();
      },
      error: err => {
        this.resolveError = err?.error?.detail || err?.error?.title || 'Resolve failed.';
        this.resolveSubmitting = false;
      },
    });
  }

  isOpen(row: ReviewTaskRow): boolean {
    return row.status === 'OPEN' || row.status === 'IN_PROGRESS';
  }

  statusLabel(status: ReviewTaskStatus): string {
    switch (status) {
      case 'OPEN':          return 'Open';
      case 'IN_PROGRESS':   return 'In progress';
      case 'RESOLVED_VOID': return 'Resolved — voided';
      case 'RESOLVED_KEEP': return 'Resolved — kept';
      case 'DISMISSED':     return 'Dismissed';
      default:              return status;
    }
  }
}
