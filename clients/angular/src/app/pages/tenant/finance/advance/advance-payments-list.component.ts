import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  AdvancePaymentRow,
  FinancePageResponse,
  FinanceService,
} from '../../../../core/services/finance.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { PermissionService } from '../../../../core/security/permission.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

@Component({
  selector: 'app-advance-payments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, IconComponent, RouterLink],
  templateUrl: './advance-payments-list.component.html',
  styleUrl: './advance-payments-list.component.scss',
})
export class AdvancePaymentsListComponent implements OnInit {
  rows: AdvancePaymentRow[] = [];
  loading = false;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'recordedAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'providerName',  label: 'Provider',   sortable: true },
    { key: 'memberName',    label: 'Member',     sortable: true },
    { key: 'amount',        label: 'Amount',     sortable: true, type: 'currency' },
    { key: 'currencyCode',  label: 'Currency',   sortable: true },
    { key: 'status',        label: 'Status',     type: 'status' },
    { key: 'type',          label: 'Type' },
    { key: 'paymentMethod', label: 'Method' },
    { key: 'reference',     label: 'Reference' },
    { key: 'recordedAt',    label: 'Recorded',   sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: AdvancePaymentRow) => this.router.navigate(['/tenant/finance/payments/advance', row.id]),
    },
    {
      label: 'Approve',
      icon: 'check-circle',
      color: 'primary',
      visible: (row: AdvancePaymentRow) =>
        row.status === 'pending' && this.permissions.has('finance:approve_advance_payment'),
      handler: (row: AdvancePaymentRow) => this.approve(row),
    },
    {
      label: 'Reverse',
      icon: 'rotate-ccw',
      color: 'danger',
      visible: (row: AdvancePaymentRow) =>
        (row.status === 'approved' || row.status === 'applied')
        && row.type !== 'REVERSAL'
        && this.permissions.has('finance:reverse_advance_payment'),
      handler: (row: AdvancePaymentRow) => this.reverse(row),
    },
  ];

  constructor(
    private finance: FinanceService,
    private router: Router,
    private permissions: PermissionService,
    private toast: ToastService,
  ) {}

  get canManage(): boolean { return this.permissions.has('finance:manage_advance_payments'); }

  ngOnInit(): void {
    // Pick up any post-redirect toast from the form.
    const nav = this.router.getCurrentNavigation();
    const state = (nav?.extras?.state ?? window.history.state) as
      | { advanceBanner?: { kind: 'success' | 'info' | 'error'; text: string } }
      | null;
    const carry = state?.advanceBanner;
    if (carry) this.toast[carry.kind](carry.text);
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.finance.listAdvancePaymentsPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<AdvancePaymentRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Failed to load advance payments'));
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  approve(row: AdvancePaymentRow): void {
    if (!confirm(`Approve advance payment ${row.reference || row.id.substring(0, 8)}?`)) return;
    this.finance.approveAdvancePayment(row.id).subscribe({
      next: (saved) => {
        this.toast.success(`Advance approved: status is now ${saved.status}`);
        this.fetchPage();
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Failed to approve'));
      },
    });
  }

  reverse(row: AdvancePaymentRow): void {
    const reason = prompt(`Reverse advance payment ${row.reference || row.id.substring(0, 8)}?\n\nEnter reason:`);
    if (!reason || !reason.trim()) return;
    this.finance.reverseAdvancePayment(row.id, { reason: reason.trim() }).subscribe({
      next: (compensating) => {
        this.toast.success(
          `Reversal posted: compensating entry ${compensating.reference || compensating.id.substring(0, 8)}`,
        );
        this.fetchPage();
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Failed to reverse'));
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
}
