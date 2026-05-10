import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DataTableComponent } from '../../shared/components/data-table/data-table.component';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { FinanceService, Payment, PaymentRun, ProviderBalance } from '../../core/services/finance.service';

@Component({
  selector: 'app-finance',
  standalone: true,
  imports: [CommonModule, DataTableComponent, StatCardComponent],
  templateUrl: './finance.component.html',
  styleUrl: './finance.component.scss',
})
export class FinanceComponent implements OnInit {
  payments: Payment[] = [];
  balances: ProviderBalance[] = [];
  paymentCount = 0; runCount = 0; totalOutstanding = '0.00';

  paymentColumns = [
    { key: 'paymentNumber', label: 'Payment #' },
    { key: 'amount', label: 'Amount', type: 'currency' },
    { key: 'paymentType', label: 'Type' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'createdAt', label: 'Date', type: 'date' },
  ];

  balanceColumns = [
    { key: 'providerId', label: 'Provider ID' },
    { key: 'totalClaimed', label: 'Claimed', type: 'currency' },
    { key: 'totalApproved', label: 'Approved', type: 'currency' },
    { key: 'totalPaid', label: 'Paid', type: 'currency' },
    { key: 'outstandingBalance', label: 'Outstanding', type: 'currency' },
  ];

  constructor(private financeService: FinanceService) {}

  ngOnInit(): void {
    this.financeService.listPayments().subscribe({
      next: (p) => { this.payments = p.slice(0, 20); this.paymentCount = p.length; },
      error: () => {}
    });
    this.financeService.listProviderBalances().subscribe({
      next: (b) => {
        this.balances = b;
        const total = b.reduce((sum, bal) => sum + Number(bal.outstandingBalance || 0), 0);
        this.totalOutstanding = total.toFixed(2);
      },
      error: () => {}
    });
    this.financeService.listRuns().subscribe({
      next: (r) => { this.runCount = r.length; },
      error: () => {}
    });
  }
}
