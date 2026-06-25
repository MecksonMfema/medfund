import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { DataTableComponent, TableAction } from '../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../shared/components/select/select.component';
import { ProvidersService, Provider, ProviderQueryParams } from '../../core/services/providers.service';

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

  columns = [
    { key: 'name',             label: 'Name' },
    { key: 'providerType',     label: 'Type',        type: 'label' },
    { key: 'specialty',        label: 'Specialty' },
    { key: 'registrationNumber', label: 'Reg. / AHFOZ / Licence No.' },
    { key: 'email',            label: 'Email' },
    { key: 'city',             label: 'City' },
    { key: 'status',           label: 'Status',      type: 'status' },
    { key: 'createdAt',        label: 'Registered',  type: 'date' },
  ];

  tableActions: TableAction[] = [
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
  readonly statusFilterOptions: SelectOption[] = [
    { value: '',                     label: 'All statuses' },
    { value: 'active',               label: 'Active' },
    { value: 'pending_verification', label: 'Pending verification' },
    { value: 'suspended',            label: 'Suspended' },
  ];
  /** Provider-type catalogue, with a leading "All types" row for the filter chip. */
  get typeFilterOptions(): SelectOption[] {
    return [
      { value: '', label: 'All types' },
      ...this.providerTypes.map(t => ({ value: t.value, label: t.label })),
    ];
  }
  /** Same catalogue without the All row — used by the register-provider modal. */
  get providerTypeSelectOptions(): SelectOption[] {
    return this.providerTypes.map(t => ({ value: t.value, label: t.label }));
  }

  private searchSubject = new Subject<string>();
  private destroy$ = new Subject<void>();

  constructor(private providersService: ProvidersService) {}

  ngOnInit(): void {
    this.searchSubject.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      takeUntil(this.destroy$),
    ).subscribe(() => this.resetAndLoad());

    this.loadProviders(1);
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
