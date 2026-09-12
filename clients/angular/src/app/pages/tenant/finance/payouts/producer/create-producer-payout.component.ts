import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { FinanceService, TenantBankAccount } from '../../../../../core/services/finance.service';
import { ProducerPayoutService } from '../../../../../core/services/producer-payout.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';

/**
 * Phase 11 §A Phase 6 — draft a PRODUCER-typed payment run. The server
 * snaps periodStart to 1st-of-month and periodEnd to last-day-of-month
 * (feedback_effective_date_snap); UI mirrors the shared form grammar used
 * by /tenant/admin/producers/new.
 */
@Component({
  selector: 'app-create-producer-payout',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './create-producer-payout.component.html',
  styleUrl: './create-producer-payout.component.scss',
})
export class CreateProducerPayoutComponent implements OnInit {
  private financeService = inject(FinanceService);
  private producerPayoutService = inject(ProducerPayoutService);
  private currencyService = inject(CurrencyService);
  private tenantService = inject(TenantService);
  private router = inject(Router);
  private toast = inject(ToastService);

  banks: TenantBankAccount[] = [];
  allowedCurrencies: TenantCurrencyConfig[] = [];

  sourceBankAccountId = '';
  currencyCode = '';
  periodStart = '';
  periodEnd = '';
  description = '';

  submitting = false;
  errorMessage: string | null = null;

  get bankOptions(): SelectOption[] {
    return this.banks.map(b => ({
      value: b.id,
      label: `${b.label} (${b.currencyCode})`,
      description: b.accountName,
    }));
  }

  get currencyOptions(): SelectOption[] {
    return this.allowedCurrencies.map(c => ({
      value: c.currencyCode,
      label: c.currencyCode,
      description: c.isDefault ? 'Default' : undefined,
    }));
  }

  get isValid(): boolean {
    return !!(this.sourceBankAccountId && this.currencyCode
      && this.periodStart && this.periodEnd);
  }

  ngOnInit(): void {
    this.financeService.listTenantBankAccounts().subscribe({
      next: banks => { this.banks = banks.filter(b => b.active); },
      error: () => { this.banks = []; },
    });

    const tenantId = this.tenantService.getTenantId();
    if (tenantId) {
      this.currencyService.listForTenant(tenantId).subscribe({
        next: configs => {
          this.allowedCurrencies = configs.filter(c => c.isActive && c.isPaymentCurrency);
          const def = configs.find(c => c.isDefault);
          if (!this.currencyCode && def) this.currencyCode = def.currencyCode;
        },
      });
    }
  }

  onBankChange(): void {
    // Pre-fill currency from the picked bank if the user has not chosen one yet.
    const bank = this.banks.find(b => b.id === this.sourceBankAccountId);
    if (bank && (!this.currencyCode || this.currencyCode === '')) {
      this.currencyCode = bank.currencyCode;
    }
  }

  submit(): void {
    if (!this.isValid) {
      this.errorMessage = 'Bank, currency, and both period dates are required.';
      return;
    }
    this.submitting = true;
    this.errorMessage = null;
    this.producerPayoutService.create({
      currencyCode: this.currencyCode.toUpperCase(),
      description: this.description.trim() || undefined,
      sourceBankAccountId: this.sourceBankAccountId,
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
    }).subscribe({
      next: run => {
        this.toast.success('Producer payout draft created');
        this.router.navigate(['/tenant/finance/payouts/producer', run.id]);
      },
      error: err => {
        this.submitting = false;
        const detail = err?.error?.detail || err?.error?.title || err?.error?.message
          || 'Failed to create producer payout run';
        this.errorMessage = detail;
        this.toast.error(detail);
      },
    });
  }
}
