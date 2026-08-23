import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Adjustment,
  AdjustmentStatus,
  CommissionAdjustmentService,
} from '../../../../../core/services/commission-adjustment.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';

/**
 * Phase 8 §B — supervisor surface. DRAFT + APPROVED queue with approve,
 * commit, and void row actions. Four-eyes invariant is enforced server-side
 * (same actor cannot approve their own draft — surfaces as 409).
 */
@Component({
  selector: 'app-adjustments-approver-queue',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './adjustments-approver-queue.component.html',
  styleUrl: './adjustments.component.scss',
})
export class AdjustmentsApproverQueueComponent implements OnInit {
  rows: Adjustment[] = [];
  loading = false;
  errorMessage: string | null = null;
  statusFilter: '' | AdjustmentStatus = '';

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;

  actionInProgress: Record<string, boolean> = {};

  // Void modal state
  voidTargetId: string | null = null;
  voidReason = '';
  voidSubmitting = false;
  voidError: string | null = null;

  readonly statusOptions = [
    { value: '',         label: 'All (DRAFT + APPROVED)' },
    { value: 'DRAFT',    label: 'Draft' },
    { value: 'APPROVED', label: 'Approved' },
  ];

  constructor(private svc: CommissionAdjustmentService) {}

  ngOnInit(): void { this.fetchPage(); }

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
          || 'Failed to load queue';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.page = 1; this.fetchPage(); }

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
}
