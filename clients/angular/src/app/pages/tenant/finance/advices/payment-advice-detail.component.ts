import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  FinanceService,
  PaymentAdvice,
  PaymentAdviceLine,
  PaymentAdviceLineType,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

const LINE_TYPE_ORDER: PaymentAdviceLineType[] = [
  'CARRY_FORWARD', 'CLAIM_PAID', 'NOTE_DEBIT', 'ADVANCE_APPLIED',
  'CTC_APPLIED', 'TAX_WITHHELD', 'SHORTFALL', 'NOTE_CREDIT',
];

const LINE_TYPE_LABEL: Record<PaymentAdviceLineType, string> = {
  CARRY_FORWARD:   'Carried forward from prior run',
  CLAIM_PAID:      'Claims paid',
  NOTE_DEBIT:      'Note debits',
  ADVANCE_APPLIED: 'Advance payments applied',
  CTC_APPLIED:     'CTCs applied',
  TAX_WITHHELD:    'Tax withheld',
  SHORTFALL:       'Shortfalls',
  NOTE_CREDIT:     'Note credits',
};

@Component({
  selector: 'app-payment-advice-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './payment-advice-detail.component.html',
  styleUrl: './payment-advice-detail.component.scss',
})
export class PaymentAdviceDetailComponent implements OnInit {
  advice: PaymentAdvice | null = null;
  loading = false;
  errorMessage: string | null = null;

  constructor(private route: ActivatedRoute, private finance: FinanceService) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMessage = 'Missing advice id';
      return;
    }
    this.loading = true;
    this.finance.getAdvice(id).subscribe({
      next: (advice) => {
        this.advice = advice;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load advice';
        this.loading = false;
      },
    });
  }

  linesFor(lineType: PaymentAdviceLineType): PaymentAdviceLine[] {
    if (!this.advice) return [];
    return this.advice.lines.filter(l => l.lineType === lineType);
  }

  hasLinesOfType(lineType: PaymentAdviceLineType): boolean {
    return this.linesFor(lineType).length > 0;
  }

  payeeName(): string {
    if (!this.advice) return '';
    return this.advice.payeeType === 'MEMBER'
      ? (this.advice.memberName || '')
      : (this.advice.providerName || '');
  }

  print(): void {
    window.print();
  }

  downloadPdf(): void {
    this.errorMessage = 'PDF export is coming soon.';
  }

  readonly SECTION_ORDER = LINE_TYPE_ORDER;
  readonly SECTION_LABEL = LINE_TYPE_LABEL;
}
