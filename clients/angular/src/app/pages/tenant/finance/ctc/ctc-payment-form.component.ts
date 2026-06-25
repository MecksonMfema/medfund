import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import {
  CreateCtcPaymentPayload,
  FinanceService,
} from '../../../../core/services/finance.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

type Target = 'group' | 'member';

@Component({
  selector: 'app-ctc-payment-form',
  standalone: true,
  imports: [CommonModule, FormsModule, EntityPickerComponent, IconComponent, SelectComponent],
  templateUrl: './ctc-payment-form.component.html',
  styleUrl: './ctc-payment-form.component.scss',
})
export class CtcPaymentFormComponent implements OnInit {
  currencies: Currency[] = [];
  busy = false;
  errorMessage: string | null = null;

  target: Target = 'group';
  groupId = '';
  memberId = '';
  amount = '';
  currencyCode = '';
  contributionId = '';

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
    if (this.target === 'group' && !this.groupId.trim()) {
      this.errorMessage = 'Group id is required';
      return;
    }
    if (this.target === 'member' && !this.memberId.trim()) {
      this.errorMessage = 'Member id is required';
      return;
    }
    const payload: CreateCtcPaymentPayload = {
      amount: this.amount,
      currencyCode: this.currencyCode,
      contributionId: this.contributionId.trim() || undefined,
    };
    if (this.target === 'group') payload.groupId = this.groupId.trim();
    else payload.memberId = this.memberId.trim();

    this.busy = true;
    this.finance.createCtcPayment(payload).subscribe({
      next: () => {
        this.busy = false;
        this.router.navigate(['/tenant/finance/payments/ctc']);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to create CTC payment';
        this.busy = false;
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/tenant/finance/payments/ctc']);
  }
}
