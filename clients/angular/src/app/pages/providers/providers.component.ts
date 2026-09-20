import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { DataTableComponent, TableAction } from '../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../shared/components/select/select.component';
import { ProvidersService, Provider, ProviderQueryParams, NetworkTier } from '../../core/services/providers.service';
import { AdminService, Tenant } from '../../core/services/admin.service';
import { INSURANCE_LINES } from '../../core/models/insurance-lines';
import { ToastService } from '../../shared/components/toast/toast.service';

@Component({
  selector: 'app-providers',
  standalone: true,
  imports: [CommonModule, FormsModule, StatCardComponent, DataTableComponent, IconComponent, SelectComponent],
  templateUrl: './providers.component.html',
  styleUrl: './providers.component.scss',
})
export class ProvidersComponent implements OnInit, OnDestroy {
  providers: Provider[] = [];
  totalCount = 0;
  totalPages = 1;
  currentPage = 1;
  loading = false;

  // Stats
  activeCount = 0;
  pendingCount = 0;
  suspendedCount = 0;

  // Filters
  searchTerm = '';
  statusFilter = '';
  typeFilter = '';

  readonly networkTierOptions = [
    { value: 'STANDARD', label: 'Standard' },
    { value: 'TIER_1',   label: 'Tier 1' },
    { value: 'TIER_2',   label: 'Tier 2' },
    { value: 'TIER_3',   label: 'Tier 3' },
  ];

  columns = [
    { key: 'name',             label: 'Name' },
    { key: 'providerType',     label: 'Type',        type: 'label' },
    { key: 'specialty',        label: 'Specialty' },
    { key: 'registrationNumber', label: 'Reg. / AHFOZ / Licence No.' },
    { key: 'email',            label: 'Email' },
    { key: 'city',             label: 'City' },
    { key: 'status',           label: 'Status',      type: 'status' },
    {
      key: 'networkTier',
      label: 'Network tier',
      type: 'select',
      options: this.networkTierOptions,
      onSelectChange: (row: Provider, value: string) =>
        this.onNetworkTierChange(row, value as NetworkTier),
    },
    {
      key: 'tenantIds',
      label: 'Tenants',
      type: 'textList',
      // The payload carries the UUID; the cell shows the tenant's name.
      labelFor: (id: string) => this.tenantName(id),
    },
    { key: 'insuranceLines', label: 'Lines', type: 'lineList' },
    { key: 'createdAt',        label: 'Registered',  type: 'date' },
  ];

  tableActions: TableAction[] = [
    {
      label: 'Tenants & lines',
      icon: 'layers',
      testid: 'manage-membership',
      handler: (row: Provider) => this.openMembershipModal(row),
    },
    {
      label: 'Verify',
      icon: 'check-circle',
      color: 'success',
      visible: (row: Provider) => row.status?.toLowerCase() === 'pending_verification' || row.status?.toLowerCase() === 'pending',
      handler: (row: Provider) => this.verifyProvider(row),
    },
    {
      label: 'Suspend',
      icon: 'pause-circle',
      color: 'warning',
      visible: (row: Provider) => row.status?.toLowerCase() === 'active',
      handler: (row: Provider) => this.suspendProvider(row),
    },
    {
      label: 'Activate',
      icon: 'play-circle',
      color: 'success',
      visible: (row: Provider) => row.status?.toLowerCase() === 'suspended',
      handler: (row: Provider) => this.activateProvider(row),
    },
  ];

  providerTypes = [
    { value: 'HEALTHCARE',  label: 'Healthcare' },
    { value: 'AUTOMOTIVE',  label: 'Automotive' },
    { value: 'LEGAL',       label: 'Legal' },
    { value: 'FUNERAL',     label: 'Funeral Services' },
    { value: 'FINANCIAL',   label: 'Financial Services' },
    { value: 'OTHER',       label: 'Other' },
  ];

  // Create modal
  showCreateModal = false;
  form = {
    name: '', providerType: '', specialty: '', registrationNumber: '',
    email: '', phone: '', city: '', address: '',
  };
  formErrors: Record<string, string> = {};
  formTouched: Record<string, boolean> = {};
  serverError = '';
  submitting = false;

  // ── SelectComponent options ─────────────────────────────────────────────
  // Empty-value entries dropped: filters use the toolbar-cell pattern where
  // "All" is a placeholder rather than a synthetic option. An empty string
  // still means "no filter".
  readonly statusFilterOptions: SelectOption[] = [
    { value: 'active',               label: 'Active' },
    { value: 'pending_verification', label: 'Pending verification' },
    { value: 'suspended',            label: 'Suspended' },
  ];
  get typeFilterOptions(): SelectOption[] {
    return this.providerTypes.map(t => ({ value: t.value, label: t.label }));
  }
  /** Same catalogue without the All row — used by the register-provider modal. */
  get providerTypeSelectOptions(): SelectOption[] {
    return this.providerTypes.map(t => ({ value: t.value, label: t.label }));
  }

  // ── Tenants & lines modal ─────────────────────────────────────────────
  // A provider row in public.providers does not make it usable by a tenant:
  // claims-service rejects a claim whose provider has no membership row for
  // the submitting tenant, or no tag for the claim's line. This modal is the
  // super-admin's only way to fix either.
  showMembershipModal = false;
  membershipProvider: Provider | null = null;
  membershipTenantIds = new Set<string>();
  membershipLines = new Set<string>();
  membershipBusy: Record<string, boolean> = {};
  membershipLoading = false;

  tenants: Tenant[] = [];
  readonly insuranceLines = INSURANCE_LINES.map(l => ({ value: l.value, label: l.label }));

  private searchSubject = new Subject<string>();
  private destroy$ = new Subject<void>();

  constructor(
    private providersService: ProvidersService,
    private adminService: AdminService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.searchSubject.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      takeUntil(this.destroy$),
    ).subscribe(() => this.resetAndLoad());

    // Loaded once: the tenant catalogue resolves the UUIDs on every row's
    // Tenants pill and populates the modal's toggle list.
    this.adminService.getTenants({ page: 1, size: 100 })
      .pipe(takeUntil(this.destroy$))
      .subscribe({ next: page => (this.tenants = page.content ?? []) });

    this.loadProviders(1);
  }

  /** Tenant display name for a UUID; falls back to a short id while loading. */
  tenantName(id: string): string {
    return this.tenants.find(t => t.id === id)?.name ?? id.slice(0, 8);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ── Data loading ──────────────────────────────────────────────────────────

  loadProviders(page: number): void {
    this.loading = true;
    this.currentPage = page;

    const params: ProviderQueryParams = { page, size: 20 };
    if (this.searchTerm.trim()) params.q           = this.searchTerm.trim();
    if (this.statusFilter)      params.status       = this.statusFilter;
    if (this.typeFilter)        params.providerType = this.typeFilter;

    this.providersService.query(params).subscribe({
      next: (result) => {
        this.providers   = result.content ?? [];
        this.totalCount  = result.totalCount ?? 0;
        this.totalPages  = result.totalPages ?? 1;
        this.activeCount  = this.providers.filter(p => p.status?.toLowerCase() === 'active').length;
        this.pendingCount = this.providers.filter(p =>
          p.status?.toLowerCase() === 'pending_verification' || p.status?.toLowerCase() === 'pending'
        ).length;
        this.suspendedCount = this.providers.filter(p => p.status?.toLowerCase() === 'suspended').length;
        this.loading = false;
      },
      error: () => { this.loading = false; },
    });
  }

  resetAndLoad(): void {
    this.loadProviders(1);
  }

  onSearchChange(value: string): void {
    this.searchTerm = value;
    this.searchSubject.next(value);
  }

  onFilterChange(): void {
    this.resetAndLoad();
  }

  onPageChange(page: number): void {
    this.loadProviders(page);
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  verifyProvider(provider: Provider): void {
    this.providersService.verify(provider.id).subscribe({ next: () => this.resetAndLoad() });
  }

  suspendProvider(provider: Provider): void {
    this.providersService.suspend(provider.id).subscribe({ next: () => this.resetAndLoad() });
  }

  activateProvider(provider: Provider): void {
    this.providersService.activate(provider.id).subscribe({ next: () => this.resetAndLoad() });
  }

  onNetworkTierChange(provider: Provider, value: NetworkTier): void {
    const previous = provider.networkTier;
    this.providersService.updateNetworkTier(provider.id, value).subscribe({
      next: () => this.toast.success(`Network tier updated for ${provider.name}`),
      error: (err) => {
        // Roll back the local row so the UI reflects backend state.
        provider.networkTier = previous;
        this.toast.error(err?.error?.detail || 'Failed to update network tier');
      },
    });
  }

  // ── Tenants & lines modal ─────────────────────────────────────────────────

  openMembershipModal(provider: Provider): void {
    this.membershipProvider = provider;
    this.membershipBusy = {};
    this.showMembershipModal = true;
    this.membershipLoading = true;
    // Read both junctions fresh rather than trusting the list payload: the
    // modal is where the operator acts on them, so a stale page is worse
    // here than one extra pair of requests.
    this.membershipTenantIds = new Set(provider.tenantIds ?? []);
    this.membershipLines = new Set(provider.insuranceLines ?? []);

    this.providersService.listMemberships(provider.id).subscribe({
      next: rows => {
        this.membershipTenantIds = new Set(rows.map(r => r.tenantId));
        this.membershipLoading = false;
      },
      error: () => { this.membershipLoading = false; },
    });
    this.providersService.listLines(provider.id).subscribe({
      next: lines => (this.membershipLines = new Set(lines)),
    });
  }

  closeMembershipModal(): void {
    this.showMembershipModal = false;
    this.membershipProvider = null;
    // The row's pills are rebuilt from the server so the table and the
    // junctions cannot drift apart.
    this.resetAndLoad();
  }

  isTenantLinked(tenantId: string): boolean {
    return this.membershipTenantIds.has(tenantId);
  }

  isLineTagged(line: string): boolean {
    return this.membershipLines.has(line);
  }

  isBusy(key: string): boolean {
    return !!this.membershipBusy[key];
  }

  toggleTenant(tenantId: string): void {
    const provider = this.membershipProvider;
    if (!provider || this.isBusy('t:' + tenantId)) return;
    const linked = this.membershipTenantIds.has(tenantId);
    this.membershipBusy['t:' + tenantId] = true;

    // Typed as unknown: link() resolves the created row and unlink() resolves
    // void, and neither payload is used here (the chip IS the state).
    const request: Observable<unknown> = linked
      ? this.providersService.unlink(provider.id, tenantId)
      : this.providersService.link(provider.id, tenantId);

    request.subscribe({
      next: () => {
        if (linked) this.membershipTenantIds.delete(tenantId);
        else this.membershipTenantIds.add(tenantId);
        this.membershipBusy['t:' + tenantId] = false;
        this.toast.success(
          `${provider.name} ${linked ? 'unlinked from' : 'linked to'} ${this.tenantName(tenantId)}`);
      },
      error: (err) => {
        this.membershipBusy['t:' + tenantId] = false;
        this.toast.error(err?.error?.detail || 'Failed to update tenant membership');
      },
    });
  }

  toggleLine(line: string): void {
    const provider = this.membershipProvider;
    if (!provider || this.isBusy('l:' + line)) return;
    const tagged = this.membershipLines.has(line);
    this.membershipBusy['l:' + line] = true;

    const request: Observable<void> = tagged
      ? this.providersService.removeLine(provider.id, line)
      : this.providersService.addLine(provider.id, line);

    request.subscribe({
      next: () => {
        if (tagged) this.membershipLines.delete(line);
        else this.membershipLines.add(line);
        this.membershipBusy['l:' + line] = false;
        this.toast.success(`${provider.name} ${tagged ? 'no longer serves' : 'now serves'} ${line}`);
      },
      error: (err) => {
        this.membershipBusy['l:' + line] = false;
        this.toast.error(err?.error?.detail || 'Failed to update insurance line');
      },
    });
  }

  // ── Create modal ──────────────────────────────────────────────────────────

  openCreateModal(): void {
    this.form = {
      name: '', providerType: '', specialty: '', registrationNumber: '',
      email: '', phone: '', city: '', address: '',
    };
    this.formErrors  = {};
    this.formTouched = {};
    this.serverError = '';
    this.showCreateModal = true;
  }

  touch(field: string): void {
    this.formTouched[field] = true;
    this.validate();
  }

  fieldError(field: string): string | null {
    return this.formTouched[field] && this.formErrors[field] ? this.formErrors[field] : null;
  }

  private validate(): boolean {
    const e: Record<string, string> = {};
    if (!this.form.name.trim())        e['name']         = 'Name is required';
    if (!this.form.providerType)       e['providerType'] = 'Provider type is required';
    if (this.form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.form.email)) {
      e['email'] = 'Enter a valid email address';
    }
    this.formErrors = e;
    return Object.keys(e).length === 0;
  }

  private validateAll(): boolean {
    ['name', 'providerType'].forEach(f => (this.formTouched[f] = true));
    return this.validate();
  }

  submitCreate(): void {
    if (!this.validateAll()) return;
    this.submitting  = true;
    this.serverError = '';

    this.providersService.onboard(this.form).subscribe({
      next: () => {
        this.showCreateModal = false;
        this.submitting      = false;
        this.resetAndLoad();
      },
      error: (err) => {
        this.serverError = err?.error?.message || 'Failed to register provider. Please try again.';
        this.submitting  = false;
      },
    });
  }
}
