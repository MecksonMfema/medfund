import { CommonModule } from '@angular/common';
import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { endOfMonth, endOfMonthOffset } from '../../utils/date-snap';

/**
 * Modal for terminating a group. Termination effective dates snap to the
 * **last day of the month** on blur — the outgoing group stays billable
 * through the whole cycle it ends in (feedback_effective_date_snap).
 *
 * <p>Emits {@code submit} with the payload {@code GroupsService.terminate}
 * expects. Parent owns the HTTP call so the modal stays presentation-only.
 * Mirrors the change-group-modal layout so operators don't have to
 * relearn the shape.
 */
export interface TerminateGroupPayload {
  effectiveDate: string;
  reason?: string;
}

@Component({
  selector: 'app-terminate-group-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './terminate-group-modal.component.html',
  styleUrl: './terminate-group-modal.component.scss',
})
export class TerminateGroupModalComponent {
  @Input() groupName = '';
  @Input() open = false;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<TerminateGroupPayload>();

  // Default to end of *this* month — matches the operator expectation of
  // "close out the current cycle" for the most common termination.
  effectiveDate = endOfMonthOffset(0);
  reason = '';
  error: string | null = null;

  /** Snap to the last day of the month on blur. */
  onEffectiveDateChange(): void {
    this.effectiveDate = endOfMonth(this.effectiveDate);
  }

  onSubmit(): void {
    this.error = null;
    // Belt-and-braces snap in case the operator typed the date.
    const snapped = endOfMonth(this.effectiveDate);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(snapped)) {
      this.error = 'Effective date must be YYYY-MM-DD.';
      return;
    }
    this.submit.emit({
      effectiveDate: snapped,
      reason: this.reason.trim() || undefined,
    });
  }

  onCancel(): void { this.cancel.emit(); }

  @HostListener('document:keydown.escape')
  onEscape(): void { if (this.open) this.onCancel(); }

  /** Reset when the parent closes the modal so a re-open presents a
   *  clean form instead of the last-typed values. */
  reset(): void {
    this.effectiveDate = endOfMonthOffset(0);
    this.reason = '';
    this.error = null;
  }
}
