import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ContributionsService, AgeGroup } from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import {
  EntityPickerComponent,
  EntityPickerSelection,
} from '../../../../shared/components/entity-picker/entity-picker.component';

/** How many schemes to preload in the picker dropdown so the operator
 *  sees a useful default list before typing. The first one is also auto-
 *  selected on page load. */
const PICKER_INITIAL_SCHEMES = 5;
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { PermissionService } from '../../../../core/security/permission.service';

/** Row shape rendered in the data-table — pre-formats the contribution
 *  amount so the table can stay declarative. */
interface AgeGroupRow extends AgeGroup {
  contributionDisplay: string;
}

@Component({
  selector: 'app-age-groups-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    IconComponent,
    EntityPickerComponent,
    DataTableComponent,
    HasPermissionDirective,
  ],
  templateUrl: './age-groups-list.component.html',
  styleUrl: './age-groups-list.component.scss',
})
export class AgeGroupsListComponent implements OnInit, OnDestroy {
  selectedSchemeId: string | null = null;
  selectedSchemeName: string | null = null;
  rows: AgeGroupRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  /** Top N schemes pre-loaded once on init — used both to pick a default
   *  scheme for the page and to seed the picker's empty-state dropdown. */
  initialSchemeSuggestions: EntityPickerSelection[] = [];

  ageGroupColumns: TableColumn[] = [
    { key: 'name',                label: 'Name',         sortable: true },
    { key: 'minAge',              label: 'Min age',      sortable: true },
    { key: 'maxAge',              label: 'Max age',      sortable: true },
    { key: 'contributionDisplay', label: 'Contribution', sortable: false },
    { key: 'status',              label: 'Status',       type: 'status', sortable: true },
  ];

  /** Full action list. {@link ageGroupActions} is the permission-filtered
   *  view rebuilt whenever the permission set changes. */
  private readonly allAgeGroupActions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      requiresPermission: 'billing:manage_age_groups',
      handler: (row: AgeGroupRow) => this.edit(row),
    },
    {
      label: 'Toggle',
      icon: 'pause-circle',
      color: 'default',
      requiresPermission: 'billing:manage_age_groups',
      labelFor: (row: AgeGroupRow) => row.status === 'inactive' ? 'Activate' : 'Deactivate',
      iconFor:  (row: AgeGroupRow) => row.status === 'inactive' ? 'play-circle' : 'pause-circle',
      colorFor: (row: AgeGroupRow) => row.status === 'inactive' ? 'success' : 'danger',
      handler: (row: AgeGroupRow) => this.toggleStatus(row),
    },
  ];

  ageGroupActions: TableAction[] = [];

  private subs: Subscription[] = [];
  private currencyFormatter = new CurrencyFormatPipe();

  constructor(
    private contributions: ContributionsService,
    private router: Router,
    private toast: ToastService,
    private permissions: PermissionService,
  ) {}

  ngOnInit(): void {
    this.subs.push(
      this.permissions.permissions$.subscribe(() => this.rebuildActions()),
    );
    this.rebuildActions();

    // Bootstrap the picker + auto-select the first scheme so the operator
    // lands on a populated table instead of an empty "pick a scheme" prompt.
    // We use the paged endpoint with size = PICKER_INITIAL_SCHEMES so we
    // only fetch what the picker actually shows by default (handles big
    // tenants without over-fetching).
    this.contributions.getSchemesPaged({
      page: 0,
      size: PICKER_INITIAL_SCHEMES,
      sortKey: 'name',
      sortDirection: 'asc',
      status: 'active',
    }).subscribe({
      next: (resp) => {
        this.initialSchemeSuggestions = resp.content.map(s => ({
          id: s.id,
          label: s.name,
          sublabel: s.schemeType ?? undefined,
        }));
        const first = this.initialSchemeSuggestions[0];
        if (first && !this.selectedSchemeId) {
          this.selectedSchemeId = first.id;
          this.selectedSchemeName = first.label;
          this.loadAgeGroups(first.id);
        }
      },
      error: () => { /* picker just stays empty — manual search still works */ },
    });
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  private rebuildActions(): void {
    this.ageGroupActions = this.allAgeGroupActions.filter(a =>
      !a.requiresPermission || this.permissions.has(a.requiresPermission),
    );
  }

  private edit(row: AgeGroupRow): void {
    this.router.navigate(['/tenant/billing/age-groups', row.id, 'edit']);
  }

  private toggleStatus(row: AgeGroupRow): void {
    const wantsActive = row.status === 'inactive';
    const note = wantsActive
      ? `Activate age group "${row.name}"? It will be used for new contributions again.`
      : `Deactivate age group "${row.name}"? It will stay on file but won't be used for new contributions.`;
    if (!confirm(note)) return;
    const stream = wantsActive
      ? this.contributions.activateAgeGroup(row.id)
      : this.contributions.deactivateAgeGroup(row.id);
    stream.subscribe({
      next: (updated) => {
        this.toast.success(`"${row.name}" ${updated.status === 'active' ? 'activated' : 'deactivated'}`);
        this.rows = this.rows.map(r => r.id === row.id ? { ...r, status: updated.status } : r);
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || `Could not ${wantsActive ? 'activate' : 'deactivate'} age group`);
      },
    });
  }

  onSchemePicked(selection: EntityPickerSelection | null): void {
    this.selectedSchemeId = selection?.id ?? null;
    this.selectedSchemeName = selection?.label ?? null;
    this.rows = [];
    if (selection?.id) {
      this.loadAgeGroups(selection.id);
    }
  }

  private loadAgeGroups(schemeId: string): void {
    this.loading = true;
    this.errorMessage = null;
    this.contributions.getAgeGroupsByScheme(schemeId).subscribe({
      next: (rows) => {
        this.rows = rows.map(r => this.decorate(r));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load age groups';
        this.loading = false;
      },
    });
  }

  private decorate(row: AgeGroup): AgeGroupRow {
    return {
      ...row,
      contributionDisplay: row.contributionAmount
        ? this.currencyFormatter.transform(row.contributionAmount, row.currencyCode)
        : '—',
    };
  }
}
