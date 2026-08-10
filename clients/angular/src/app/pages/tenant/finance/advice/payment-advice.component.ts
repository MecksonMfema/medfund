import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  FinancePageResponse,
  FinanceService,
  PaymentAdviceRow,
} from '../../../../core/services/finance.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-payment-advice',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './payment-advice.component.html',
  styleUrl: './payment-advice.component.scss',
})
export class PaymentAdviceComponent implements OnInit {
  rows: PaymentAdviceRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  pageTitle = 'Payment advices';
  pageDescription = 'Per-payee ledger of every payment run. Advices are generated '
    + 'automatically when a run executes. Click through to see the typed line items '
    + '(carry-forward, claims paid, CTC offsets, advance drawdowns, tax withheld, '
    + 'shortfalls) and net due.';

  payeeTypeFilter: 'PROVIDER' | 'MEMBER' | '' = '';
  statusFilter: 'generated' | 'sent' | 'failed' | '' = '';
  /** Month picker value in `YYYY-MM` form (native `<input type="month">`). */
  monthFilter = '';

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'issuedAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly payeeTypeOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'PROVIDER', label: 'Provider' },
    { value: 'MEMBER', label: 'Member' },
  ];

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'generated', label: 'Generated' },
    { value: 'sent', label: 'Sent' },
    { value: 'failed', label: 'Failed' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'adviceNumber',  label: 'Advice #',  sortable: true },
    { key: 'payeeName',     label: 'Payee' },
    { key: 'payeeType',     label: 'Payee type', sortable: true, type: 'label' },
    { key: 'runNumber',     label: 'Run #',      sortable: true },
    { key: 'status',        label: 'Status',     sortable: true, type: 'status' },
    { key: 'netDueAmount',  label: 'Net due',    sortable: true, type: 'currency' },
    { key: 'issuedAt',      label: 'Issued',     sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: PaymentAdviceRow) => this.router.navigate(['/tenant/finance/advices', row.id]),
    },
  ];

  constructor(private finance: FinanceService, private router: Router) {}

  ngOnInit(): void {
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    const bounds = this.monthFilter ? monthToPeriodBounds(this.monthFilter) : null;
    this.finance.listAdvicesPaged({
      payeeType: this.payeeTypeFilter || undefined,
      status: this.statusFilter || undefined,
      periodStart: bounds?.start,
      periodEnd: bounds?.end,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<PaymentAdviceRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load payment advices';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onFilterChange(): void {
    this.page = 1;
    this.fetchPage();
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

/**
 * Translate the browser's `<input type="month">` value (`YYYY-MM`) into
 * the inclusive ISO-8601 bounds the API expects. UTC — matches how
 * period_end_at is stored server-side.
 */
function monthToPeriodBounds(month: string): { start: string; end: string } | null {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m) return null;
  const year = Number(m[1]);
  const monthIdx = Number(m[2]) - 1;
  const start = new Date(Date.UTC(year, monthIdx, 1, 0, 0, 0, 0));
  const end = new Date(Date.UTC(year, monthIdx + 1, 0, 23, 59, 59, 999));
  return { start: start.toISOString(), end: end.toISOString() };
}
