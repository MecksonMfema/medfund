import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ContributionsService, RecordTransactionPayload } from '../../../../core/services/contributions.service';
import {
  BillingCatalogueService,
  PaymentMethod,
  TransactionType,
} from '../../../../core/services/billing-catalogue.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-transaction-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './transaction-form.component.html',
  styleUrl: './transaction-form.component.scss',
})
export class TransactionFormComponent implements OnInit {
  paymentMethods: PaymentMethod[] = [];
  transactionTypes: TransactionType[] = [];
  currencies: TenantCurrencyConfig[] = [];

  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  form = {
    contributionId: '',
    invoiceId: '',
    amount: '',
    currencyCode: '',
    transactionTypeCode: '',
    paymentMethodCode: '',
    reference: '',
  };

  constructor(
    private catalogue: BillingCatalogueService,
    private currencyService: CurrencyService,
    private contributions: ContributionsService,
    private tenantService: TenantService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    forkJoin({
      payments:     this.catalogue.listPaymentMethods(true),
      transactions: this.catalogue.listTransactionTypes(true),
      currencies:   this.currencyService.listForTenant(tenantId),
    }).subscribe({
      next: ({ payments, transactions, currencies }) => {
        this.paymentMethods = payments;
        this.transactionTypes = transactions;
        this.currencies = currencies.filter(c => c.isActive && c.isPaymentCurrency);
        const def = this.currencies.find(c => c.isDefault);
        if (!this.form.currencyCode && def) this.form.currencyCode = def.currencyCode;
      },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load form data'; },
    });
  }

  selectedMethod(): PaymentMethod | undefined {
    return this.paymentMethods.find(p => p.code === this.form.paymentMethodCode);
  }

  submit(): void {
    if (!this.form.amount || !this.form.currencyCode || !this.form.transactionTypeCode) {
      this.errorMessage = 'Amount, currency, and transaction type are required';
      return;
    }
    if (!this.form.contributionId && !this.form.invoiceId) {
      this.errorMessage = 'Provide either a contribution id or an invoice id';
      return;
    }
    const method = this.selectedMethod();
    if (method?.requiresReference && !this.form.reference.trim()) {
      this.errorMessage = `${method.label} requires a reference`;
      return;
    }

    const payload: RecordTransactionPayload = {
      contributionId: this.form.contributionId || undefined,
      invoiceId: this.form.invoiceId || undefined,
      amount: this.form.amount,
      currencyCode: this.form.currencyCode,
      transactionType: this.form.transactionTypeCode,
      paymentMethod: this.form.paymentMethodCode || undefined,
      reference: this.form.reference.trim() || undefined,
    };

    this.saving = true;
    this.errorMessage = null;
    this.contributions.recordTransaction(payload).subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Transaction recorded';
        setTimeout(() => this.router.navigate(['/tenant/billing/transactions']), 800);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
