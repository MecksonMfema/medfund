import { CommonModule } from '@angular/common';
import {
  Component,
  EventEmitter,
  HostListener,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
} from '@angular/core';
import { FormsModule } from '@angular/forms';

export interface ClaimReserveSubmit {
  reservedAmount: string;
  reasonNote: string;
}

/**
 * Phase 14 §A — modal for setting or updating a claim case reserve.
 * Simple amount + reason form; the parent (claim detail) owns the HTTP
 * call, so this component is purely presentational.
 */
@Component({
  selector: 'app-claim-reserve-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './claim-reserve-modal.component.html',
  styleUrl: './claim-reserve-modal.component.scss',
})
export class ClaimReserveModalComponent implements OnChanges {
  @Input() open = false;
  @Input() claimNumber: string | null = null;
  @Input() currencyCode: string | null = null;
  @Input() currentReserve: string | null = null;
  @Input() submitting = false;
  @Input() serverError: string | null = null;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<ClaimReserveSubmit>();

  reservedAmount = '';
  reasonNote = '';
  clientError: string | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open) {
      this.reservedAmount = this.currentReserve ?? '';
      this.reasonNote = '';
      this.clientError = null;
    }
  }

  onSubmit(): void {
    this.clientError = null;
    const amount = Number(this.reservedAmount);
    if (this.reservedAmount === '' || Number.isNaN(amount) || amount < 0) {
      this.clientError = 'Reserve must be a non-negative number.';
      return;
    }
    if (!this.reasonNote || this.reasonNote.trim().length < 5) {
      this.clientError = 'Reason note must be at least 5 characters.';
      return;
    }
    this.submit.emit({
      reservedAmount: amount.toFixed(2),
      reasonNote: this.reasonNote.trim(),
    });
  }

  onCancel(): void {
    this.cancel.emit();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.open && !this.submitting) this.onCancel();
  }
}
