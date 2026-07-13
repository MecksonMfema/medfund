import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { DataTableComponent, TableAction, TableColumn } from '../../shared/components/data-table/data-table.component';
import { ClaimRow, ClaimsService, PageResponse } from '../../core/services/claims.service';
import { TenantService } from '../../core/services/tenant.service';
import { PermissionService } from '../../core/security/permission.service';

interface TypeTab {
  /** Lowercase claim_type filter value, or null for "All". */
  value: string | null;
  label: string;
}

@Component({
  selector: 'app-claims',
  standalone: true,
  imports: [CommonModule, DataTableComponent],
  templateUrl: './claims.component.html',
  styleUrl: './claims.component.scss',
})
export class ClaimsComponent implements OnInit, OnDestroy {
  rows: ClaimRow[] = [];
  loading = false;

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'submissionDate';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  typeTabs: TypeTab[] = [];
  activeType: string | null = null;
  showTabs = false;

  // Users with only `claims:view_drug` (no `claims:view`) get the row set
  // pinned to drug claims and the tab strip hidden — they never see medical.
  private drugOnly = false;

  columns: TableColumn[] = [
    { key: 'claimNumber',    label: 'Claim #',      sortable: true },
    { key: 'memberName',     label: 'Member',       sortable: true },
    { key: 'providerName',   label: 'Provider',     sortable: true },
    { key: 'claimType',      label: 'Type',         sortable: true },
    { key: 'claimedAmount',  label: 'Amount',       sortable: true, type: 'currency' },
    { key: 'status',         label: 'Status',       type: 'status', sortable: true },
    { key: 'serviceDate',    label: 'Service Date', sortable: true },
    { key: 'submissionDate', label: 'Submitted',    type: 'date',   sortable: true },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: ClaimRow) => this.router.navigate(['/tenant/claims', row.id]),
    },
  ];

  private subs: Subscription[] = [];

  constructor(
    private claimsService: ClaimsService,
    private tenantService: TenantService,
    private permissions: PermissionService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.subs.push(
      this.tenantService.tenant$.subscribe(() => this.rebuildTabs()),
      this.permissions.permissions$.subscribe(() => this.rebuildTabs()),
    );
    this.rebuildTabs();
    this.fetchPage();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  selectType(value: string | null): void {
    if (this.activeType === value) return;
    this.activeType = value;
    this.page = 1;
    this.fetchPage();
  }

  private rebuildTabs(): void {
    const tenant = this.tenantService.getTenant();
    // Drug claims are a HEALTH-line concept only. Even if a user has the
    // permission, the drug filter tab stays hidden for non-health tenants.
    const isHealth   = !!tenant?.insuranceLines?.includes('HEALTH');
    const canViewAll  = this.permissions.has('claims:view');
    const canViewDrug = this.permissions.has('claims:view_drug');

    this.drugOnly = !canViewAll && canViewDrug;

    const tabs: TypeTab[] = [];
    if (canViewAll) {
      tabs.push({ value: null,      label: 'All'     });
      tabs.push({ value: 'medical', label: 'Medical' });
    }
    if (canViewDrug && isHealth) {
      tabs.push({ value: 'drug', label: 'Drug' });
    }

    this.typeTabs = tabs;
    if (this.drugOnly) {
      this.activeType = 'drug';
      this.showTabs = false;
    } else {
      this.showTabs = tabs.length > 1;
      if (this.activeType && !tabs.some(t => t.value === this.activeType)) {
        this.activeType = null;
      }
    }
  }

  private fetchPage(): void {
    this.loading = true;
    this.claimsService.listPaged({
      claimType: this.activeType ?? undefined,
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
      error: () => {
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
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
