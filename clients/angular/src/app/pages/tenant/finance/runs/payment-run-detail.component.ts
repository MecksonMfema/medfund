import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, of } from 'rxjs';
import {
  FinancePageResponse,
  FinanceService,
  PaymentRow,
  PaymentRun,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../shared/components/data-table/data-table.component';

/**
 * Detail page for a single payment run — the folded landing spot for
 * payments-in-run (V067). Header on top surfaces the run's identity
 * and money movement (generated, status, settlement date, carry-in
 * from prior runs, carry-out to next); payments table below is scoped
 * via the new {@code paymentRunId} filter on GET /payments/page.
 */
@Component({
  selector: 'app-payment-run-detail',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    SkeletonComponent,
    CurrencyFormatPipe,
    HumanizePipe,
    DataTableComponent,
  ],
  templateUrl: './payment-run-detail.component.html',
  styleUrl: './payment-run-detail.component.scss',
})
export class PaymentRunDetailComponent implements OnInit {
  run: PaymentRun | null = null;
  loading = false;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  // ── Payments table state (scoped to this run) ────────────────────
  rows: PaymentRow[] = [];
  paymentsLoading = false;
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'paymentNumber', label: 'Payment #',   sortable: true },
    { key: 'providerName',  label: 'Provider',    sortable: true },
    { key: 'amount',        label: 'Amount',      sortable: true, type: 'currency' },
    { key: 'currencyCode',  label: 'Currency',    sortable: true },
    { key: 'paymentType',   label: 'Type',        sortable: true, type: 'label' },
    { key: 'status',        label: 'Status',      sortable: true, type: 'status' },
    { key: 'reference',     label: 'Reference' },
    { key: 'paidAt',        label: 'Paid at',     sortable: true, type: 'date' },
    { key: 'createdAt',     label: 'Created',     sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: PaymentRow) => this.router.navigate(['/tenant/finance/payments', row.id]),
    },
  ];

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMessage = 'No payment run id provided';
      return;
    }
    this.refresh(id);
  }

  refresh(id: string): void {
    this.loading = true;
    this.finance.getRun(id).subscribe({
      next: (run) => {
        this.run = run;
        this.loading = false;
        this.fetchPayments();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load payment run';
        this.loading = false;
      },
    });
  }

  fetchPayments(): void {
    if (!this.run) return;
    this.paymentsLoading = true;
    this.finance.listPaymentsPaged({
      paymentRunId: this.run.id,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    })
      .pipe(catchError(() => of<FinancePageResponse<PaymentRow>>({
        content: [], total: 0, page: 0, size: this.pageSize, totalPages: 1,
      })))
      .subscribe((resp) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.paymentsLoading = false;
      });
  }

  onPageChange(page: number): void {
    this.page = page;
    this.fetchPayments();
  }

  onSearchChange(term: string): void {
    this.searchTerm = term;
    this.page = 1;
    this.fetchPayments();
  }

  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPayments();
  }

  execute(): void {
    if (!this.run) return;
    if (!confirm(`Execute run ${this.run.runNumber}? This commits payments and is final.`)) return;
    this.transition('executed', () => this.finance.executeRun(this.run!.id));
  }

  approve(): void {
    if (!this.run) return;
    if (!confirm(`Approve run ${this.run.runNumber}?`)) return;
    this.transition('approved', () => this.finance.approveRun(this.run!.id));
  }

  cancelRun(): void {
    if (!this.run) return;
    if (!confirm(`Cancel run ${this.run.runNumber}?`)) return;
    this.transition('cancelled', () => this.finance.cancelRun(this.run!.id));
  }

  private transition(label: string, fn: () => import('rxjs').Observable<PaymentRun>): void {
    this.busy = true;
    fn().subscribe({
      next: (run) => {
        this.run = run;
        this.successMessage = `Payment run ${run.runNumber} → ${label}.`;
        this.busy = false;
        this.refresh(run.id);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || `Failed to ${label}`;
        this.busy = false;
      },
    });
  }

  back(): void {
    this.router.navigate(['/tenant/finance/runs']);
  }
}
