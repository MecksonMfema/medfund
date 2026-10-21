import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

/**
 * Combined workflow modal for review / file / close transitions on an AML
 * alert. Same shape as the {@link
 * ../../../../../shared/components/policy-status-action-modal
 * PolicyStatusActionModalComponent} — three fields depending on mode:
 * <ul>
 *   <li>{@code review} → reviewNote (≥ 10 chars)</li>
 *   <li>{@code file}   → filedRef (≤ 120 chars) + optional filedXlsxRef</li>
 *   <li>{@code close}  → closedReason (5-120 chars)</li>
 * </ul>
 * The host emits the correct service call; the modal only shapes the input.
 */
export type AmlWorkflowMode = 'review' | 'file' | 'close';

export interface AmlWorkflowSubmit {
  mode: AmlWorkflowMode;
  reviewNote?: string;
  filedRef?: string;
  filedXlsxRef?: string | null;
  closedReason?: string;
}

@Component({
  selector: 'app-aml-workflow-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './aml-workflow-modal.component.html',
  styleUrl: './raise-aml-alert-modal.component.scss',
})
export class AmlWorkflowModalComponent {
  @Input() open = false;
  @Input({ required: true }) mode!: AmlWorkflowMode;
  @Input() transactionRef = '';
  @Input() submitting = false;
  @Input() serverError: string | null = null;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<AmlWorkflowSubmit>();

  reviewNote = '';
  filedRef = '';
  filedXlsxRef = '';
  closedReason = '';

  clientError: string | null = null;

  get title(): string {
    switch (this.mode) {
      case 'review': return 'Review alert';
      case 'file':   return 'File alert with regulator';
      case 'close':  return 'Close alert';
    }
  }

  get description(): string {
    switch (this.mode) {
      case 'review':
        return 'Move this alert to REVIEWED with a triage note. The note is attached to the audit event.';
      case 'file':
        return 'Move this alert to FILED after submitting to the regulator (FIU / FIC / FinCEN). ' +
               'Capture the regulator\'s filing reference so it appears on the audit trail. ' +
               'FILED is terminal: this alert can\'t be closed after filing.';
      case 'close':
        return 'Close this alert as not reportable. Reason is required and appears on the audit ' +
               'trail so the compliance officer can defend the call.';
    }
  }

  get submitLabel(): string {
    switch (this.mode) {
      case 'review': return this.submitting ? 'Reviewing…' : 'Confirm review';
      case 'file':   return this.submitting ? 'Filing…'    : 'Confirm filing';
      case 'close':  return this.submitting ? 'Closing…'   : 'Confirm close';
    }
  }

  onCancel(): void {
    if (this.submitting) return;
    this.reset();
    this.cancel.emit();
  }

  onSubmit(): void {
    this.clientError = this.validate();
    if (this.clientError) return;

    const base: AmlWorkflowSubmit = { mode: this.mode };
    switch (this.mode) {
      case 'review':
        this.submit.emit({ ...base, reviewNote: this.reviewNote.trim() });
        break;
      case 'file':
        this.submit.emit({
          ...base,
          filedRef: this.filedRef.trim(),
          filedXlsxRef: this.filedXlsxRef.trim() || null,
        });
        break;
      case 'close':
        this.submit.emit({ ...base, closedReason: this.closedReason.trim() });
        break;
    }
  }

  reset(): void {
    this.reviewNote = '';
    this.filedRef = '';
    this.filedXlsxRef = '';
    this.closedReason = '';
    this.clientError = null;
  }

  private validate(): string | null {
    switch (this.mode) {
      case 'review':
        if (this.reviewNote.trim().length < 10) {
          return 'Review note must be at least 10 characters.';
        }
        return null;
      case 'file':
        if (!this.filedRef.trim()) return 'Regulator filing reference is required.';
        if (this.filedRef.length > 120) return 'Filing reference must be ≤ 120 characters.';
        return null;
      case 'close': {
        const trimmed = this.closedReason.trim();
        if (trimmed.length < 5) return 'Closed reason must be at least 5 characters.';
        if (trimmed.length > 120) return 'Closed reason must be ≤ 120 characters.';
        return null;
      }
    }
  }
}
