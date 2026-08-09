import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  CreateCtcPaymentPayload,
  FinanceService,
  MemberPayable,
} from '../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * Claims-side entry to record a CTC. Same two-step selection as the
 * finance-side form (member → payable → amount) — the two entry points
 * exist so both operator roles can start from their natural workspace.
 */
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
  loadingPayables = false;
  formError: string | null = null;

  memberId: string | null = null;
  memberPayableId: string | null = null;
  openPayables: MemberPayable[] = [];

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

  get payableOptions(): SelectOption[] {
    return this.openPayables.map(p => ({
      value: p.id,
      label: `${p.claimNumber ? '#' + p.claimNumber + ' • ' : ''}${p.amount} ${p.currencyCode}`,
    }));
  }

  get selectedPayable(): MemberPayable | null {
    if (!this.memberPayableId) return null;
    return this.openPayables.find(p => p.id === this.memberPayableId) ?? null;
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

  onMemberChange(newId: string | null): void {
    this.memberId = newId;
    this.memberPayableId = null;
    this.openPayables = [];
    this.form.amount = '';
    this.formError = null;
    if (!newId) return;
    this.loadingPayables = true;
    this.finance.listOpenPayablesForMember(newId).subscribe({
      next: (rows) => {
        this.openPayables = (rows || []).filter(p => p.status === 'open');
        this.loadingPayables = false;
        if (this.openPayables.length === 0) {
          this.formError = 'This member has no open payables to offset.';
        }
      },
      error: (err) => {
        this.loadingPayables = false;
        this.formError = err?.error?.detail || 'Failed to load member payables';
      },
    });
  }

  onPayableChange(payableId: string): void {
    this.memberPayableId = payableId;
    const p = this.selectedPayable;
    if (p) {
      this.form.currencyCode = p.currencyCode;
      this.form.amount = p.amount;
    }
  }

  submit(): void {
    this.formError = null;
    if (!this.memberId) { this.formError = 'Pick a member first'; return; }
    if (!this.memberPayableId) { this.formError = 'Pick a payable to offset'; return; }
    const amt = Number(this.form.amount);
    if (!Number.isFinite(amt) || amt <= 0) {
      this.formError = 'Amount must be greater than zero';
      return;
    }

    const payload: CreateCtcPaymentPayload = {
      memberId: this.memberId,
      memberPayableId: this.memberPayableId,
      amount: amt.toFixed(2),
      currencyCode: this.form.currencyCode,
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
