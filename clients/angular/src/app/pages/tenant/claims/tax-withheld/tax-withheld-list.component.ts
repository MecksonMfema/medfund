import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  Adjustment,
  AdjustmentStatus,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { StatCardComponent } from '../../../../shared/components/stat-card/stat-card.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-tax-withheld-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    IconComponent,
    SelectComponent,
    SkeletonComponent,
    StatCardComponent,
    CurrencyFormatPipe,
    HumanizePipe,
  ],
  templateUrl: './tax-withheld-list.component.html',
  styleUrl: './tax-withheld-list.component.scss',
})
export class TaxWithheldListComponent implements OnInit {
  rows: Adjustment[] = [];
  loading = false;
  statusFilter: AdjustmentStatus | '' = '';
  q = '';
  variant: 'medical' | 'drug' = 'medical';

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All statuses' },
    { value: 'pending', label: 'Pending' },
    { value: 'approved', label: 'Approved' },
    { value: 'applied', label: 'Applied' },
    { value: 'cancelled', label: 'Cancelled' },
  ];

  constructor(
    private finance: FinanceService,
    private toast: ToastService,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.variant = this.route.snapshot.data?.['variant'] === 'drug' ? 'drug' : 'medical';
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    // The adjustments API supports lookups by status and by provider but not by type,
    // so we fan out across statuses and filter to TAX_WITHHELD client-side. If a
    // status filter is set, we skip the fan-out.
    const statuses: AdjustmentStatus[] = this.statusFilter
      ? [this.statusFilter]
      : ['pending', 'approved', 'applied', 'cancelled'];

    forkJoin(statuses.map(s => this.finance.getAdjustmentsByStatus(s))).subscribe({
      next: (buckets) => {
        const all = buckets.flat().filter(a => a.adjustmentType === 'TAX_WITHHELD');
        // Deduplicate by id — no server dedupe, and a caching layer may repeat.
        const map = new Map<string, Adjustment>();
        for (const a of all) map.set(a.id, a);
        this.rows = [...map.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load tax-withheld records');
      },
    });
  }

  get filtered(): Adjustment[] {
    if (!this.q.trim()) return this.rows;
    const q = this.q.trim().toLowerCase();
    return this.rows.filter(r =>
      (r.adjustmentNumber || '').toLowerCase().includes(q) ||
      (r.reason || '').toLowerCase().includes(q) ||
      (r.providerId || '').toLowerCase().includes(q) ||
      (r.memberId || '').toLowerCase().includes(q),
    );
  }

  get totalAmount(): number {
    return this.filtered.reduce((s, r) => s + Number(r.amount || 0), 0);
  }

  get currencyMix(): string {
    const set = new Set(this.filtered.map(r => r.currencyCode));
    return set.size === 1 ? [...set][0] : 'Mixed';
  }

  get pageTitle(): string {
    return this.variant === 'drug' ? 'Tax-withheld drug claims' : 'Tax-withheld claims';
  }

  onStatusChange(): void { this.refresh(); }
}
