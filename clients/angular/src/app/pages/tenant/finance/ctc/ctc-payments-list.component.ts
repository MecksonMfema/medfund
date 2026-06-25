import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  CtcPayment,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-ctc-payments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './ctc-payments-list.component.html',
  styleUrl: './ctc-payments-list.component.scss',
})
export class CtcPaymentsListComponent implements OnInit {
  rows: CtcPayment[] = [];
  loading = false;
  errorMessage: string | null = null;

  committedFilter: '' | 'true' | 'false' = '';

  constructor(private finance: FinanceService, private router: Router) {}

  readonly committedOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'false', label: 'Pending' },
    { value: 'true', label: 'Committed' },
  ];

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    const committed = this.committedFilter ? this.committedFilter === 'true' : undefined;
    this.finance.listCtcPayments(committed).subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load CTC payments';
        this.loading = false;
      },
    });
  }

  open(row: CtcPayment): void {
    this.router.navigate(['/tenant/finance/payments/ctc', row.id]);
  }
}
