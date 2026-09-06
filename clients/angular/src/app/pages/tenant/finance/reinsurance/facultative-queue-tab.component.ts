import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CessionRow,
  FacultativeQueueStatus,
  ReinsuranceService,
} from '../../../../core/services/reinsurance.service';
import { PermissionService } from '../../../../core/security/permission.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

/**
 * Cession-queue tab of the merged Facultative page. DRAFT / APPROVED
 * cessions the supervisor works through. Row actions gated on
 * {@code finance.reinsurance:approve_facultative}; view-only users see
 * the queue in read-only mode.
 */
@Component({
  selector: 'app-facultative-queue-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent],
  templateUrl: './facultative-queue-tab.component.html',
  styleUrl: './facultative-queue-tab.component.scss',
})
export class FacultativeQueueTabComponent implements OnInit {
  rows: CessionRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  statusFilter: '' | FacultativeQueueStatus = '';

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;

  voidTargetId: string | null = null;
  voidReason = '';
  voidSubmitting = false;
  voidError: string | null = null;

  actionInProgress: Record<string, boolean> = {};

  readonly canAct: boolean;

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All (DRAFT + APPROVED)' },
    { value: 'DRAFT', label: 'Draft' },
    { value: 'APPROVED', label: 'Approved' },
  ];

  constructor(
    private svc: ReinsuranceService,
    perms: PermissionService,
  ) {
    this.canAct = perms.hasAny(['finance.reinsurance:approve_facultative']);
  }

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.svc.listFacultativeQueue(
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
          || 'Failed to load facultative queue';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.page = 1; this.fetchPage(); }

  approve(row: CessionRow): void {
    if (this.actionInProgress[row.id]) return;
    this.actionInProgress[row.id] = true;
    this.svc.approveFacultativeCession(row.id).subscribe({
      next: () => { this.actionInProgress[row.id] = false; this.fetchPage(); },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Approve failed.';
        this.actionInProgress[row.id] = false;
      },
    });
  }

  commit(row: CessionRow): void {
    if (this.actionInProgress[row.id]) return;
    this.actionInProgress[row.id] = true;
    this.svc.commitFacultativeCession(row.id).subscribe({
      next: () => { this.actionInProgress[row.id] = false; this.fetchPage(); },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Commit failed.';
        this.actionInProgress[row.id] = false;
      },
    });
  }

  openVoid(row: CessionRow): void {
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
    if (!this.voidReason.trim()) {
      this.voidError = 'Reason is required.';
      return;
    }
    this.voidSubmitting = true;
    this.voidError = null;
    this.svc.voidFacultativeCession(this.voidTargetId, this.voidReason.trim()).subscribe({
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
