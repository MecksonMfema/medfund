import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import { FinanceService } from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-payment-run-generate',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './payment-run-generate.component.html',
  styleUrl: './payment-run-generate.component.scss',
})
export class PaymentRunGenerateComponent implements OnInit {
  currencies: Currency[] = [];
  currencyCode = '';
  description = '';
  busy = false;
  errorMessage: string | null = null;

  constructor(
    private currencyService: CurrencyService,
    private finance: FinanceService,
    private router: Router,
  ) {}

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
