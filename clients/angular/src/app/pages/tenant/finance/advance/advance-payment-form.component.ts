import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import {
  CreateAdvancePaymentPayload,
  FinanceService,
} from '../../../../core/services/finance.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

type Target = 'provider' | 'member';

@Component({
  selector: 'app-advance-payment-form',
  standalone: true,
  imports: [CommonModule, FormsModule, EntityPickerComponent, IconComponent, SelectComponent],
  templateUrl: './advance-payment-form.component.html',
  styleUrl: './advance-payment-form.component.scss',
})
export class AdvancePaymentFormComponent implements OnInit {
  currencies: Currency[] = [];
  busy = false;
  errorMessage: string | null = null;

  target: Target = 'provider';
  providerId = '';
  memberId = '';
  amount = '';
  currencyCode = '';
  paymentMethod = '';
  reference = '';
  comment = '';

  constructor(
    private currencyService: CurrencyService,
    private finance: FinanceService,
    private router: Router,
  ) {}

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({ value: c.code, label: `${c.code} — ${c.name}` }));
  }

  ngOnInit(): void {
    this.currencyService.listMaster(true).subscribe({
      next: (rows) => {
        this.currencies = rows;
        if (!this.currencyCode && rows.length) this.currencyCode = rows[0].code;
      },
      error: () => {},
    });
  }

  submit(): void {
    if (!this.amount || !this.currencyCode) {
      this.errorMessage = 'Amount and currency are required';
      return;
    }
    if (this.target === 'provider' && !this.providerId.trim()) {
      this.errorMessage = 'Provider id is required';
      return;
    }
    if (this.target === 'member' && !this.memberId.trim()) {
      this.errorMessage = 'Member id is required';
      return;
    }
    const payload: CreateAdvancePaymentPayload = {
      amount: this.amount,
      currencyCode: this.currencyCode,
      paymentMethod: this.paymentMethod.trim() || undefined,
      reference: this.reference.trim() || undefined,
      comment: this.comment.trim() || undefined,
    };
    if (this.target === 'provider') payload.providerId = this.providerId.trim();
    else payload.memberId = this.memberId.trim();

    this.busy = true;
    this.finance.createAdvancePayment(payload).subscribe({
      next: () => {
        this.busy = false;
        this.router.navigate(['/tenant/finance/payments/advance']);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to record advance payment';
        this.busy = false;
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/tenant/finance/payments/advance']);
  }
}
