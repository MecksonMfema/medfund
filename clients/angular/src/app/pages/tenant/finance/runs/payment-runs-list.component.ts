import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  FinanceService,
  PaymentRun,
  PaymentRunStatus,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-payment-runs-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './payment-runs-list.component.html',
  styleUrl: './payment-runs-list.component.scss',
})
export class PaymentRunsListComponent implements OnInit {
  rows: PaymentRun[] = [];
  filtered: PaymentRun[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Route-driven preset (e.g. current-payment-run preset = draft).
  presetStatus: PaymentRunStatus | '' = '';
  pageTitle = 'Payment runs';
  pageDescription = 'Batched payouts to providers. Each run aggregates eligible adjudicated claims into a draft, then commits on execute.';

  statusFilter: PaymentRunStatus | '' = '';
  currencyFilter = '';
  searchTerm = '';

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'draft', label: 'Draft' },
    { value: 'approved', label: 'Approved' },
    { value: 'executing', label: 'Executing' },
    { value: 'executed', label: 'Executed' },
    { value: 'cancelled', label: 'Cancelled' },
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
    this.finance.listRuns().subscribe({
      next: (rows) => { this.rows = rows; this.applyFilter(); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load payment runs';
        this.loading = false;
      },
    });
  }

  open(run: PaymentRun): void {
    this.router.navigate(['/tenant/finance/runs', run.id]);
  }

  onFilterChange(): void {
    this.applyFilter();
  }

  uniqueCurrencies(): string[] {
    return Array.from(new Set(this.rows.map(r => r.currencyCode))).sort();
  }

  private applyFilter(): void {
    let rows = this.rows;
    if (this.statusFilter) {
      rows = rows.filter(r => r.status === this.statusFilter);
    }
    if (this.currencyFilter) {
      rows = rows.filter(r => r.currencyCode === this.currencyFilter);
    }
    const q = this.searchTerm.trim().toLowerCase();
    if (q) {
      rows = rows.filter(r =>
        r.runNumber?.toLowerCase().includes(q) ||
        (r.description && r.description.toLowerCase().includes(q)),
      );
    }
    this.filtered = rows;
  }
}
