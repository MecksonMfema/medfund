import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import {
  AdvancePayment,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-advance-payment-detail',
  standalone: true,
  imports: [CommonModule, IconComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './advance-payment-detail.component.html',
  styleUrl: './advance-payment-detail.component.scss',
})
export class AdvancePaymentDetailComponent implements OnInit {
  payment: AdvancePayment | null = null;
  loading = false;
  errorMessage: string | null = null;

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMessage = 'No advance payment id';
      return;
    }
    this.loading = true;
    this.finance.getAdvancePayment(id).subscribe({
      next: (p) => { this.payment = p; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load advance payment';
        this.loading = false;
      },
    });
  }

  back(): void {
    this.router.navigate(['/tenant/finance/payments/advance']);
  }
}
