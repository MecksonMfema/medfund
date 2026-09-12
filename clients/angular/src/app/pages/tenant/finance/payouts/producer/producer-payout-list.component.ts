import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ProducerPayoutRun, ProducerPayoutService } from '../../../../../core/services/producer-payout.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import {
  DataTableComponent,
  TableColumn,
} from '../../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';

interface PayoutRow extends ProducerPayoutRun {
  periodLabel: string;
}

/**
 * Phase 11 §A Phase 6 — PRODUCER-typed payment runs list. Filters by
 * status + currency. Row-click routes to /tenant/finance/payouts/producer/{id}.
 */
@Component({
  selector: 'app-producer-payout-list',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, IconComponent, SelectComponent],
  templateUrl: './producer-payout-list.component.html',
  styleUrl: './producer-payout-list.component.scss',
})
export class ProducerPayoutListComponent implements OnInit {
  private service = inject(ProducerPayoutService);
  private currencyService = inject(CurrencyService);
  private tenantService = inject(TenantService);
  private router = inject(Router);

  rows: PayoutRow[] = [];
  loading = false;
  error: string | null = null;

  statusFilter = '';
  currencyFilter = '';
  currencies: TenantCurrencyConfig[] = [];

  readonly statusOptions: SelectOption[] = [
    { value: '',          label: 'All statuses' },
    { value: 'draft',     label: 'Draft' },
    { value: 'approved',  label: 'Approved' },
    { value: 'executing', label: 'Executing' },
    { value: 'executed',  label: 'Executed' },
    { value: 'cancelled', label: 'Cancelled' },
  ];

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'All currencies' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  readonly columns: TableColumn[] = [
    { key: 'runNumber',    label: 'Run #',    sortable: false },
    { key: 'status',       label: 'Status',   sortable: false, type: 'status' },
    { key: 'currencyCode', label: 'Currency', sortable: false },
    { key: 'periodLabel',  label: 'Period',   sortable: false },
    { key: 'paymentCount', label: 'Producer count', sortable: false },
    { key: 'totalAmount',  label: 'Total',    sortable: false, type: 'currency' },
    { key: 'createdAt',    label: 'Created',  sortable: false, type: 'date' },
  ];

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (tenantId) {
      this.currencyService.listForTenant(tenantId).subscribe({
        next: configs => { this.currencies = configs.filter(c => c.isActive && c.isPaymentCurrency); },
        error: () => { this.currencies = []; },
      });
    }
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.error = null;
    this.service.list(
      this.statusFilter || undefined,
      this.currencyFilter?.trim().toUpperCase() || undefined,
    ).subscribe({
      next: rows => {
        this.rows = rows.map(r => ({
          ...r,
          periodLabel: r.periodStart && r.periodEnd ? `${r.periodStart} → ${r.periodEnd}` : '-',
        }));
        this.loading = false;
      },
      error: err => {
        this.error = err?.error?.message ?? err?.error?.detail ?? 'Failed to load producer payouts';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.reload(); }
  onCurrencyChange(): void { this.reload(); }

  openRow(row: PayoutRow): void {
    this.router.navigate(['/tenant/finance/payouts/producer', row.id]);
  }

  openNew(): void {
    this.router.navigate(['/tenant/finance/payouts/producer/new']);
  }
}
