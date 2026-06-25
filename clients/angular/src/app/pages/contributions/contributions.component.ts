import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { DataTableComponent, TableAction, TableColumn } from '../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { HasPermissionDirective } from '../../shared/directives/has-permission.directive';
import { ContributionsService, Scheme } from '../../core/services/contributions.service';
import { ToastService } from '../../shared/components/toast/toast.service';
import { TenantService } from '../../core/services/tenant.service';
import { PermissionService } from '../../core/security/permission.service';
import { schemeTypesForLines } from '../../core/models/insurance-lines';

interface SchemeTab {
  /** API filter value (scheme_type code) or null for the "All" tab. */
  value: string | null;
  label: string;
}

@Component({
  selector: 'app-contributions',
  standalone: true,
  imports: [CommonModule, RouterLink, DataTableComponent, IconComponent, HasPermissionDirective],
  templateUrl: './contributions.component.html',
  styleUrl: './contributions.component.scss',
})
export class ContributionsComponent implements OnInit, OnDestroy {
  schemes: Scheme[] = [];

  // Server-side pagination + sort state. Page is 1-indexed in the UI
  // (matches the data-table's serverPage input) and 0-indexed in the API.
  page = 1;
  pageSize = 20;
  totalCount = 0;
  totalPages = 1;
  loading = false;
  searchTerm = '';
  sortKey: string = 'name';
  sortDirection: 'asc' | 'desc' = 'asc';

  /** Insurance-line tabs derived from the tenant's enabled lines. Only
   *  rendered when the tenant has more than one line — single-line tenants
   *  get no useful filter from a one-option tab strip. */
  tabs: SchemeTab[] = [];
  activeTab: string | null = null;
  showTabs = false;

  schemeColumns: TableColumn[] = [
    { key: 'name',          label: 'Scheme Name',    sortable: true },
    { key: 'currencyCode',  label: 'Currency',       sortable: true },
    { key: 'status',        label: 'Status',         type: 'status', sortable: true },
    { key: 'effectiveDate', label: 'Effective Date', type: 'date',   sortable: true },
  ];

  /** Full action list. {@link schemeActions} is the permission-filtered view
   *  the template binds against, rebuilt whenever the permission set changes. */
  private readonly allSchemeActions: TableAction[] = [
    {
      label: 'Benefits',
      icon: 'clipboard-list',
      color: 'default',
      // Benefits is a navigation, not a mutation — read access is enough.
      requiresPermission: 'billing:view',
      handler: (row: Scheme) => this.router.navigate(['/tenant/billing/schemes', row.id, 'benefits']),
    },
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      requiresPermission: 'billing:manage_schemes',
      handler: (row: Scheme) => this.router.navigate(['/tenant/billing/schemes', row.id, 'edit']),
    },
    {
      // Label, icon, and color flip per row based on current status — one
      // action slot that always shows, never a row with no toggle option.
      label: 'Toggle',
      icon: 'pause-circle',
      color: 'default',
      requiresPermission: 'billing:manage_schemes',
      labelFor: (row: Scheme) => row.status === 'inactive' ? 'Activate' : 'Deactivate',
      iconFor:  (row: Scheme) => row.status === 'inactive' ? 'play-circle' : 'pause-circle',
      colorFor: (row: Scheme) => row.status === 'inactive' ? 'success' : 'danger',
      handler: (row: Scheme) => this.toggleSchemeStatus(row),
    },
  ];

  schemeActions: TableAction[] = [];

  private subs: Subscription[] = [];

  constructor(
    private contribService: ContributionsService,
    private router: Router,
    private toast: ToastService,
    private tenantService: TenantService,
    private permissions: PermissionService,
  ) {}

  private toggleSchemeStatus(row: Scheme): void {
    const wantsActive = row.status === 'inactive';
    const verb = wantsActive ? 'Activate' : 'Deactivate';
    const note = wantsActive
      ? `Activate scheme "${row.name}"? New contributions will start generating again.`
      : `Deactivate scheme "${row.name}"? Existing contributions, benefits, and claims stay on file but new ones won't be generated.`;
    if (!confirm(note)) return;
    const stream = wantsActive
      ? this.contribService.activateScheme(row.id)
      : this.contribService.deactivateScheme(row.id);
    stream.subscribe({
      next: (updated) => {
        this.toast.success(`"${row.name}" ${updated.status === 'active' ? 'activated' : 'deactivated'}`);
        this.schemes = this.schemes.map(s => s.id === row.id ? { ...s, status: updated.status } : s);
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || `Could not ${verb.toLowerCase()} scheme`);
      },
    });
  }

  ngOnInit(): void {
    // React to tenant changes (insurance lines) and permission changes (RBAC
    // gating of row actions) so reload-less tenant switches or role edits
    // keep the page coherent.
    this.subs.push(
      this.tenantService.tenant$.subscribe(t => this.rebuildTabs(t?.insuranceLines ?? [])),
      this.permissions.permissions$.subscribe(() => this.rebuildActions()),
    );
    this.rebuildActions();
    this.fetchPage();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  private rebuildTabs(lines: string[]): void {
    // Build one tab per scheme_type available to the tenant's configured
    // insurance lines (e.g. HEALTH → Medical aid, HMO, Wellness, …).
    // `schemeTypesForLines` falls back to the HEALTH catalogue when the
    // tenant has no lines set, which keeps the strip visible during setup.
    const types = schemeTypesForLines(lines);
    this.showTabs = types.length >= 1;
    this.tabs = [
      { value: null, label: 'All' },
      ...types.map(t => ({ value: t.code, label: t.label })),
    ];
    // If the active tab is no longer in the list (lines changed), fall back to All.
    if (this.activeTab && !types.some(t => t.code === this.activeTab)) {
      this.activeTab = null;
      this.fetchPage();
    }
  }

  private rebuildActions(): void {
    this.schemeActions = this.allSchemeActions.filter(a =>
      !a.requiresPermission || this.permissions.has(a.requiresPermission),
    );
  }

  selectTab(value: string | null): void {
    if (this.activeTab === value) return;
    this.activeTab = value;
    this.page = 1;
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.contribService.getSchemesPaged({
      page: this.page - 1,
      size: this.pageSize,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      q: this.searchTerm || undefined,
      schemeType: this.activeTab || undefined,
    }).subscribe({
      next: (resp) => {
        this.schemes    = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading    = false;
      },
      error: () => {
        this.schemes    = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading    = false;
      },
    });
  }

  onSort(e: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey       = e.key;
    this.sortDirection = e.direction;
    this.page          = 1;
    this.fetchPage();
  }

  onSearch(term: string): void {
    this.searchTerm = term;
    this.page       = 1;
    this.fetchPage();
  }

  onPageChange(page: number): void {
    this.page = page;
    this.fetchPage();
  }
}
