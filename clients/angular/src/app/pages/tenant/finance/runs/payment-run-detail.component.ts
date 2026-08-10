import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, of } from 'rxjs';
import {
  FinancePageResponse,
  FinanceService,
  PaymentAdviceRecord,
  PaymentRow,
  PaymentRun,
} from '../../../../core/services/finance.service';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../shared/components/data-table/data-table.component';
import { PermissionService } from '../../../../core/security/permission.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

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
    RouterLink,
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

  // ── Advices for this run ─────────────────────────────────────────
  advices: PaymentAdviceRecord[] = [];
  advicesLoading = false;
  regenerating = false;
  activeTab: 'payments' | 'advices' = 'payments';

  readonly columns: TableColumn[] = [
    { key: 'paymentNumber', label: 'Payment #',   sortable: true },
    { key: 'payeeName',     label: 'Payee',       sortable: true },
    { key: 'payeeType',     label: 'Type',        sortable: true, type: 'label' },
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
    {
      label: 'Revoke',
      icon: 'x-circle',
      color: 'danger',
      // Only visible while the payment is pending AND the parent run is
      // still mutable (draft or approved). The backend enforces the same
      // guard and 400s otherwise — this is a UX pre-check.
      visible: (row: PaymentRow) =>
        row.status === 'pending'
        && !!this.run
        && (this.run.status === 'draft' || this.run.status === 'approved')
        && this.permissions.has('finance:manage_payments'),
      handler: (row: PaymentRow) => this.revoke(row),
    },
  ];

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
    private permissions: PermissionService,
    private toast: ToastService,
  ) {}

  canRegenerateAdvices(): boolean {
    return this.permissions.has('finance:generate_payment_advice');
  }

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
        this.fetchAdvices();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load payment run';
        this.loading = false;
      },
    });
  }

  fetchAdvices(): void {
    if (!this.run) return;
    this.advicesLoading = true;
    this.finance.listAdvicesForRun(this.run.id).subscribe({
      next: (rows) => {
        this.advices = rows;
        this.advicesLoading = false;
      },
      error: () => {
        this.advices = [];
        this.advicesLoading = false;
      },
    });
  }

  regenerateAdvices(): void {
    if (!this.run) return;
    if (!confirm('Regenerate advices for this run? Existing advice rows will be replaced.')) return;
    this.regenerating = true;
    this.finance.regenerateAdvicesForRun(this.run.id).subscribe({
      next: () => {
        this.successMessage = 'Advices regenerated.';
        this.regenerating = false;
        this.fetchAdvices();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to regenerate advices';
        this.regenerating = false;
      },
    });
  }

  showTab(tab: 'payments' | 'advices'): void {
    this.activeTab = tab;
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

  /**
   * Revoke a pending payment from this run. Deletes the Payment + its
   * PaymentRunItem, then reloads the run so paymentCount / totalAmount
   * reflect the new state. The payee's outstanding balance is unchanged
   * and next run generation picks them up again.
   */
  revoke(row: PaymentRow): void {
    if (!confirm(`Revoke payment ${row.paymentNumber}? The payee's outstanding balance is unchanged; they will be included in the next run generation.`)) return;
    this.finance.revokePayment(row.id).subscribe({
      next: () => {
        this.toast.success(`Revoked ${row.paymentNumber}`);
        // Reload the run summary + items — recomputeRunTotals ran server-side.
        if (this.run) this.refresh(this.run.id);
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || `Failed to revoke ${row.paymentNumber}`);
      },
    });
  }
}
