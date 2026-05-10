import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import { FinanceService } from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-reconciliation-form',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './reconciliation-form.component.html',
  styleUrl: './reconciliation-form.component.scss',
})
export class ReconciliationFormComponent implements OnInit {
  currencies: Currency[] = [];
  busy = false;
  errorMessage: string | null = null;

  referenceNumber = '';
  statementAmount = '';
  currencyCode = '';
  statementDate = new Date().toISOString().slice(0, 10);
  notes = '';

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
    if (!this.referenceNumber.trim() || !this.statementAmount || !this.currencyCode || !this.statementDate) {
      this.errorMessage = 'Reference, amount, currency and statement date are all required';
      return;
    }
    this.busy = true;
    this.finance.createReconciliation({
      referenceNumber: this.referenceNumber.trim(),
      statementAmount: this.statementAmount,
      currencyCode: this.currencyCode,
      statementDate: this.statementDate,
      notes: this.notes.trim() || undefined,
    }).subscribe({
      next: () => {
        this.busy = false;
        this.router.navigate(['/tenant/finance/reconciliations']);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to create reconciliation';
        this.busy = false;
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/tenant/finance/reconciliations']);
  }
}
