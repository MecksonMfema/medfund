import { CommonModule } from '@angular/common';
import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EntityPickerComponent } from '../entity-picker/entity-picker.component';

/**
 * Modal for booking a group change (V048). Wraps EntityPicker so the
 * operator searches by group name rather than typing a UUID
 * (feedback_no_raw_id_inputs). Effective-date input is snapped to
 * the 1st of the month on blur; the modal never emits a mid-month
 * date.
 *
 * <p>Emits {@code submit} with the payload the members.service
 * requestGroupChange method expects. The parent owns the HTTP call
 * so the modal stays presentation-only.
 */
export interface ChangeGroupPayload {
  targetGroupId: string;
  effectiveDate: string;
  reason?: string;
}

@Component({
  selector: 'app-change-group-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, EntityPickerComponent],
  templateUrl: './change-group-modal.component.html',
  styleUrl: './change-group-modal.component.scss',
})
export class ChangeGroupModalComponent {
  @Input() memberName = '';
  @Input() currentGroupName: string | null = null;
  @Input() open = false;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<ChangeGroupPayload>();

  targetGroupId = '';
  effectiveDate = this.firstOfMonthOffset(1);
  reason = '';
  error: string | null = null;

  /** Snap mid-month picks to day 1 so the backend's CHECK constraint
   *  never trips on a "day = 1" violation. */
  onEffectiveDateChange(): void {
    if (!this.effectiveDate) return;
    const parts = this.effectiveDate.split('-');
    if (parts.length === 3) {
      this.effectiveDate = `${parts[0]}-${parts[1]}-01`;
    }
  }

  isBackdated(): boolean {
    if (!this.effectiveDate) return false;
    const first = this.firstOfMonthOffset(0);
    return this.effectiveDate < first;
  }

  onSubmit(): void {
    this.error = null;
    if (!this.targetGroupId) {
      this.error = 'Pick a target group.';
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(this.effectiveDate)) {
      this.error = 'Effective date must be YYYY-MM-DD.';
      return;
    }
    this.submit.emit({
      targetGroupId: this.targetGroupId,
      effectiveDate: this.effectiveDate,
      reason: this.reason.trim() || undefined,
    });
  }

  onCancel(): void { this.cancel.emit(); }

  @HostListener('document:keydown.escape')
  onEscape(): void { if (this.open) this.onCancel(); }

  /** Reset the form when the parent closes the modal so a re-open
   *  presents a clean form instead of the last-typed values. */
  reset(): void {
    this.targetGroupId = '';
    this.effectiveDate = this.firstOfMonthOffset(1);
    this.reason = '';
    this.error = null;
  }

  private firstOfMonthOffset(offset: number): string {
    const d = new Date();
    d.setDate(1);
    d.setMonth(d.getMonth() + offset);
    return d.toISOString().slice(0, 10);
  }
}
