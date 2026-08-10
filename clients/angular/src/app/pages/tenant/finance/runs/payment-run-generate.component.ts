import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import {
  FinanceService,
  PayeeType,
  TenantBankAccount,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

@Component({
  selector: 'app-payment-run-generate',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent],
  templateUrl: './payment-run-generate.component.html',
  styleUrl: './payment-run-generate.component.scss',
})
export class PaymentRunGenerateComponent implements OnInit {
  currencies: Currency[] = [];
  currencyCode = '';
  /** Homogeneous per run — the V072 trigger rejects mixed-payee items. */
  payeeType: PayeeType = 'PROVIDER';
  description = '';
  busy = false;
  errorMessage: string | null = null;

  // V075 — source bank account. Filtered by currencyCode; cleared when the
  // currency changes so the user can't submit a mismatched pair.
  bankAccounts: TenantBankAccount[] = [];
  sourceBankAccountId = '';

  readonly payeeTypeOptions: SelectOption[] = [
    { value: 'PROVIDER', label: 'Providers' },
    { value: 'MEMBER',   label: 'Members' },
  ];

  constructor(
    private currencyService: CurrencyService,
    private finance: FinanceService,
    private router: Router,
  ) {}

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({ value: c.code, label: `${c.code} — ${c.name}` }));
  }

  get bankAccountOptions(): SelectOption[] {
    return this.bankAccounts
      .filter(b => b.active && b.currencyCode === this.currencyCode)
      .map(b => ({
        value: b.id,
        label: b.nominated ? `${b.label} (nominated)` : b.label,
        description: `${b.bankName} · ${b.accountNumber}`,
      }));
  }

  ngOnInit(): void {
    this.currencyService.listMaster(true).subscribe({
      next: (rows) => {
        this.currencies = rows;
        if (!this.currencyCode && rows.length) this.currencyCode = rows[0].code;
        this.onCurrencyChange();
      },
      error: () => { this.currencies = []; },
    });
    this.finance.listTenantBankAccounts().subscribe({
      next: (rows) => {
        this.bankAccounts = rows;
        this.autoSelectNominated();
      },
      error: () => { this.bankAccounts = []; },
    });
  }

  onCurrencyChange(): void {
    // Currency change invalidates the picked bank; default to the nominated
    // account for the new currency if one exists.
    this.sourceBankAccountId = '';
    this.autoSelectNominated();
  }

  private autoSelectNominated(): void {
    if (!this.currencyCode) return;
    const nominated = this.bankAccounts.find(
      b => b.active && b.currencyCode === this.currencyCode && b.nominated,
    );
    if (nominated) this.sourceBankAccountId = nominated.id;
  }

  submit(): void {
    if (!this.currencyCode) {
      this.errorMessage = 'Pick a currency';
      return;
    }
    if (!this.sourceBankAccountId) {
      this.errorMessage = 'Pick a source bank account';
      return;
    }
    this.busy = true;
    this.finance.createRun({
      currencyCode: this.currencyCode,
      description: this.description.trim() || undefined,
      payeeType: this.payeeType,
      sourceBankAccountId: this.sourceBankAccountId,
    }).subscribe({
      next: (run) => {
        this.busy = false;
        this.router.navigate(['/tenant/finance/runs', run.id]);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to create payment run';
        this.busy = false;
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/tenant/finance/runs']);
  }
}
