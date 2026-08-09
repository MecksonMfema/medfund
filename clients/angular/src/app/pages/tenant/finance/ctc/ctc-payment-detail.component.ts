import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  CtcPayment,
  FinanceService,
  ReverseCtcPaymentPayload,
} from '../../../../core/services/finance.service';
import { PermissionService } from '../../../../core/security/permission.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

/**
 * CTC payment detail. Shows a status pill + timeline strip
 * (Recorded → Committed → Reversed) plus link chips to the source
 * payable and the CTC_OFFSET transaction posted at commit time.
 * Commit and Reverse actions are permission-gated per the plan.
 */
@Component({
  selector: 'app-ctc-payment-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './ctc-payment-detail.component.html',
  styleUrl: './ctc-payment-detail.component.scss',
})
export class CtcPaymentDetailComponent implements OnInit {
  payment: CtcPayment | null = null;
  loading = false;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
    private permissions: PermissionService,
    private confirm: ConfirmService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMessage = 'No CTC payment id';
      return;
    }
    this.refresh(id);
  }

  refresh(id: string): void {
    this.loading = true;
    this.finance.getCtcPayment(id).subscribe({
      next: (p) => { this.payment = p; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load CTC payment';
        this.loading = false;
      },
    });
  }

  get status(): 'draft' | 'committed' | 'reversed' {
    const p = this.payment;
    if (!p) return 'draft';
    if (p.status) return p.status;
    return p.committed ? 'committed' : 'draft';
  }

  get statusClass(): string {
    switch (this.status) {
      case 'committed': return 'badge ok';
      case 'reversed':  return 'badge danger';
      default:          return 'badge muted';
    }
  }

  get isReversalRow(): boolean {
    return this.payment?.type === 'REVERSAL';
  }

  get canCommit(): boolean {
    return this.status === 'draft'
      && (this.permissions.has('finance:manage_ctc_payments')
          || this.permissions.has('claims:commit_ctc_payment'));
  }

  get canReverse(): boolean {
    return this.status === 'committed'
      && !this.isReversalRow
      && this.permissions.has('finance:reverse_ctc_payment');
  }

  async commit(): Promise<void> {
    if (!this.payment || !this.canCommit) return;
    const ok = await this.confirm.ask({
      title: 'Commit CTC payment',
      message: `Commit ${this.payment.amount} ${this.payment.currencyCode}? This posts a CTC_OFFSET transaction against the member's contribution ledger.`,
      confirmLabel: 'Commit',
    });
    if (!ok) return;

    this.busy = true;
    this.finance.commitCtcPayment(this.payment.id).subscribe({
      next: (p) => {
        this.payment = p;
        this.successMessage = 'CTC payment committed — CTC_OFFSET transaction posted';
        this.busy = false;
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to commit');
        this.busy = false;
      },
    });
  }

  async reverse(): Promise<void> {
    if (!this.payment || !this.canReverse) return;
    const ok = await this.confirm.ask({
      title: 'Reverse CTC payment',
      message: `Reverse ${this.payment.amount} ${this.payment.currencyCode}? A compensating REVERSAL row is written and a CTC_OFFSET_REVERSAL restores the member's contribution balance.`,
      confirmLabel: 'Reverse',
      danger: true,
    });
    if (!ok) return;
    const reason = window.prompt('Reason for the reversal (audit trail):');
    if (!reason || !reason.trim()) return;

    this.busy = true;
    const body: ReverseCtcPaymentPayload = { reason: reason.trim() };
    this.finance.reverseCtcPayment(this.payment.id, body).subscribe({
      next: (compensating) => {
        // The endpoint returns the compensating row; re-fetch the original
        // (still at this route) so the page reflects the new `reversed`
        // status.
        this.successMessage = `Reversal posted — compensating row ${compensating.id.substring(0, 8)}`;
        if (this.payment) this.refresh(this.payment.id);
        this.busy = false;
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to reverse');
        this.busy = false;
      },
    });
  }

  back(): void {
    this.router.navigate(['/tenant/finance/payments/ctc']);
  }
}
