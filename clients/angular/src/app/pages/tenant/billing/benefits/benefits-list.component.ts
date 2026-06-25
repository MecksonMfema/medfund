import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  ContributionsService,
  Scheme,
  SchemeBenefit,
} from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { PermissionService } from '../../../../core/security/permission.service';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/** Row shape rendered in the data-table — extends the raw benefit with
 *  display-ready limit strings so the table can stay declarative. */
interface BenefitRow extends SchemeBenefit {
  annualLimitDisplay: string;
  dailyLimitDisplay: string;
  eventLimitDisplay: string;
}

@Component({
  selector: 'app-benefits-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    IconComponent,
    DataTableComponent,
    HasPermissionDirective,
  ],
  templateUrl: './benefits-list.component.html',
  styleUrl: './benefits-list.component.scss',
})
export class BenefitsListComponent implements OnInit, OnDestroy {
  schemeId = '';
  scheme: Scheme | null = null;
  benefits: BenefitRow[] = [];
  errorMessage: string | null = null;

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

  benefitColumns: TableColumn[] = [
    { key: 'name',               label: 'Name',          sortable: true },
    { key: 'benefitType',        label: 'Type',          type: 'label',  sortable: true },
    { key: 'annualLimitDisplay', label: 'Annual limit',  sortable: false },
    { key: 'dailyLimitDisplay',  label: 'Daily limit',   sortable: false },
    { key: 'eventLimitDisplay',  label: 'Event limit',   sortable: false },
    { key: 'waitingPeriodDays',  label: 'Waiting (days)', sortable: true },
    { key: 'status',             label: 'Status',        type: 'status', sortable: true },
  ];

  /** Full action list. {@link benefitActions} is the permission-filtered view
   *  rebuilt whenever the permission set changes. */
  private readonly allBenefitActions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      requiresPermission: 'billing:manage_schemes',
      handler: (row: BenefitRow) => this.edit(row),
    },
    {
      label: 'Toggle',
      icon: 'pause-circle',
      color: 'default',
      requiresPermission: 'billing:manage_schemes',
      labelFor: (row: BenefitRow) => row.status === 'inactive' ? 'Activate' : 'Deactivate',
      iconFor:  (row: BenefitRow) => row.status === 'inactive' ? 'play-circle' : 'pause-circle',
      colorFor: (row: BenefitRow) => row.status === 'inactive' ? 'success' : 'danger',
      handler: (row: BenefitRow) => this.toggleStatus(row),
    },
  ];

  benefitActions: TableAction[] = [];

  private subs: Subscription[] = [];
  private currencyFormatter = new CurrencyFormatPipe();

  constructor(
    private contributions: ContributionsService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
    private permissions: PermissionService,
  ) {}

  ngOnInit(): void {
    this.schemeId = this.route.snapshot.paramMap.get('schemeId') ?? '';
    if (!this.schemeId) {
      this.errorMessage = 'No scheme id in route';
      return;
    }
    this.subs.push(
      this.permissions.permissions$.subscribe(() => this.rebuildActions()),
    );
    this.rebuildActions();
    // Scheme metadata is loaded once for the page header / row decoration
    // (we still need the scheme's currency code for limit formatting).
    this.contributions.getSchemeById(this.schemeId).subscribe({
      next: (s) => (this.scheme = s),
      error: () => {},
    });
    this.fetchPage();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  private rebuildActions(): void {
    this.benefitActions = this.allBenefitActions.filter(a =>
      !a.requiresPermission || this.permissions.has(a.requiresPermission),
    );
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.contributions.getBenefitsBySchemePaged(this.schemeId, {
      page: this.page - 1,
      size: this.pageSize,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      q: this.searchTerm || undefined,
    }).subscribe({
      next: (resp) => {
        this.benefits   = resp.content.map(b => this.decorate(b));
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading    = false;
      },
      error: (err) => {
        this.benefits   = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.errorMessage = err?.error?.detail || 'Failed to load benefits';
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

  /** Pre-formats currency limits so the data-table can render them through
   *  the shared default cell renderer — keeps the dash-for-empty convention. */
  private decorate(b: SchemeBenefit): BenefitRow {
    const fmt = (amount?: string): string =>
      amount ? this.currencyFormatter.transform(amount, b.currencyCode) : '—';
    return {
      ...b,
      annualLimitDisplay: fmt(b.annualLimit),
      dailyLimitDisplay:  fmt(b.dailyLimit),
      eventLimitDisplay:  fmt(b.eventLimit),
    };
  }

  private edit(b: SchemeBenefit): void {
    this.router.navigate(['/tenant/billing/schemes', this.schemeId, 'benefits', b.id, 'edit']);
  }

  private toggleStatus(b: BenefitRow): void {
    const wantsActive = b.status === 'inactive';
    const note = wantsActive
      ? `Activate benefit "${b.name}"? It will be selectable for new claims again.`
      : `Deactivate benefit "${b.name}"? It will stay on file for historical claims but won't be picked up for new ones.`;
    if (!confirm(note)) return;
    const stream = wantsActive
      ? this.contributions.activateBenefit(b.id)
      : this.contributions.deactivateBenefit(b.id);
    stream.subscribe({
      next: (updated) => {
        // Patch the row in place — the rest of the page (count, sort) stays.
        this.benefits = this.benefits.map(x => x.id === b.id
          ? this.decorate({ ...x, status: updated.status })
          : x);
        this.toast.success(`"${b.name}" ${updated.status === 'active' ? 'activated' : 'deactivated'}`);
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || `Could not ${wantsActive ? 'activate' : 'deactivate'} benefit`);
      },
    });
  }
}
