import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  CreateCtcPaymentPayload,
  FinanceService,
  MemberPayable,
} from '../../../../core/services/finance.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * CTC (Claims-to-Contributions) recording form. Two-step selection —
 *
 *  1. Pick the member via the debounced entity-picker (never a raw UUID
 *     input, per feedback_no_raw_id_inputs).
 *  2. Pick one of the member's open payables from a server-fetched
 *     dropdown. Selecting a payable pre-fills the currency and caps the
 *     amount at the payable's remaining balance.
 *
 * The server 422s a group-only CTC — group-level offsets are out of
 * scope for MVP (design decision #1 in the plan).
 */
@Component({
  selector: 'app-ctc-payment-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, EntityPickerComponent, IconComponent, SelectComponent],
  templateUrl: './ctc-payment-form.component.html',
  styleUrl: './ctc-payment-form.component.scss',
})
export class CtcPaymentFormComponent implements OnInit {
  tenantCurrencies: TenantCurrencyConfig[] = [];
  busy = false;
  loadingPayables = false;
  errorMessage: string | null = null;

  memberId: string | null = null;
  memberPayableId: string | null = null;
  openPayables: MemberPayable[] = [];

  amount = '';
  currencyCode = '';
  contributionId = '';

  constructor(
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private finance: FinanceService,
    private router: Router,
    private toast: ToastService,
  ) {}

  get currencyOptions(): SelectOption[] {
    return this.tenantCurrencies
      .filter(c => c.isActive)
      .map(c => ({ value: c.currencyCode, label: c.currencyCode }));
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

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (tenantId) {
      this.currencyService.listForTenant(tenantId).subscribe({
        next: (rows) => {
          this.tenantCurrencies = rows;
          if (!this.currencyCode) {
            const first = rows.find(c => c.isActive);
            if (first) this.currencyCode = first.currencyCode;
          }
        },
        error: () => {},
      });
    }
  }

  onMemberChange(newId: string | null): void {
    this.memberId = newId;
    this.memberPayableId = null;
    this.openPayables = [];
    this.amount = '';
    if (!newId) return;
    this.loadingPayables = true;
    this.finance.listOpenPayablesForMember(newId).subscribe({
      next: (rows) => {
        this.openPayables = (rows || []).filter(p => p.status === 'open');
        this.loadingPayables = false;
        if (this.openPayables.length === 0) {
          this.errorMessage = 'This member has no open payables to offset.';
        } else {
          this.errorMessage = null;
        }
      },
      error: (err) => {
        this.loadingPayables = false;
        this.errorMessage = err?.error?.detail || 'Failed to load member payables';
      },
    });
  }

  onPayableChange(payableId: string): void {
    this.memberPayableId = payableId;
    const p = this.selectedPayable;
    if (p) {
      this.currencyCode = p.currencyCode;
      // Pre-fill the amount with the payable's full remaining balance —
      // partial offsets are the exception, not the rule.
      this.amount = p.amount;
    }
  }

  submit(): void {
    this.errorMessage = null;
    if (!this.memberId) { this.errorMessage = 'Pick a member first'; return; }
    if (!this.memberPayableId) { this.errorMessage = 'Pick a payable to offset'; return; }
    if (!this.amount || Number(this.amount) <= 0) { this.errorMessage = 'Amount must be greater than zero'; return; }
    if (!this.currencyCode) { this.errorMessage = 'Currency is required'; return; }

    const payload: CreateCtcPaymentPayload = {
      memberId: this.memberId,
      memberPayableId: this.memberPayableId,
      amount: this.amount,
      currencyCode: this.currencyCode,
      contributionId: this.contributionId.trim() || undefined,
    };

    this.busy = true;
    this.finance.createCtcPayment(payload).subscribe({
      next: () => {
        this.busy = false;
        this.router.navigate(['/tenant/finance/payments/ctc'], {
          state: { ctcBanner: { kind: 'success', text: 'CTC drafted — awaiting commit' } },
        });
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to create CTC payment';
        this.busy = false;
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/tenant/finance/payments/ctc']);
  }
}
