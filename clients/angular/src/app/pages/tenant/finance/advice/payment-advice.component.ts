import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  FinanceService,
  PaymentAdvice,
  PaymentAdviceRecord,
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
export class PaymentAdviceComponent implements OnInit {
  paymentRunId = '';
  advice: PaymentAdvice | null = null;
  history: PaymentAdviceRecord[] = [];
  loading = false;
  errorMessage: string | null = null;

  constructor(private finance: FinanceService) {}

  ngOnInit(): void {
    this.refreshHistory();
  }

  refreshHistory(): void {
    this.finance.listAdviceRecords().subscribe({
      next: (rows) => { this.history = rows.slice(0, 20); },
      error: () => { this.history = []; },
    });
  }

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
        this.refreshHistory();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to generate advice';
        this.loading = false;
      },
    });
  }

  loadFromHistory(record: PaymentAdviceRecord): void {
    if (!record.paymentRunId) return;
    this.paymentRunId = record.paymentRunId;
    this.generate();
  }

  print(): void {
    window.print();
  }

  totalApproved(): number {
    if (!this.advice) return 0;
    return this.advice.lines.reduce((s, l) => s + Number(l.approvedAmount || 0), 0);
  }
}
