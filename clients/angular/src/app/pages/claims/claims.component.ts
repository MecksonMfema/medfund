import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { DataTableComponent, TableAction, TableColumn } from '../../shared/components/data-table/data-table.component';
import { Claim, ClaimsService } from '../../core/services/claims.service';
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
  claims: Claim[] = [];
  filtered: Claim[] = [];
  loading = false;
  pageSize = 20;
  sortKey = 'submissionDate';
  sortDirection: 'asc' | 'desc' = 'desc';

  typeTabs: TypeTab[] = [];
  activeType: string | null = null;
  showTabs = false;

  // Users with only `claims:view_drug` (no `claims:view`) get the row set
  // pinned to drug claims and the tab strip hidden — they never see medical.
  private drugOnly = false;

  columns: TableColumn[] = [
    { key: 'claimNumber',    label: 'Claim #',      sortable: true },
    { key: 'claimType',      label: 'Type',         sortable: true },
    { key: 'claimedAmount',  label: 'Amount',       sortable: true, type: 'currency' },
    { key: 'status',         label: 'Status',       type: 'status', sortable: true },
    { key: 'serviceDate',    label: 'Service Date', type: 'date',   sortable: true },
    { key: 'submissionDate', label: 'Submitted',    type: 'date',   sortable: true },
  ];

  // Row action list — flat because there's only one entry today. If a
  // second appears, filter through {@link rebuildActions} the way the
  // schemes page does. The View action is intentionally ungated: the
  // page-level route guard already established the caller's right to
  // see rows, and the detail-route guard accepts both view and view_drug.
  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: Claim) => this.router.navigate(['/tenant/claims', row.id]),
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
    this.fetchAll();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  selectType(value: string | null): void {
    if (this.activeType === value) return;
    this.activeType = value;
    this.applyFilter();
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
    this.applyFilter();
  }

  private fetchAll(): void {
    this.loading = true;
    this.claimsService.list().subscribe({
      next: (rows) => { this.claims = rows; this.applyFilter(); this.loading = false; },
      error: () => { this.claims = []; this.applyFilter(); this.loading = false; },
    });
  }

  private applyFilter(): void {
    let rows = this.claims;
    if (this.drugOnly) {
      rows = rows.filter(c => (c.claimType ?? '').toLowerCase() === 'drug');
    } else if (this.activeType) {
      rows = rows.filter(c => (c.claimType ?? '').toLowerCase() === this.activeType);
    }
    this.filtered = rows;
  }
}
