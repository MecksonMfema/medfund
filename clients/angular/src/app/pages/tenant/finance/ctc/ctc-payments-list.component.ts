import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  CtcPaymentRow,
  FinancePageResponse,
  FinanceService,
} from '../../../../core/services/finance.service';
import { PermissionService } from '../../../../core/security/permission.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

/**
 * Finance-side CTC list. Mirrors the claims-side ctc-list.component.ts
 * (paginated + joined names + shared confirm) and adds the finance-only
 * Reverse row action gated on `finance:reverse_ctc_payment`.
 */
@Component({
  selector: 'app-ctc-payments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DataTableComponent, IconComponent],
  templateUrl: './ctc-payments-list.component.html',
  styleUrl: './ctc-payments-list.component.scss',
})
export class CtcPaymentsListComponent implements OnInit {
  rows: CtcPaymentRow[] = [];
  loading = false;
  busyId: string | null = null;
  banner: { kind: 'success' | 'info' | 'error'; text: string } | null = null;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'memberName',   label: 'Member',       sortable: true },
    { key: 'memberNumber', label: 'Number',       sortable: false },
    { key: 'amount',       label: 'Amount',       sortable: true, type: 'currency' },
    { key: 'currencyCode', label: 'Currency',     sortable: true },
    { key: 'status',       label: 'Status',       type: 'status' },
    { key: 'type',         label: 'Type' },
    { key: 'createdAt',    label: 'Created',      sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: CtcPaymentRow) => this.router.navigate(['/tenant/finance/payments/ctc', row.id]),
    },
    {
      label: 'Commit',
      icon: 'check-circle',
      color: 'success',
      visible: (row: CtcPaymentRow) =>
        this.rowStatus(row) === 'draft'
        && (this.permissions.has('finance:manage_ctc_payments')
            || this.permissions.has('claims:commit_ctc_payment')),
      labelFor: (row: CtcPaymentRow) => this.busyId === row.id ? 'Committing…' : 'Commit',
      handler: (row: CtcPaymentRow) => this.commit(row),
    },
    {
      label: 'Reverse',
      icon: 'rotate-ccw',
      color: 'danger',
      visible: (row: CtcPaymentRow) =>
        this.rowStatus(row) === 'committed'
        && row.type !== 'REVERSAL'
        && this.permissions.has('finance:reverse_ctc_payment'),
      handler: (row: CtcPaymentRow) => this.reverse(row),
    },
  ];

  constructor(
    private finance: FinanceService,
    private router: Router,
    private permissions: PermissionService,
    private confirm: ConfirmService,
    private toast: ToastService,
  ) {}

  get canCreate(): boolean { return this.permissions.has('finance:manage_ctc_payments'); }

  ngOnInit(): void {
    const nav = this.router.getCurrentNavigation();
    const state = (nav?.extras?.state ?? window.history.state) as
      | { ctcBanner?: { kind: 'success' | 'info' | 'error'; text: string } }
      | null;
    if (state?.ctcBanner) this.banner = state.ctcBanner;
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.finance.listCtcPaymentsPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<CtcPaymentRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.toast.error(err?.error?.detail || err?.error?.title || 'Failed to load CTC payments');
      },
    });
  }

  onPageChange(page: number): void {
    this.page = page;
    this.fetchPage();
  }

  onSearchChange(term: string): void {
    this.searchTerm = term;
    this.page = 1;
    this.fetchPage();
  }

  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }

  async commit(row: CtcPaymentRow): Promise<void> {
    if (this.rowStatus(row) !== 'draft') return;
    const ok = await this.confirm.ask({
      title: 'Commit CTC payment',
      message: `Commit ${row.amount} ${row.currencyCode} against this member-payable? This posts a CTC_OFFSET transaction and locks the row.`,
      confirmLabel: 'Commit',
    });
    if (!ok) return;

    this.busyId = row.id;
    this.finance.commitCtcPayment(row.id).subscribe({
      next: () => {
        this.busyId = null;
        this.banner = { kind: 'success', text: 'CTC committed — CTC_OFFSET transaction posted' };
        this.fetchPage();
      },
      error: (err) => {
        this.busyId = null;
        this.toast.error(err?.error?.detail || err?.error?.title || 'Commit failed');
      },
    });
  }

  async reverse(row: CtcPaymentRow): Promise<void> {
    if (this.rowStatus(row) !== 'committed') return;
    const ok = await this.confirm.ask({
      title: 'Reverse CTC payment',
      message: `This posts a compensating CTC REVERSAL and a CTC_OFFSET_REVERSAL transaction that restores ${row.amount} ${row.currencyCode} to the member's contribution ledger.`,
      confirmLabel: 'Reverse',
      danger: true,
    });
    if (!ok) return;

    const reason = window.prompt('Reason for the reversal (audit trail):');
    if (!reason || !reason.trim()) return;

    this.busyId = row.id;
    this.finance.reverseCtcPayment(row.id, { reason: reason.trim() }).subscribe({
      next: () => {
        this.busyId = null;
        this.banner = { kind: 'success', text: 'CTC reversed — compensating row posted' };
        this.fetchPage();
      },
      error: (err) => {
        this.busyId = null;
        this.toast.error(err?.error?.detail || err?.error?.title || 'Reversal failed');
      },
    });
  }

  /**
   * `status` was added by V069; historical rows before that migration only
   * carry `committed`. Derive a status when the projection is missing so the
   * action gates work regardless of when the row was created.
   */
  private rowStatus(row: CtcPaymentRow): 'draft' | 'committed' | 'reversed' {
    if (row.status) return row.status;
    return row.committed ? 'committed' : 'draft';
  }
}
