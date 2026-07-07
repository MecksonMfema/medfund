import { CommonModule } from '@angular/common';
import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { endOfMonth, endOfMonthOffset } from '../../utils/date-snap';

/**
 * Modal for deactivating a dependant (V046 terminal soft-transition).
 * Dependants are never hard-deleted — billing continues UP TO AND
 * INCLUDING the cycle that contains the effective date. The date snaps
 * to the **last day of the month** on blur so the dependant remains
 * billable for the whole current cycle (feedback_effective_date_snap).
 *
 * <p>Emits {@code submit} with the payload
 * {@code MembersService.deactivateDependant} expects. Parent owns the
 * HTTP call so the modal stays presentation-only.
 */
export interface DeactivateDependantPayload {
  effectiveDate: string;
}

@Component({
  selector: 'app-deactivate-dependant-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './deactivate-dependant-modal.component.html',
  styleUrl: './deactivate-dependant-modal.component.scss',
})
export class DeactivateDependantModalComponent {
  @Input() dependantName = '';
  @Input() open = false;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<DeactivateDependantPayload>();

  // Default to end of this month.
  effectiveDate = endOfMonthOffset(0);
  error: string | null = null;

  /** Snap to the last day of the month on blur. */
  onEffectiveDateChange(): void {
    this.effectiveDate = endOfMonth(this.effectiveDate);
  }

  onSubmit(): void {
    this.error = null;
    const snapped = endOfMonth(this.effectiveDate);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(snapped)) {
      this.error = 'Effective date must be YYYY-MM-DD.';
      return;
    }
    this.submit.emit({ effectiveDate: snapped });
  }

  onCancel(): void { this.cancel.emit(); }

  @HostListener('document:keydown.escape')
  onEscape(): void { if (this.open) this.onCancel(); }

  reset(): void {
    this.effectiveDate = endOfMonthOffset(0);
    this.error = null;
  }
}
