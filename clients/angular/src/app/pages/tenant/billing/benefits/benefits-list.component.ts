import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, forkJoin } from 'rxjs';
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
 *  display-ready fields so the table can stay declarative. */
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
  loading = false;
  errorMessage: string | null = null;

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
    this.refresh();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  private rebuildActions(): void {
    this.benefitActions = this.allBenefitActions.filter(a =>
      !a.requiresPermission || this.permissions.has(a.requiresPermission),
    );
  }

  refresh(): void {
    this.loading = true;
    this.errorMessage = null;
    forkJoin({
      scheme: this.contributions.getSchemeById(this.schemeId),
      benefits: this.contributions.getBenefitsByScheme(this.schemeId),
    }).subscribe({
      next: ({ scheme, benefits }) => {
        this.scheme = scheme;
        this.benefits = benefits.map(b => this.decorate(b));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load benefits';
        this.loading = false;
      },
    });
  }

  /** Pre-formats currency limits so the data-table can render them with the
   *  shared cell renderer — keeps the dash-for-empty convention. */
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
        this.benefits = this.benefits.map(x => x.id === b.id ? { ...x, status: updated.status } : x);
        this.toast.success(`"${b.name}" ${updated.status === 'active' ? 'activated' : 'deactivated'}`);
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || `Could not ${wantsActive ? 'activate' : 'deactivate'} benefit`);
      },
    });
  }
}
