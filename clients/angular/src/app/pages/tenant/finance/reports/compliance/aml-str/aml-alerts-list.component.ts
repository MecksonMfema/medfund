import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AmlAlertResponse,
  AmlAlertService,
  AmlAlertStatus,
} from '../../../../../../core/services/aml-alert.service';
import { PermissionService } from '../../../../../../core/security/permission.service';
import { IconComponent } from '../../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../../shared/components/select/select.component';
import { RaiseAmlAlertModalComponent } from './raise-aml-alert-modal.component';
import {
  AmlWorkflowModalComponent,
  AmlWorkflowMode,
  AmlWorkflowSubmit,
} from './aml-workflow-modal.component';
import { RaiseAmlAlertRequest } from '../../../../../../core/services/aml-alert.service';

/**
 * Phase 23 REG8 host page for the AML/STR alert workflow. Renders the queue
 * (default active-work slice: RAISED + REVIEWED) with a status filter chip
 * and per-row action buttons that open the shared workflow modal.
 *
 * <p>All four permission gates are respected in-page: the "Raise" button
 * shows only when the caller has {@code compliance:aml_raise}; row-level
 * Review / File / Close buttons show only for their respective permissions.
 * The parent guard already enforced {@code compliance:aml_review} to reach
 * this route at all.
 */
@Component({
  selector: 'app-aml-alerts-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    SelectComponent,
    RaiseAmlAlertModalComponent,
    AmlWorkflowModalComponent,
  ],
  templateUrl: './aml-alerts-list.component.html',
  styleUrl: './aml-alerts-list.component.scss',
})
export class AmlAlertsListComponent implements OnInit {
  readonly pageTitle = 'AML/STR alerts';
  readonly pageSubtitle =
    'Raise, review, file, or close Suspicious Transaction Alerts. Every ' +
    'transition writes an audit event with the friendly txnRef as entityName.';

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'Active work (RAISED + REVIEWED)' },
    { value: 'RAISED', label: 'RAISED only' },
    { value: 'REVIEWED', label: 'REVIEWED only' },
    { value: 'FILED', label: 'FILED (terminal: regulator submitted)' },
    { value: 'CLOSED', label: 'CLOSED (terminal: not reportable)' },
  ];

  statusFilter = '';
  page = 0;
  size = 50;

  loading = false;
  loadError: string | null = null;
  rows: AmlAlertResponse[] = [];
  total = 0;

  // ── Raise modal ────────────────────────────────────────────────────────
  raiseOpen = false;
  raiseSubmitting = false;
  raiseError: string | null = null;

  // ── Workflow modal (review / file / close) ────────────────────────────
  workflowOpen = false;
  workflowMode: AmlWorkflowMode = 'review';
  workflowTarget: AmlAlertResponse | null = null;
  workflowSubmitting = false;
  workflowError: string | null = null;

  constructor(
    private service: AmlAlertService,
    private permissions: PermissionService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  // ── Permission-gated UI flags ──────────────────────────────────────────

  get canRaise():  boolean { return this.permissions.has('compliance:aml_raise'); }
  get canReview(): boolean { return this.permissions.has('compliance:aml_review'); }
  get canFile():   boolean { return this.permissions.has('compliance:aml_file'); }
  get canClose():  boolean { return this.permissions.has('compliance:aml_close'); }

  // ── Loading ────────────────────────────────────────────────────────────

  load(): void {
    this.loading = true;
    this.loadError = null;
    this.service.queue(this.statusFilter || null, this.page, this.size).subscribe({
      next: (page) => {
        this.rows = page.content;
        this.total = page.total;
        this.loading = false;
      },
      error: (err) => {
        this.loadError = err?.error?.detail || err?.error?.title || 'Failed to load alerts';
        this.loading = false;
      },
    });
  }

  onStatusChange(v: string): void {
    this.statusFilter = v;
    this.page = 0;
    this.load();
  }

  // ── Raise flow ─────────────────────────────────────────────────────────

  openRaise(): void {
    this.raiseError = null;
    this.raiseOpen = true;
  }

  cancelRaise(): void {
    this.raiseOpen = false;
    this.raiseError = null;
  }

  submitRaise(body: RaiseAmlAlertRequest): void {
    this.raiseSubmitting = true;
    this.raiseError = null;
    this.service.raise(body).subscribe({
      next: () => {
        this.raiseSubmitting = false;
        this.raiseOpen = false;
        this.load();
      },
      error: (err) => {
        this.raiseSubmitting = false;
        this.raiseError = err?.error?.detail || err?.error?.title || 'Failed to raise alert';
      },
    });
  }

  // ── Workflow flow ──────────────────────────────────────────────────────

  openWorkflow(row: AmlAlertResponse, mode: AmlWorkflowMode): void {
    this.workflowTarget = row;
    this.workflowMode = mode;
    this.workflowError = null;
    this.workflowOpen = true;
  }

  cancelWorkflow(): void {
    this.workflowOpen = false;
    this.workflowTarget = null;
    this.workflowError = null;
  }

  submitWorkflow(payload: AmlWorkflowSubmit): void {
    if (!this.workflowTarget) return;
    const id = this.workflowTarget.id;
    this.workflowSubmitting = true;
    this.workflowError = null;

    const done = () => {
      this.workflowSubmitting = false;
      this.workflowOpen = false;
      this.workflowTarget = null;
      this.load();
    };
    const fail = (err: unknown) => {
      this.workflowSubmitting = false;
      const httpErr = err as { error?: { detail?: string; title?: string } };
      this.workflowError =
        httpErr?.error?.detail || httpErr?.error?.title || 'Workflow transition failed';
    };

    switch (payload.mode) {
      case 'review':
        this.service.review(id, { reviewNote: payload.reviewNote ?? '' })
          .subscribe({ next: done, error: fail });
        break;
      case 'file':
        this.service.file(id, {
          filedRef: payload.filedRef ?? '',
          filedXlsxRef: payload.filedXlsxRef ?? null,
        }).subscribe({ next: done, error: fail });
        break;
      case 'close':
        this.service.close(id, { closedReason: payload.closedReason ?? '' })
          .subscribe({ next: done, error: fail });
        break;
    }
  }

  // ── Row-level guards ───────────────────────────────────────────────────

  canRow(row: AmlAlertResponse, mode: AmlWorkflowMode): boolean {
    switch (mode) {
      case 'review': return this.canReview && row.status === 'RAISED';
      case 'file':   return this.canFile   && row.status === 'REVIEWED';
      case 'close':  return this.canClose  && (row.status === 'RAISED' || row.status === 'REVIEWED');
    }
  }

  statusClass(status: AmlAlertStatus): string {
    return `badge badge-${status.toLowerCase()}`;
  }

  get workflowTxnRef(): string {
    return this.workflowTarget?.transactionRef ?? '';
  }
}
