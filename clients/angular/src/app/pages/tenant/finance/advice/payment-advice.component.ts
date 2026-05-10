import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  FinanceService,
  PaymentAdvice,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-payment-advice',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './payment-advice.component.html',
  styleUrl: './payment-advice.component.scss',
})
export class PaymentAdviceComponent {
  paymentRunId = '';
  advice: PaymentAdvice | null = null;
  loading = false;
  errorMessage: string | null = null;

  constructor(private finance: FinanceService) {}

  generate(): void {
    if (!this.paymentRunId.trim()) {
      this.errorMessage = 'Enter a payment run ID';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.finance.generateAdvice(this.paymentRunId.trim()).subscribe({
      next: (advice) => {
        this.advice = advice;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to generate advice';
        this.loading = false;
      },
    });
  }

  print(): void {
    window.print();
  }

  totalApproved(): number {
    if (!this.advice) return 0;
    return this.advice.lines.reduce((s, l) => s + Number(l.approvedAmount || 0), 0);
  }
}
