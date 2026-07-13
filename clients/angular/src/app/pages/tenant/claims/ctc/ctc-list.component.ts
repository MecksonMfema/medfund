import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  CtcPaymentRow,
  FinancePageResponse,
  FinanceService,
} from '../../../../core/services/finance.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-ctc-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    IconComponent,
    HasPermissionDirective,
    DataTableComponent,
  ],
  templateUrl: './ctc-list.component.html',
  styleUrl: './ctc-list.component.scss',
})
export class CtcListComponent implements OnInit {
  rows: CtcPaymentRow[] = [];
  loading = false;
  busyId: string | null = null;
  committed = false;

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
    { key: 'groupName',    label: 'Group',        sortable: true },
    { key: 'amount',       label: 'Amount',       sortable: true, type: 'currency' },
    { key: 'currencyCode', label: 'Currency',     sortable: true },
    { key: 'committed',    label: 'Committed',    sortable: true, type: 'boolean' },
    { key: 'createdAt',    label: 'Created',      sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Commit',
      icon: 'check',
      color: 'success',
      requiresPermission: 'claims:commit_ctc_payment',
      visible: (row: CtcPaymentRow) => !row.committed,
      labelFor: (row: CtcPaymentRow) => this.busyId === row.id ? 'Committing…' : 'Commit',
      handler: (row: CtcPaymentRow) => this.commit(row),
    },
  ];

  constructor(
    private finance: FinanceService,
    private confirm: ConfirmService,
    private toast: ToastService,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.committed = !!this.route.snapshot.data?.['committed'];
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.finance.listCtcPaymentsPaged({
      committed: this.committed,
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
    if (row.committed) return;
    const ok = await this.confirm.ask({
      title: 'Commit CTC payment',
      message: `Commit ${row.amount} ${row.currencyCode} for this allocation? This locks it against edits.`,
      confirmLabel: 'Commit',
    });
    if (!ok) return;

    this.busyId = row.id;
    this.finance.commitCtcPayment(row.id).subscribe({
      next: () => {
        this.busyId = null;
        this.toast.success('CTC payment committed');
        // Re-fetch the current page so the row moves out of the pending
        // list (or lands on committed with its new state).
        this.fetchPage();
      },
      error: (err) => {
        this.busyId = null;
        this.toast.error(err?.error?.detail || 'Commit failed');
      },
    });
  }
}
