import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  ClaimRow,
  ClaimsService,
  PageResponse,
} from '../../../../core/services/claims.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-pending-claims-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './pending-claims-list.component.html',
  styleUrl: './pending-claims-list.component.scss',
})
export class PendingClaimsListComponent implements OnInit {
  rows: ClaimRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'submissionDate';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  // Preset (route-driven) — locks the status / claim-type filter when set so
  // the URL identity is preserved. Sub-routes (accepted, rejected, drug, etc.)
  // seed these via route data.
  presetStatus = '';
  presetClaimType = '';
  pageTitle = 'Pending claims';
  pageDescription = 'Claims queued for adjudication. Click any row to open the detail view and decide.';

  statusFilter = '';

  readonly statusFilterOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'SUBMITTED', label: 'Submitted' },
    { value: 'VERIFIED', label: 'Verified (ready)' },
    { value: 'IN_ADJUDICATION', label: 'In adjudication' },
    { value: 'ADJUDICATED', label: 'Adjudicated' },
    { value: 'REJECTED', label: 'Rejected' },
    { value: 'PENDING_INFO', label: 'Pending info' },
    { value: 'COMMITTED', label: 'Committed' },
    { value: 'PAID', label: 'Paid' },
    { value: 'CANCELLED', label: 'Cancelled' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'claimNumber',     label: 'Claim #',      sortable: true },
    { key: 'memberName',      label: 'Member',       sortable: true },
    { key: 'providerName',    label: 'Provider',     sortable: true },
    { key: 'claimType',       label: 'Type',         sortable: true, type: 'label' },
    { key: 'claimedAmount',   label: 'Claimed',      sortable: true, type: 'currency' },
    { key: 'approvedAmount',  label: 'Approved',     sortable: true, type: 'currency' },
    { key: 'status',          label: 'Status',       sortable: true, type: 'status' },
    { key: 'serviceDate',     label: 'Service date', sortable: true },
    { key: 'submissionDate',  label: 'Submitted',    sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: ClaimRow) => this.router.navigate(['/tenant/claims', row.id]),
    },
  ];

  constructor(private claims: ClaimsService, private route: ActivatedRoute, private router: Router) {}

  ngOnInit(): void {
    const data = this.route.snapshot.data;
    if (data['presetStatus']) {
      this.presetStatus = data['presetStatus'];
      this.statusFilter = this.presetStatus;
    } else if (data['presetClaimType']) {
      this.statusFilter = '';
    } else {
      this.statusFilter = 'VERIFIED'; // default to "ready for adjudication"
    }
    if (data['presetClaimType']) this.presetClaimType = data['presetClaimType'];
    if (data['title'])           this.pageTitle       = data['title'];
    if (data['description'])     this.pageDescription = data['description'];

    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.claims.listPaged({
      status: this.statusFilter || undefined,
      claimType: this.presetClaimType || undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: PageResponse<ClaimRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load claims';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onStatusChange(): void {
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
