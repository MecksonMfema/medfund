import { CommonModule } from '@angular/common';
import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EntityPickerComponent } from '../entity-picker/entity-picker.component';

/**
 * Modal for booking a scheme change. Mirrors change-group-modal — wraps
 * EntityPicker so the operator searches by scheme name rather than
 * typing a UUID (feedback_no_raw_id_inputs). Effective-date input snaps
 * to the 1st of the month on blur so the backend's CHECK constraint
 * never trips.
 *
 * <p>Emits {@code submit} with the payload members.service
 * requestSchemeChange expects; the parent owns the HTTP call so the
 * modal stays presentation-only.
 *
 * <p>changeKind is intentionally not user-facing — the backend
 * auto-classifier (UPGRADE / DOWNGRADE / CURRENCY_CHANGE / CROSS_GRADE)
 * routes the ledger adjustment. The type is reserved on the payload for
 * a later admin override surface.
 */
export interface ChangeSchemePayload {
  fromSchemeId: string;
  toSchemeId: string;
  effectiveDate: string;
  reason?: string;
  changeKind?: string;
}

@Component({
  selector: 'app-change-scheme-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, EntityPickerComponent],
  templateUrl: './change-scheme-modal.component.html',
  styleUrl: './change-scheme-modal.component.scss',
})
export class ChangeSchemeModalComponent {
  @Input() memberName = '';
  @Input() currentSchemeName: string | null = null;
  @Input() currentSchemeId: string | null = null;
  @Input() open = false;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<ChangeSchemePayload>();

  toSchemeId = '';
  effectiveDate = this.firstOfMonthOffset(1);
  reason = '';
  error: string | null = null;

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
    if (!this.currentSchemeId) {
      this.error = 'Current scheme is unknown; refresh the member and try again.';
      return;
    }
    if (!this.toSchemeId) {
      this.error = 'Pick a target scheme.';
      return;
    }
    if (this.toSchemeId === this.currentSchemeId) {
      this.error = 'Target scheme must differ from the current scheme.';
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(this.effectiveDate)) {
      this.error = 'Effective date must be YYYY-MM-DD.';
      return;
    }
    this.submit.emit({
      fromSchemeId: this.currentSchemeId,
      toSchemeId: this.toSchemeId,
      effectiveDate: this.effectiveDate,
      reason: this.reason.trim() || undefined,
    });
  }

  onCancel(): void { this.cancel.emit(); }

  @HostListener('document:keydown.escape')
  onEscape(): void { if (this.open) this.onCancel(); }

  reset(): void {
    this.toSchemeId = '';
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
