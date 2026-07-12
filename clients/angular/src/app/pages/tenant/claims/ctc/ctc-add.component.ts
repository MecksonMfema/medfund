import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  CreateCtcPaymentPayload,
  FinanceService,
} from '../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

type Beneficiary = 'member' | 'group';

@Component({
  selector: 'app-ctc-add',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    EntityPickerComponent,
    IconComponent,
    SelectComponent,
  ],
  templateUrl: './ctc-add.component.html',
  styleUrl: './ctc-add.component.scss',
})
export class CtcAddComponent implements OnInit {
  saving = false;
  formError: string | null = null;

  beneficiary: Beneficiary = 'member';
  memberId: string | null = null;
  groupId: string | null = null;

  form = {
    amount: '',
    currencyCode: 'USD',
    contributionId: '',
  };

  currencies: TenantCurrencyConfig[] = [];

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({
      value: c.currencyCode,
      label: c.currencyCode,
      description: c.isDefault ? 'Default' : undefined,
    }));
  }

  constructor(
    private finance: FinanceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private toast: ToastService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const tenant = this.tenantService.getTenant();
    if (tenant) {
      this.currencyService.listForTenant(tenant.id).subscribe({
        next: (rows) => {
          this.currencies = rows.filter(c => c.isActive);
          const def = this.currencies.find(c => c.isDefault);
          if (def) this.form.currencyCode = def.currencyCode;
        },
      });
    }
  }

  onBeneficiaryChange(): void {
    // Clear the un-selected picker so only one ID goes into the payload.
    if (this.beneficiary === 'member') this.groupId = null;
    else this.memberId = null;
  }

  submit(): void {
    this.formError = null;

    if (this.beneficiary === 'member' && !this.memberId) {
      this.formError = 'Pick a member';
      return;
    }
    if (this.beneficiary === 'group' && !this.groupId) {
      this.formError = 'Pick a group';
      return;
    }
    const amt = Number(this.form.amount);
    if (!Number.isFinite(amt) || amt <= 0) {
      this.formError = 'Amount must be greater than zero';
      return;
    }

    const payload: CreateCtcPaymentPayload = {
      amount: amt.toFixed(2),
      currencyCode: this.form.currencyCode,
      memberId: this.beneficiary === 'member' ? this.memberId! : undefined,
      groupId: this.beneficiary === 'group' ? this.groupId! : undefined,
      contributionId: this.form.contributionId.trim() || undefined,
    };

    this.saving = true;
    this.finance.createCtcPayment(payload).subscribe({
      next: () => {
        this.saving = false;
        this.toast.success('CTC payment recorded — it will show up under Pending until committed.');
        this.router.navigate(['/tenant/claims/ctc/pending']);
      },
      error: (err) => {
        this.saving = false;
        const msg = err?.error?.detail || err?.error?.title || 'Failed to record CTC payment';
        this.formError = msg;
        this.toast.error(msg);
      },
    });
  }
}
