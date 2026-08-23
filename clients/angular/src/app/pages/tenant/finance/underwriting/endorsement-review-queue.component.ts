import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  EndorsementResponse,
  EndorsementService,
  EndorsementStatus,
} from '../../../../core/services/endorsement.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { NavigationService } from '../../../../core/services/navigation.service';
import { PermissionService } from '../../../../core/security/permission.service';

/**
 * Phase 12 §C endorsement approver queue. Mirrors Phase 11's
 * commission-adjustment approver queue verbatim: DRAFT + APPROVED rows
 * with approve / commit / void actions and a void-reason modal. The
 * four-eyes invariant is enforced server-side (approver actor-id ≠
 * drafter actor-id — a 403 on violation); we also disable the Approve
 * button when the current user's email equals the drafter's email so
 * they don't waste a round-trip.
 */
@Component({
  selector: 'app-endorsement-review-queue',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './endorsement-review-queue.component.html',
  styleUrl: './endorsement-review-queue.component.scss',
})
export class EndorsementReviewQueueComponent implements OnInit {
  rows: EndorsementResponse[] = [];
  loading = false;
  errorMessage: string | null = null;
  statusFilter: '' | EndorsementStatus = '';

  page = 0;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;

  actionInProgress: Record<string, boolean> = {};
  currentUserEmail = '';

  // Void modal state (mirrors adjustments queue precedent).
  voidTargetId: string | null = null;
  voidReason = '';
  voidSubmitting = false;
  voidError: string | null = null;

  readonly statusOptions: { value: '' | EndorsementStatus; label: string }[] = [
    { value: '',         label: 'All (DRAFT + APPROVED)' },
    { value: 'DRAFT',    label: 'Draft' },
    { value: 'APPROVED', label: 'Approved' },
  ];

  constructor(
    private svc: EndorsementService,
    private navService: NavigationService,
    private permissionService: PermissionService,
  ) {}

  ngOnInit(): void {
    this.currentUserEmail = this.navService.getUserInfo().email || '';
    this.fetchPage();
  }

  canApprove(): boolean {
    return this.permissionService.has('policy:approve_endorsement');
  }

  isSameActor(row: EndorsementResponse): boolean {
    return !!this.currentUserEmail
      && this.currentUserEmail.toLowerCase() === row.draftActorEmail?.toLowerCase();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.svc.list({
      status: this.statusFilter || undefined,
      page:   this.page,
      size:   this.pageSize,
    }).subscribe({
      next: page => {
        this.rows       = page.content;
        this.totalCount = page.totalElements;
        this.totalPages = page.totalPages || 1;
        this.loading    = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load endorsement queue.';
        this.rows    = [];
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.page = 0; this.fetchPage(); }

  approve(row: EndorsementResponse): void {
    if (this.actionInProgress[row.id] || this.isSameActor(row)) return;
    this.actionInProgress[row.id] = true;
    this.svc.approve(row.id).subscribe({
      next: () => {
        this.actionInProgress[row.id] = false;
        this.fetchPage();
      },
      error: err => {
        this.errorMessage = err?.error?.detail
          || err?.error?.title
          || 'Approve failed.';
        this.actionInProgress[row.id] = false;
      },
    });
  }

  commit(row: EndorsementResponse): void {
    if (this.actionInProgress[row.id]) return;
    this.actionInProgress[row.id] = true;
    this.svc.commit(row.id).subscribe({
      next: () => {
        this.actionInProgress[row.id] = false;
        this.fetchPage();
      },
      error: err => {
        this.errorMessage = err?.error?.detail
          || err?.error?.title
          || 'Commit failed.';
        this.actionInProgress[row.id] = false;
      },
    });
  }

  openVoid(row: EndorsementResponse): void {
    this.voidTargetId = row.id;
    this.voidReason   = '';
    this.voidError    = null;
  }

  cancelVoid(): void {
    this.voidTargetId    = null;
    this.voidReason      = '';
    this.voidError       = null;
    this.voidSubmitting  = false;
  }

  submitVoid(): void {
    if (!this.voidTargetId) return;
    if (!this.voidReason.trim() || this.voidReason.trim().length < 5) {
      this.voidError = 'Reason must be at least 5 characters.';
      return;
    }
    this.voidSubmitting = true;
    this.voidError = null;
    this.svc.void(this.voidTargetId, { reason: this.voidReason.trim() }).subscribe({
      next: () => {
        this.voidSubmitting = false;
        this.cancelVoid();
        this.fetchPage();
      },
      error: err => {
        this.voidError = err?.error?.detail || err?.error?.title
          || 'Void failed.';
        this.voidSubmitting = false;
      },
    });
  }
}
