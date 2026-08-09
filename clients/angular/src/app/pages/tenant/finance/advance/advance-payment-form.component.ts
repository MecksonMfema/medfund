import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { BillingCatalogueService, PaymentMethod } from '../../../../core/services/billing-catalogue.service';
import { TenantService } from '../../../../core/services/tenant.service';
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
  imports: [CommonModule, FormsModule, RouterLink, EntityPickerComponent, IconComponent, SelectComponent],
  templateUrl: './advance-payment-form.component.html',
  styleUrl: './advance-payment-form.component.scss',
})
export class AdvancePaymentFormComponent implements OnInit {
  currencies: TenantCurrencyConfig[] = [];
  paymentMethods: PaymentMethod[] = [];
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
    private catalogue: BillingCatalogueService,
    private tenantService: TenantService,
    private finance: FinanceService,
    private router: Router,
  ) {}

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({ value: c.currencyCode, label: c.currencyCode }));
  }

  get paymentMethodOptions(): SelectOption[] {
    return this.paymentMethods.map(p => ({
      value: p.code,
      label: p.requiresReference ? `${p.label} (requires reference)` : p.label,
    }));
  }

  get selectedMethodRequiresReference(): boolean {
    const m = this.paymentMethods.find(p => p.code === this.paymentMethod);
    return !!m?.requiresReference;
  }

  ngOnInit(): void {
    const tenant = this.tenantService.getTenant();
    if (!tenant) return;
    forkJoin({
      currencies: this.currencyService.listForTenant(tenant.id).pipe(catchError(() => of<TenantCurrencyConfig[]>([]))),
      methods:    this.catalogue.listPaymentMethods(true).pipe(catchError(() => of<PaymentMethod[]>([]))),
    }).subscribe(({ currencies, methods }) => {
      // Payment currencies only. Fall back to any active currency if the
      // tenant hasn't tagged a payment currency yet, so the picker isn't
      // left empty for freshly-provisioned tenants.
      this.currencies = currencies.filter(c => c.isActive && (c.isPaymentCurrency || currencies.every(r => !r.isPaymentCurrency)));
      if (!this.currencyCode) {
        const def = this.currencies.find(c => c.isDefault) ?? this.currencies[0];
        if (def) this.currencyCode = def.currencyCode;
      }
      this.paymentMethods = methods;
    });
  }

  submit(): void {
    if (!this.amount || !this.currencyCode) {
      this.errorMessage = 'Amount and currency are required';
      return;
    }
    if (this.target === 'provider' && !this.providerId.trim()) {
      this.errorMessage = 'Provider is required';
      return;
    }
    if (this.target === 'member' && !this.memberId.trim()) {
      this.errorMessage = 'Member is required';
      return;
    }
    if (this.selectedMethodRequiresReference && !this.reference.trim()) {
      this.errorMessage = 'Reference is required for the selected payment method';
      return;
    }
    const payload: CreateAdvancePaymentPayload = {
      amount: this.amount,
      currencyCode: this.currencyCode,
      paymentMethod: this.paymentMethod || undefined,
      reference: this.reference.trim() || undefined,
      comment: this.comment.trim() || undefined,
    };
    if (this.target === 'provider') payload.providerId = this.providerId.trim();
    else payload.memberId = this.memberId.trim();

    this.busy = true;
    this.finance.createAdvancePayment(payload).subscribe({
      next: (saved) => {
        this.busy = false;
        // Above-threshold advances land in 'pending'. Route back to the list
        // with a state flag the list can surface as a banner, so the recorder
        // isn't left staring at a blank form wondering if it worked.
        const state = saved.status === 'pending'
          ? { advanceBanner: { kind: 'info', text: `Recorded and awaiting approval. Advance ${saved.reference || saved.id.substring(0, 8)} will apply once approved by a different operator.` } }
          : { advanceBanner: { kind: 'success', text: `Advance recorded and auto-approved (${saved.reference || saved.id.substring(0, 8)}).` } };
        this.router.navigate(['/tenant/finance/payments/advance'], { state });
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
