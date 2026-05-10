import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import {
  CtcPayment,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-ctc-payment-detail',
  standalone: true,
  imports: [CommonModule, IconComponent, SkeletonComponent, CurrencyFormatPipe],
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

  commit(): void {
    if (!this.payment) return;
    if (!confirm('Commit this CTC payment? This is final.')) return;
    this.busy = true;
    this.finance.commitCtcPayment(this.payment.id).subscribe({
      next: (p) => {
        this.payment = p;
        this.successMessage = 'CTC payment committed.';
        this.busy = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to commit';
        this.busy = false;
      },
    });
  }

  back(): void {
    this.router.navigate(['/tenant/finance/payments/ctc']);
  }
}
