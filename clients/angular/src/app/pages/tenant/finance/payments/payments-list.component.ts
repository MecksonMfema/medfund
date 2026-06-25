import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  FinanceService,
  Payment,
  PaymentStatus,
} from '../../../../core/services/finance.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-payments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './payments-list.component.html',
  styleUrl: './payments-list.component.scss',
})
export class PaymentsListComponent implements OnInit {
  rows: Payment[] = [];
  filtered: Payment[] = [];
  loading = false;
  errorMessage: string | null = null;

  presetStatus: PaymentStatus | '' = '';
  pageTitle = 'Payments';
  pageDescription = 'Provider payouts. Filter by status or currency. Click any row to inspect.';

  statusFilter: PaymentStatus | '' = '';
  currencyFilter = '';
  searchTerm = '';

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'pending', label: 'Pending' },
    { value: 'approved', label: 'Approved' },
    { value: 'paid', label: 'Paid' },
    { value: 'cancelled', label: 'Cancelled' },
    { value: 'failed', label: 'Failed' },
  ];

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'All' },
      ...this.uniqueCurrencies().map(c => ({ value: c, label: c })),
    ];
  }

  ngOnInit(): void {
    const data = this.route.snapshot.data;
    if (data['presetStatus']) {
      this.presetStatus = data['presetStatus'];
      this.statusFilter = this.presetStatus;
    }
    if (data['title']) this.pageTitle = data['title'];
    if (data['description']) this.pageDescription = data['description'];

    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    const stream = this.statusFilter
      ? this.finance.getPaymentsByStatus(this.statusFilter)
      : this.finance.listPayments();
    stream.subscribe({
      next: (rows) => { this.rows = rows; this.applyFilter(); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load payments';
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.refresh(); }
  onFilterChange(): void { this.applyFilter(); }

  open(payment: Payment): void {
    this.router.navigate(['/tenant/finance/payments', payment.id]);
  }

  uniqueCurrencies(): string[] {
    return Array.from(new Set(this.rows.map(r => r.currencyCode))).sort();
  }

  private applyFilter(): void {
    let rows = this.rows;
    if (this.currencyFilter) {
      rows = rows.filter(r => r.currencyCode === this.currencyFilter);
    }
    const q = this.searchTerm.trim().toLowerCase();
    if (q) {
      rows = rows.filter(r =>
        r.paymentNumber?.toLowerCase().includes(q) ||
        (r.reference && r.reference.toLowerCase().includes(q)),
      );
    }
    this.filtered = rows;
  }
}
