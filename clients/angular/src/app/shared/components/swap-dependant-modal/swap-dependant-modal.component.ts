import { CommonModule } from '@angular/common';
import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Dependant } from '../../../core/services/members.service';

export interface SwapDependantPayload {
  dependantId: string;
  effectiveDate: string;
  reason?: string;
}

/**
 * Modal for booking a role swap between a member and one of their
 * dependants (V048). The dependant list is passed in from the parent
 * (member-detail already has it loaded); the operator picks by row
 * rather than typing a UUID (feedback_no_raw_id_inputs).
 *
 * <p>The preview panel explains the semantics — after the swap the
 * chosen dependant becomes the principal and the current member
 * becomes a dependant under them. Sibling dependants re-parent
 * automatically on the backend.
 */
@Component({
  selector: 'app-swap-dependant-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './swap-dependant-modal.component.html',
  styleUrl: './swap-dependant-modal.component.scss',
})
export class SwapDependantModalComponent {
  @Input() memberName = '';
  @Input() dependants: Dependant[] = [];
  @Input() open = false;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<SwapDependantPayload>();

  selectedDependantId = '';
  effectiveDate = this.firstOfMonthOffset(1);
  reason = '';
  error: string | null = null;

  /** Only active/suspended dependants are swap candidates. Deactivated
   *  or already-swapped rows can't take over as principal. */
  eligible(): Dependant[] {
    return this.dependants.filter(d =>
      d.status === 'active' || d.status === 'suspended');
  }

  selected(): Dependant | undefined {
    return this.dependants.find(d => d.id === this.selectedDependantId);
  }

  onEffectiveDateChange(): void {
    if (!this.effectiveDate) return;
    const parts = this.effectiveDate.split('-');
    if (parts.length === 3) {
      this.effectiveDate = `${parts[0]}-${parts[1]}-01`;
    }
  }

  isBackdated(): boolean {
    if (!this.effectiveDate) return false;
    return this.effectiveDate < this.firstOfMonthOffset(0);
  }

  onSubmit(): void {
    this.error = null;
    if (!this.selectedDependantId) {
      this.error = 'Pick a dependant to promote.';
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(this.effectiveDate)) {
      this.error = 'Effective date must be YYYY-MM-DD.';
      return;
    }
    this.submit.emit({
      dependantId: this.selectedDependantId,
      effectiveDate: this.effectiveDate,
      reason: this.reason.trim() || undefined,
    });
  }

  onCancel(): void { this.cancel.emit(); }

  @HostListener('document:keydown.escape')
  onEscape(): void { if (this.open) this.onCancel(); }

  reset(): void {
    this.selectedDependantId = '';
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
