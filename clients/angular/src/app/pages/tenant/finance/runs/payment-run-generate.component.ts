import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import { FinanceService, PayeeType } from '../../../../core/services/finance.service';
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
    if (!this.currencyCode) {
      this.errorMessage = 'Pick a currency';
      return;
    }
    this.busy = true;
    this.finance.createRun({
      currencyCode: this.currencyCode,
      description: this.description.trim() || undefined,
      payeeType: this.payeeType,
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
