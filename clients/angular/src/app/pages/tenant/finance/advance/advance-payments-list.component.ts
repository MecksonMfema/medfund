import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  AdvancePayment,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-advance-payments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './advance-payments-list.component.html',
  styleUrl: './advance-payments-list.component.scss',
})
export class AdvancePaymentsListComponent implements OnInit {
  rows: AdvancePayment[] = [];
  filtered: AdvancePayment[] = [];
  loading = false;
  errorMessage: string | null = null;

  searchTerm = '';
  payeeFilter: '' | 'provider' | 'member' = '';

  constructor(private finance: FinanceService, private router: Router) {}

  readonly payeeOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'provider', label: 'Providers' },
    { value: 'member', label: 'Members' },
  ];

  open(row: AdvancePayment): void {
    this.router.navigate(['/tenant/finance/payments/advance', row.id]);
  }

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.finance.listAdvancePayments().subscribe({
      next: (rows) => { this.rows = rows; this.applyFilter(); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load advance payments';
        this.loading = false;
      },
    });
  }

  onFilterChange(): void { this.applyFilter(); }

  private applyFilter(): void {
    let rows = this.rows;
    if (this.payeeFilter === 'provider') rows = rows.filter(r => !!r.providerId);
    if (this.payeeFilter === 'member') rows = rows.filter(r => !!r.memberId);
    const q = this.searchTerm.trim().toLowerCase();
    if (q) {
      rows = rows.filter(r =>
        (r.reference && r.reference.toLowerCase().includes(q)) ||
        (r.comment && r.comment.toLowerCase().includes(q)));
    }
    this.filtered = rows;
  }
}
