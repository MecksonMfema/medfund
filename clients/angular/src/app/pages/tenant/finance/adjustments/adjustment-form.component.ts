import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import {
  AdjustmentType,
  CreateAdjustmentPayload,
  FinanceService,
} from '../../../../core/services/finance.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

type Target = 'provider' | 'member';

@Component({
  selector: 'app-adjustment-form',
  standalone: true,
  imports: [CommonModule, FormsModule, EntityPickerComponent, IconComponent, SelectComponent],
  templateUrl: './adjustment-form.component.html',
  styleUrl: './adjustment-form.component.scss',
})
export class AdjustmentFormComponent implements OnInit {
  currencies: Currency[] = [];
  busy = false;
  errorMessage: string | null = null;

  target: Target = 'provider';
  providerId = '';
  memberId = '';
  adjustmentType: AdjustmentType = 'IN_PAYMENT';
  amount = '';
  currencyCode = '';
  reason = '';

  constructor(
    private currencyService: CurrencyService,
    private finance: FinanceService,
    private router: Router,
  ) {}

  readonly adjustmentTypeOptions: SelectOption[] = [
    { value: 'IN_PAYMENT', label: 'In payment' },
    { value: 'PAYOUT', label: 'Payout' },
    { value: 'NON_CASH_IN', label: 'Non-cash in' },
    { value: 'NON_CASH_OUT', label: 'Non-cash out' },
    { value: 'TAX_WITHHELD', label: 'Tax withheld' },
  ];

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({ value: c.code, label: `${c.code} — ${c.name}` }));
  }

  ngOnInit(): void {
    this.currencyService.listMaster(true).subscribe({
      next: (rows) => {
        this.currencies = rows;
        if (!this.currencyCode && rows.length) this.currencyCode = rows[0].code;
      },
      error: () => { this.currencies = []; },
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
    const payload: CreateAdjustmentPayload = {
      adjustmentType: this.adjustmentType,
      amount: this.amount,
      currencyCode: this.currencyCode,
      reason: this.reason.trim() || undefined,
    };
    if (this.target === 'provider') payload.providerId = this.providerId.trim();
    else payload.memberId = this.memberId.trim();

    this.busy = true;
    this.finance.createAdjustment(payload).subscribe({
      next: (adj) => {
        this.busy = false;
        this.router.navigate(['/tenant/finance/adjustments', adj.id]);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to create adjustment';
        this.busy = false;
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/tenant/finance/adjustments']);
  }
}
