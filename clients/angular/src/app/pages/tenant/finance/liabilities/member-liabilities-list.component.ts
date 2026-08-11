import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  MemberLiabilityRow,
  MemberLiabilityService,
} from '../../../../core/services/member-liability.service';
import { FinancePageResponse } from '../../../../core/services/finance.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

/**
 * V078 member cost-share liabilities list (Phase 4 copayments). Every row
 * represents one adjudicated claim's member share, drawn from the
 * finance-service {@code member_cost_share_liability} table.
 *
 * <p>Server-side paginated + KPI-free (per {@code feedback_stats_serverside};
 * no client aggregation — status filter and totals come from the endpoint).
 */
@Component({
  selector: 'app-member-liabilities-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './member-liabilities-list.component.html',
  styleUrl: './member-liabilities-list.component.scss',
})
export class MemberLiabilitiesListComponent implements OnInit {
  rows: MemberLiabilityRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  statusFilter: string = '';
  currencyFilter: string = '';

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly statusOptions: SelectOption[] = [
    { value: '',                  label: 'All statuses' },
    { value: 'OPEN',              label: 'Open' },
    { value: 'PARTIALLY_SETTLED', label: 'Partially settled' },
    { value: 'SETTLED',           label: 'Settled' },
    { value: 'WRITTEN_OFF',       label: 'Written off' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'memberName',    label: 'Member',      sortable: true },
    { key: 'memberNumber',  label: 'Member #',    sortable: true },
    { key: 'claimNumber',   label: 'Claim #',     sortable: true },
    { key: 'totalOwed',     label: 'Owed',        sortable: true, type: 'currency' },
    { key: 'totalSettled',  label: 'Settled',     sortable: true, type: 'currency' },
    { key: 'currencyCode',  label: 'Currency',    sortable: true },
    { key: 'status',        label: 'Status',      sortable: true, type: 'status' },
    { key: 'createdAt',     label: 'Created',     sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: MemberLiabilityRow) => this.router.navigate(['/tenant/finance/member-liabilities', row.id]),
    },
  ];

  constructor(
    private service: MemberLiabilityService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.service.listPaged({
      status: this.statusFilter || undefined,
      currencyCode: this.currencyFilter || undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: FinancePageResponse<MemberLiabilityRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load member liabilities';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.page = 1; this.fetchPage(); }
  onCurrencyChange(): void { this.page = 1; this.fetchPage(); }
  onPageChange(page: number): void { this.page = page; this.fetchPage(); }
  onSearchChange(term: string): void { this.searchTerm = term; this.page = 1; this.fetchPage(); }
  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }
}
