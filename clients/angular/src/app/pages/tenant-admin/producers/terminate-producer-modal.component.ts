import { CommonModule } from '@angular/common';
import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { endOfMonth, endOfMonthOffset } from '../../../shared/utils/date-snap';

/**
 * Modal for terminating a producer. Termination effective dates snap to the
 * last-day-of-month per {@code feedback_effective_date_snap} — mirrors the
 * shared terminate-group-modal shape so operators don't have to relearn.
 *
 * <p>Presentation-only: emits {@code submit} with the payload
 * {@link ProducerService#terminateProducer} expects. The parent page owns
 * the HTTP call and the follow-on bulk-reassign navigation.</p>
 */
export interface TerminateProducerPayload {
  effectiveDate: string;
}

@Component({
  selector: 'app-terminate-producer-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './terminate-producer-modal.component.html',
  styleUrl: '../../../shared/components/terminate-group-modal/terminate-group-modal.component.scss',
})
export class TerminateProducerModalComponent {
  @Input() producerCode = '';
  @Input() producerName = '';
  @Input() openAssignmentCount: number | null = null;
  @Input() open = false;
  @Input() saving = false;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<TerminateProducerPayload>();

  effectiveDate = endOfMonthOffset(0);
  error: string | null = null;

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
