import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import {
  FinanceService,
  Payment,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

@Component({
  selector: 'app-payment-detail',
  standalone: true,
  imports: [CommonModule, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './payment-detail.component.html',
  styleUrl: './payment-detail.component.scss',
})
export class PaymentDetailComponent implements OnInit {
  payment: Payment | null = null;
  loading = false;
  busy = false;
  successMessage: string | null = null;

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.toast.error('No payment id');
      return;
    }
    this.refresh(id);
  }

  refresh(id: string): void {
    this.loading = true;
    this.finance.getPayment(id).subscribe({
      next: (p) => { this.payment = p; this.loading = false; },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Failed to load payment'));
        this.loading = false;
      },
    });
  }

  markPaid(): void {
    if (!this.payment) return;
    if (!confirm(`Mark payment ${this.payment.paymentNumber} as paid?`)) return;
    this.busy = true;
    this.finance.payPayment(this.payment.id).subscribe({
      next: (p) => {
        this.payment = p;
        this.successMessage = `Payment ${p.paymentNumber} marked paid.`;
        this.busy = false;
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Failed to mark paid'));
        this.busy = false;
      },
    });
  }

  cancelPayment(): void {
    if (!this.payment) return;
    if (!confirm(`Cancel payment ${this.payment.paymentNumber}?`)) return;
    this.busy = true;
    this.finance.cancelPayment(this.payment.id).subscribe({
      next: (p) => {
        this.payment = p;
        this.successMessage = `Payment ${p.paymentNumber} cancelled.`;
        this.busy = false;
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Failed to cancel'));
        this.busy = false;
      },
    });
  }

  back(): void {
    this.router.navigate(['/tenant/finance/payments']);
  }
}
