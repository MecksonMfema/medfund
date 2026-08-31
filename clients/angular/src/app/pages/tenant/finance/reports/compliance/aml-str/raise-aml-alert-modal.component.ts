import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RaiseAmlAlertRequest, AmlTransactionType } from '../../../../../../core/services/aml-alert.service';
import { EntityPickerComponent } from '../../../../../../shared/components/entity-picker/entity-picker.component';
import { SelectComponent, SelectOption } from '../../../../../../shared/components/select/select.component';

/**
 * Raise-alert modal for Phase 23 REG8. Front-line staff fill in the
 * transaction context + narrative; the payload is validated client-side
 * (mirrors the backend `RaiseAmlAlertRequest` regex + length constraints)
 * before it hits the wire.
 *
 * <p>member_id / provider_id use the shared debounced search-select
 * (`no_raw_id_inputs` feedback memory) — never a raw UUID text input.
 */
@Component({
  selector: 'app-raise-aml-alert-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, EntityPickerComponent, SelectComponent],
  templateUrl: './raise-aml-alert-modal.component.html',
  styleUrl: './raise-aml-alert-modal.component.scss',
})
export class RaiseAmlAlertModalComponent {
  @Input() open = false;
  @Input() submitting = false;
  @Input() serverError: string | null = null;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<RaiseAmlAlertRequest>();

  transactionRef = '';
  transactionType: AmlTransactionType | '' = '';
  amountNative = '';
  currency = '';
  memberId: string | null = null;
  providerId: string | null = null;
  description = '';

  clientError: string | null = null;

  readonly typeOptions: SelectOption[] = [
    { value: 'PREMIUM', label: 'PREMIUM — contribution / member payment' },
    { value: 'CLAIM_PAYOUT', label: 'CLAIM_PAYOUT — payment to provider or member' },
    { value: 'ADVANCE_PAYMENT', label: 'ADVANCE_PAYMENT — provider prepayment' },
    { value: 'REFUND', label: 'REFUND — money returned to a member' },
    { value: 'COMMISSION', label: 'COMMISSION — producer payout' },
    { value: 'ADJUSTMENT', label: 'ADJUSTMENT — journal / manual correction' },
    { value: 'OTHER', label: 'OTHER — anything else worth flagging' },
  ];

  onTypeChange(v: string): void {
    this.transactionType = (v || '') as AmlTransactionType | '';
  }

  onCancel(): void {
    if (this.submitting) return;
    this.reset();
    this.cancel.emit();
  }

  onSubmit(): void {
    this.clientError = this.validate();
    if (this.clientError) return;
    const payload: RaiseAmlAlertRequest = {
      transactionRef: this.transactionRef.trim(),
      transactionType: this.transactionType as AmlTransactionType,
      amountNative: this.amountNative.trim(),
      currency: this.currency.trim().toUpperCase(),
      memberId: this.memberId || null,
      providerId: this.providerId || null,
      description: this.description.trim(),
    };
    this.submit.emit(payload);
  }

  reset(): void {
    this.transactionRef = '';
    this.transactionType = '';
    this.amountNative = '';
    this.currency = '';
    this.memberId = null;
    this.providerId = null;
    this.description = '';
    this.clientError = null;
  }

  private validate(): string | null {
    if (!this.transactionRef.trim()) return 'Transaction reference is required.';
    if (this.transactionRef.length > 120) return 'Transaction reference must be ≤ 120 characters.';
    if (!this.transactionType) return 'Transaction type is required.';
    const amount = Number(this.amountNative);
    if (!this.amountNative.trim() || Number.isNaN(amount) || amount <= 0) {
      return 'Amount must be a positive number.';
    }
    if (!/^[A-Z]{3}$/.test(this.currency.trim().toUpperCase())) {
      return 'Currency must be a 3-letter ISO code (e.g. ZWL, ZAR, USD).';
    }
    if (this.description.trim().length < 20) {
      return 'Description must be at least 20 characters — regulators expect a narrative.';
    }
    return null;
  }
}
