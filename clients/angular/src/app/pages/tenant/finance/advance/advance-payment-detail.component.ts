import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  AdvancePayment,
  AdvancePaymentApplication,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { PermissionService } from '../../../../core/security/permission.service';

@Component({
  selector: 'app-advance-payment-detail',
  standalone: true,
  imports: [CommonModule, IconComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './advance-payment-detail.component.html',
  styleUrl: './advance-payment-detail.component.scss',
})
export class AdvancePaymentDetailComponent implements OnInit {
  payment: AdvancePayment | null = null;
  applications: AdvancePaymentApplication[] = [];
  loading = false;
  errorMessage: string | null = null;
  banner: { kind: 'success' | 'error'; text: string } | null = null;

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
    private permissions: PermissionService,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMessage = 'No advance payment id';
      return;
    }
    this.load(id);
  }

  load(id: string): void {
    this.loading = true;
    forkJoin({
      payment: this.finance.getAdvancePayment(id),
      applications: this.finance.listAdvancePaymentApplications(id).pipe(
        catchError(() => of([] as AdvancePaymentApplication[])),
      ),
    }).subscribe({
      next: ({ payment, applications }) => {
        this.payment = payment;
        this.applications = applications;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load advance payment';
        this.loading = false;
      },
    });
  }

  get canApprove(): boolean {
    return !!this.payment
      && this.payment.status === 'pending'
      && this.permissions.has('finance:approve_advance_payment');
  }

  get canReverse(): boolean {
    return !!this.payment
      && (this.payment.status === 'approved' || this.payment.status === 'applied')
      && this.payment.type !== 'REVERSAL'
      && this.permissions.has('finance:reverse_advance_payment');
  }

  approve(): void {
    if (!this.payment) return;
    if (!confirm(`Approve advance payment ${this.payment.reference || this.payment.id.substring(0, 8)}?`)) return;
    this.finance.approveAdvancePayment(this.payment.id).subscribe({
      next: () => {
        this.banner = { kind: 'success', text: 'Advance approved.' };
        this.load(this.payment!.id);
      },
      error: (err) => {
        this.banner = { kind: 'error', text: err?.error?.detail || err?.error?.title || 'Failed to approve' };
      },
    });
  }

  reverse(): void {
    if (!this.payment) return;
    const reason = prompt(`Reverse advance payment ${this.payment.reference || this.payment.id.substring(0, 8)}?\n\nEnter reason:`);
    if (!reason || !reason.trim()) return;
    this.finance.reverseAdvancePayment(this.payment.id, { reason: reason.trim() }).subscribe({
      next: (compensating) => {
        this.banner = {
          kind: 'success',
          text: `Reversal posted — compensating entry ${compensating.reference || compensating.id.substring(0, 8)}.`,
        };
        this.load(this.payment!.id);
      },
      error: (err) => {
        this.banner = { kind: 'error', text: err?.error?.detail || err?.error?.title || 'Failed to reverse' };
      },
    });
  }

  openOriginal(): void {
    if (this.payment?.reversesAdvanceId) {
      this.router.navigate(['/tenant/finance/payments/advance', this.payment.reversesAdvanceId]);
    }
  }

  back(): void {
    this.router.navigate(['/tenant/finance/payments/advance']);
  }
}
