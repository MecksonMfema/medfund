import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  EndorsementResponse,
  EndorsementService,
  EndorsementStatus,
} from '../../../../core/services/endorsement.service';
import { NavigationService } from '../../../../core/services/navigation.service';
import { PermissionService } from '../../../../core/security/permission.service';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../shared/components/data-table/data-table.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

interface EndorsementRow extends EndorsementResponse {
  premiumDeltaNum: number | null;
}

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
  imports: [CommonModule, FormsModule, DataTableComponent, SelectComponent],
  templateUrl: './endorsement-review-queue.component.html',
  styleUrl: './endorsement-review-queue.component.scss',
})
export class EndorsementReviewQueueComponent implements OnInit {
  rows: EndorsementRow[] = [];
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

  readonly statusOptions: SelectOption[] = [
    { value: '',         label: 'All (DRAFT + APPROVED)' },
    { value: 'DRAFT',    label: 'Draft' },
    { value: 'APPROVED', label: 'Approved' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'status',           label: 'Status',      sortable: false, type: 'status' },
    { key: 'reference',        label: 'Reference',   sortable: false },
    { key: 'changeType',       label: 'Change type', sortable: false },
    { key: 'effectiveFrom',    label: 'Effective',   sortable: false, type: 'date' },
    { key: 'premiumDeltaNum',  label: 'Δ premium',   sortable: false, type: 'currency' },
    { key: 'currencyCode',     label: 'Currency',    sortable: false },
    { key: 'draftActorEmail',  label: 'Drafter',     sortable: false },
    { key: 'draftAt',          label: 'Drafted at',  sortable: false, type: 'date' },
  ];

  get actions(): TableAction[] {
    const items: TableAction[] = [];
    if (this.canApprove()) {
      items.push({
        label: 'Approve',
        icon: 'check-circle',
        color: 'success',
        testid: 'approve-btn',
        visible: (row: EndorsementRow) => row.status === 'DRAFT',
        disabled: (row: EndorsementRow) =>
          this.actionInProgress[row.id] === true || this.isSameActor(row),
        titleFor: (row: EndorsementRow) =>
          this.isSameActor(row) ? 'Four-eyes: cannot approve your own draft' : '',
        handler: (row: EndorsementRow) => this.approve(row),
      });
      items.push({
        label: 'Commit',
        icon: 'external-link',
        color: 'success',
        testid: 'commit-btn',
        visible: (row: EndorsementRow) => row.status === 'APPROVED',
        disabled: (row: EndorsementRow) => this.actionInProgress[row.id] === true,
        handler: (row: EndorsementRow) => this.commit(row),
      });
      items.push({
        label: 'Void',
        icon: 'x-circle',
        color: 'danger',
        testid: 'void-btn',
        visible: (row: EndorsementRow) =>
          row.status === 'DRAFT' || row.status === 'APPROVED',
        disabled: (row: EndorsementRow) => this.actionInProgress[row.id] === true,
        handler: (row: EndorsementRow) => this.openVoid(row),
      });
    }
    items.push({
      label: 'Detail',
      icon: 'eye',
      color: 'default',
      handler: (row: EndorsementRow) => this.openDetail(row),
    });
    return items;
  }

  private router = inject(Router);

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
        this.rows = page.content.map(r => ({
          ...r,
          premiumDeltaNum: r.premiumDelta == null ? null : Number(r.premiumDelta),
        }));
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

  openDetail(row: EndorsementResponse): void {
    this.router.navigate(['/tenant/finance/underwriting/endorsements', row.id]);
  }

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
