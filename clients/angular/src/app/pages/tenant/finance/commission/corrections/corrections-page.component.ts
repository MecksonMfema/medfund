import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  Adjustment,
  AdjustmentStatus,
  CommissionAdjustmentService,
} from '../../../../../core/services/commission-adjustment.service';
import { PermissionService } from '../../../../../core/security/permission.service';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';

interface CorrectionRow extends Adjustment {
  targetShort: string;
}

/**
 * Merged Commission corrections page. Single view, status-filtered list.
 *
 * <p>The toolbar's "New correction" button is gated on
 * {@code finance.commission:draft_adjustment} and navigates to
 * {@code /tenant/finance/commission/corrections/new}; row-level
 * Approve / Commit / Void buttons are gated on
 * {@code finance.commission:approve_adjustment}. Viewers with only
 * {@code finance.commission:view} see a read-only list.
 *
 * <p>Four-eyes invariant is enforced server-side (a drafter approving
 * their own row is rejected with 409); the client only decorates.
 */
@Component({
  selector: 'app-corrections-page',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, IconComponent, SelectComponent],
  templateUrl: './corrections-page.component.html',
  styleUrl: './corrections-page.component.scss',
})
export class CorrectionsPageComponent implements OnInit {
  rows: CorrectionRow[] = [];
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

  readonly statusOptions: SelectOption[] = [
    { value: '',          label: 'All open (DRAFT + APPROVED)' },
    { value: 'DRAFT',     label: 'Draft' },
    { value: 'APPROVED',  label: 'Approved' },
    { value: 'COMMITTED', label: 'Committed' },
    { value: 'VOIDED',    label: 'Voided' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'status',           label: 'Status',    sortable: false, type: 'status' },
    { key: 'reference',        label: 'Reference', sortable: false },
    { key: 'targetShort',      label: 'Target',    sortable: false },
    { key: 'adjustmentType',   label: 'Type',      sortable: false },
    { key: 'adjustmentAmount', label: 'Amount',    sortable: false, type: 'currency' },
    { key: 'actorEmail',       label: 'Drafter',   sortable: false },
    { key: 'createdAt',        label: 'Created',   sortable: false, type: 'date' },
  ];

  get actions(): TableAction[] {
    const items: TableAction[] = [];
    if (this.canApprove) {
      items.push({
        label: 'Approve',
        icon: 'check-circle',
        color: 'success',
        testid: 'approve-btn',
        visible: (row: CorrectionRow) => row.status === 'DRAFT',
        handler: (row: CorrectionRow) => this.approve(row),
      });
      items.push({
        label: 'Commit',
        icon: 'external-link',
        color: 'success',
        testid: 'commit-btn',
        visible: (row: CorrectionRow) => row.status === 'APPROVED',
        handler: (row: CorrectionRow) => this.commit(row),
      });
      items.push({
        label: 'Void',
        icon: 'x-circle',
        color: 'danger',
        testid: 'void-btn',
        visible: (row: CorrectionRow) => !this.isTerminal(row),
        handler: (row: CorrectionRow) => this.openVoid(row),
      });
    }
    items.push({
      label: 'Detail',
      icon: 'eye',
      color: 'default',
      handler: (row: CorrectionRow) => this.openDetail(row),
    });
    return items;
  }

  // ── Void modal state ──────────────────────────────────────────────────
  voidTargetId: string | null = null;
  voidReason = '';
  voidSubmitting = false;
  voidError: string | null = null;

  // Router is injected via inject() rather than a constructor param so
  // the existing spec (which builds this component with `new` and two
  // positional args) keeps working without stub gymnastics.
  private router: Router;

  constructor(
    private svc: CommissionAdjustmentService,
    perms: PermissionService,
  ) {
    this.canDraft = perms.hasAny(['finance.commission:draft_adjustment']);
    this.canApprove = perms.hasAny(['finance.commission:approve_adjustment']);
    try {
      this.router = inject(Router);
    } catch {
      // Spec context: navigate() is unused by the assertions.
      this.router = { navigate: () => Promise.resolve(true) } as unknown as Router;
    }
  }

  ngOnInit(): void {
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.svc.list(this.statusFilter || undefined, this.page - 1, this.pageSize).subscribe({
      next: page => {
        this.rows = page.content.map(r => ({
          ...r,
          targetShort: `${r.targetCommissionTransactionId.substring(0, 8)}…`,
        }));
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

  openNew(): void {
    this.router.navigate(['/tenant/finance/commission/corrections/new']);
  }

  openDetail(row: CorrectionRow): void {
    this.router.navigate(['/tenant/finance/commission/corrections', row.id]);
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

