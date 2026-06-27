import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { StatCardComponent } from '../../../shared/components/stat-card/stat-card.component';
import { DataTableComponent, TableAction } from '../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';
import { AdminService, Tenant } from '../../../core/services/admin.service';
import { TenantService } from '../../../core/services/tenant.service';
import { BrandingService } from '../../../core/services/branding.service';
import { beginImpersonation } from '../../../auth/keycloak.init';
import { INSURANCE_LINES, InsuranceLine, parseInsuranceLines, parseProviderRegLabel, deriveProviderRegLabel, insuranceLineLabel } from '../../../core/models/insurance-lines';

@Component({
  selector: 'app-tenants',
  standalone: true,
  imports: [CommonModule, FormsModule, StatCardComponent, DataTableComponent, IconComponent, SelectComponent],
  templateUrl: './tenants.component.html',
  styleUrl: './tenants.component.scss',
})
export class TenantsComponent implements OnInit, OnDestroy {
  tenants: Tenant[] = [];
  totalCount = 0;
  totalPages = 1;
  currentPage = 1;
  loading = false;
  showCreateModal = false;

  // Create form state
  form = { name: '', slug: '', email: '', country: '', model: '', insuranceLines: [] as string[] };
  formErrors: Record<string, string> = {};
  formTouched: Record<string, boolean> = {};
  serverError = '';
  submitting = false;

  // Edit form state
  showEditModal = false;
  editTenantId = '';
  editTenantSlug = '';        // read-only display
  editTenantCountry = '';     // read-only display
  editForm = { name: '', domain: '', email: '', timezone: '', model: '', insuranceLines: [] as string[], providerRegLabel: '' };
  editErrors: Record<string, string> = {};
  editTouched: Record<string, boolean> = {};
  editServerError = '';
  editSubmitting = false;

  // Insurance lines catalogue
  readonly insuranceLineOptions: InsuranceLine[] = INSURANCE_LINES;

  // Filter state
  searchTerm = '';
  statusFilter = '';
  modelFilter = '';
  lineFilter = '';

  columns = [
    { key: 'name',                  label: 'Name' },
    { key: 'status',                label: 'Status',           type: 'status' },
    { key: 'insuranceLinesDisplay', label: 'Insurance Lines' },
    { key: 'membershipModel',       label: 'Model',            type: 'label' },
    { key: 'contactEmail',          label: 'Contact' },
    { key: 'countryCode',           label: 'Country' },
    { key: 'createdAt',             label: 'Created',          type: 'date' },
  ];

  tableActions: TableAction[] = [
    {
      label: 'Tenant Portal',
      icon: 'external-link',
      color: 'default',
      handler: (row: Tenant) => this.enterTenantAdmin(row),
    },
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      handler: (row: Tenant) => this.openEditModal(row),
    },
    {
      label: 'Suspend',
      icon: 'pause-circle',
      color: 'warning',
      visible: (row: Tenant) => row.status?.toLowerCase() === 'active',
      handler: (row: Tenant) => this.suspendTenant(row),
    },
    {
      label: 'Reactivate',
      icon: 'play-circle',
      color: 'success',
      visible: (row: Tenant) => row.status?.toLowerCase() === 'suspended',
      handler: (row: Tenant) => this.activateTenant(row),
    },
  ];

  // ── SelectComponent options ─────────────────────────────────────────────
  readonly statusFilterOptions: SelectOption[] = [
    { value: '',          label: 'All statuses' },
    { value: 'active',    label: 'Active' },
    { value: 'suspended', label: 'Suspended' },
  ];
  readonly modelFilterOptions: SelectOption[] = [
    { value: '',           label: 'All models' },
    { value: 'individual', label: 'Individual' },
    { value: 'group',      label: 'Group' },
    { value: 'hybrid',     label: 'Hybrid' },
  ];
  get lineFilterOptions(): SelectOption[] {
    return [
      { value: '', label: 'All insurance lines' },
      ...this.insuranceLineOptions.map(l => ({ value: l.value, label: l.label })),
    ];
  }
  /** Membership model picker — shared by the create + edit modals. */
  readonly membershipModelOptions: SelectOption[] = [
    { value: 'INDIVIDUAL_ONLY', label: 'Individual only' },
    { value: 'GROUP_ONLY',      label: 'Group only' },
    { value: 'BOTH',            label: 'Both (individual & group)' },
  ];

  private searchSubject = new Subject<string>();
  private destroy$ = new Subject<void>();

  constructor(
    private adminService: AdminService,
    private tenantService: TenantService,
    private brandingService: BrandingService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.searchSubject.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      takeUntil(this.destroy$),
    ).subscribe(() => this.resetAndLoad());

    this.loadTenants(1);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get activeTenants(): number {
    return (this.tenants ?? []).filter((t) => t.status?.toLowerCase() === 'active').length;
  }

  get suspendedTenants(): number {
    return (this.tenants ?? []).filter((t) => t.status?.toLowerCase() === 'suspended').length;
  }

  onNameChange(value: string): void {
    this.form.name = value;
    this.form.slug = value
      .toLowerCase()
      .trim()
      .replace(/[^a-z0-9\s-]/g, '')
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-')
      .slice(0, 50);
    if (this.formTouched['name']) this.validateField('name');
    if (this.formTouched['slug']) this.validateField('slug');
  }

  toggleInsuranceLine(value: string): void {
    const idx = this.form.insuranceLines.indexOf(value);
    if (idx === -1) {
      this.form.insuranceLines = [...this.form.insuranceLines, value];
    } else {
      this.form.insuranceLines = this.form.insuranceLines.filter(v => v !== value);
    }
    if (this.formTouched['insuranceLines']) this.validateField('insuranceLines');
  }

  isLineSelected(value: string): boolean {
    return this.form.insuranceLines.includes(value);
  }

  openCreateModal(): void {
    this.form = { name: '', slug: '', email: '', country: '', model: '', insuranceLines: [] };
    this.formErrors = {};
    this.formTouched = {};
    this.serverError = '';
    this.submitting = false;
    this.showCreateModal = true;
  }

  touch(field: string): void {
    this.formTouched[field] = true;
    this.validateField(field);
  }

  validateField(field: string): void {
    const v = this.form as any;
    switch (field) {
      case 'name':
        this.formErrors['name'] = v.name.trim() ? '' : 'Organization name is required.';
        break;
      case 'slug':
        if (!v.slug.trim()) {
          this.formErrors['slug'] = 'Slug is required.';
        } else if (!/^[a-z0-9-]+$/.test(v.slug)) {
          this.formErrors['slug'] = 'Slug may only contain lowercase letters, numbers and hyphens.';
        } else if (v.slug.length < 2) {
          this.formErrors['slug'] = 'Slug must be at least 2 characters.';
        } else {
          this.formErrors['slug'] = '';
        }
        break;
      case 'email':
        if (v.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email)) {
          this.formErrors['email'] = 'Enter a valid email address.';
        } else {
          this.formErrors['email'] = '';
        }
        break;
      case 'country':
        if (v.country && !/^[A-Za-z]{2}$/.test(v.country)) {
          this.formErrors['country'] = 'Enter a 2-letter country code (e.g. ZW).';
        } else {
          this.formErrors['country'] = '';
        }
        break;
      case 'model':
        this.formErrors['model'] = v.model ? '' : 'Please select a membership model.';
        break;
      case 'insuranceLines':
        this.formErrors['insuranceLines'] = v.insuranceLines.length > 0
          ? '' : 'Select at least one insurance line.';
        break;
    }
  }

  private validateAll(): boolean {
    ['name', 'slug', 'email', 'country', 'model', 'insuranceLines'].forEach(f => {
      this.formTouched[f] = true;
      this.validateField(f);
    });
    return Object.values(this.formErrors).every(e => !e);
  }

  fieldError(field: string): string {
    return this.formTouched[field] ? (this.formErrors[field] ?? '') : '';
  }

  onSearchChange(value: string): void {
    this.searchTerm = value;
    this.searchSubject.next(value);
  }

  onFilterChange(): void {
    this.resetAndLoad();
  }

  onPageChange(page: number): void {
    this.loadTenants(page);
  }

  resetAndLoad(): void {
    this.currentPage = 1;
    this.loadTenants(1);
  }

  loadTenants(page: number): void {
    this.loading = true;
    this.adminService.getTenants({
      q: this.searchTerm || undefined,
      status: this.statusFilter || undefined,
      membershipModel: this.modelFilter || undefined,
      page,
      size: 20,
    }).subscribe({
      next: (p) => {
        let rows = (p.content ?? []).map(t => ({
          ...t,
          insuranceLinesDisplay: parseInsuranceLines(t.settings)
            .map(v => insuranceLineLabel(v))
            .join(', ') || '—',
        }));
        // Client-side insurance line filter (backend JSONB filter support pending)
        if (this.lineFilter) {
          rows = rows.filter(t => parseInsuranceLines(t.settings).includes(this.lineFilter));
        }
        this.tenants = rows;
        this.totalCount = p.totalCount ?? 0;
        this.totalPages = p.totalPages ?? 1;
        this.currentPage = p.page ?? 1;
        this.loading = false;
      },
      error: () => {
        this.tenants = [];
        this.loading = false;
      },
    });
  }

  onTenantClick(tenant: Tenant): void {
    this.enterTenantAdmin(tenant);
  }

  enterTenantAdmin(tenant: Tenant): void {
    // Emit IMPERSONATION_START so the target tenant's audit trail records the
    // super admin's entry. Fire-and-forget — navigation must not block on the
    // audit pipeline.
    beginImpersonation(tenant.id, tenant.name);

    // Fetch full tenant data (including branding) before navigating
    this.adminService.getTenantById(tenant.id).subscribe({
      next: (full) => {
        const lines = parseInsuranceLines(full.settings);
        this.tenantService.setTenant({
          id: full.id,
          name: full.name,
          slug: full.slug,
          status: full.status,
          branding: this.brandingService.parseBranding(full.branding),
          timezone: full.timezone ?? '',
          insuranceLines: lines,
          providerRegLabel: parseProviderRegLabel(full.settings) || deriveProviderRegLabel(lines),
        });
        this.router.navigate(['/tenant/admin/dashboard']);
      },
      error: () => {
        const lines = parseInsuranceLines((tenant as any).settings);
        this.tenantService.setTenant({
          id: tenant.id,
          name: tenant.name,
          slug: tenant.slug,
          status: tenant.status,
          branding: { templateId: 'platform' },
          timezone: '',
          insuranceLines: lines,
          providerRegLabel: deriveProviderRegLabel(lines),
        });
        this.router.navigate(['/tenant/admin/dashboard']);
      },
    });
  }

  suspendTenant(tenant: Tenant): void {
    this.adminService.suspendTenant(tenant.id).subscribe({
      next: () => this.resetAndLoad(),
      error: () => {},
    });
  }

  activateTenant(tenant: Tenant): void {
    this.adminService.activateTenant(tenant.id).subscribe({
      next: () => this.resetAndLoad(),
      error: () => {},
    });
  }

  // ── Edit ────────────────────────────────────────────────────────────────────

  openEditModal(tenant: Tenant): void {
    this.editTenantId      = tenant.id;
    this.editTenantSlug    = tenant.slug;
    this.editTenantCountry = tenant.countryCode;
    this.editErrors        = {};
    this.editTouched       = {};
    this.editServerError   = '';
    this.editSubmitting    = false;
    const lines = parseInsuranceLines(tenant.settings);
    this.editForm = {
      name:             tenant.name ?? '',
      domain:           tenant.domain ?? '',
      email:            tenant.contactEmail ?? '',
      timezone:         tenant.timezone ?? '',
      model:            tenant.membershipModel ?? '',
      insuranceLines:   lines,
      providerRegLabel: parseProviderRegLabel(tenant.settings) || deriveProviderRegLabel(lines),
    };
    this.showEditModal = true;
  }

  touchEdit(field: string): void {
    this.editTouched[field] = true;
    this.validateEditField(field);
  }

  editFieldError(field: string): string {
    return this.editTouched[field] ? (this.editErrors[field] ?? '') : '';
  }

  toggleEditInsuranceLine(value: string): void {
    const idx = this.editForm.insuranceLines.indexOf(value);
    if (idx === -1) {
      this.editForm.insuranceLines = [...this.editForm.insuranceLines, value];
    } else {
      this.editForm.insuranceLines = this.editForm.insuranceLines.filter(v => v !== value);
    }
    if (this.editTouched['insuranceLines']) this.validateEditField('insuranceLines');
  }

  isEditLineSelected(value: string): boolean {
    return this.editForm.insuranceLines.includes(value);
  }

  validateEditField(field: string): void {
    const v = this.editForm as any;
    switch (field) {
      case 'name':
        this.editErrors['name'] = v.name.trim() ? '' : 'Name is required.';
        break;
      case 'email':
        if (v.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email)) {
          this.editErrors['email'] = 'Enter a valid email address.';
        } else {
          this.editErrors['email'] = '';
        }
        break;
      case 'insuranceLines':
        this.editErrors['insuranceLines'] = v.insuranceLines.length > 0
          ? '' : 'Select at least one insurance line.';
        break;
    }
  }

  private validateAllEdit(): boolean {
    ['name', 'email', 'insuranceLines'].forEach(f => {
      this.editTouched[f] = true;
      this.validateEditField(f);
    });
    return Object.values(this.editErrors).every(e => !e);
  }

  saveTenant(): void {
    if (!this.validateAllEdit()) return;
    this.editSubmitting  = true;
    this.editServerError = '';

    this.adminService.updateTenant(this.editTenantId, {
      name:            this.editForm.name || undefined,
      domain:          this.editForm.domain || undefined,
      contactEmail:    this.editForm.email || undefined,
      timezone:        this.editForm.timezone || undefined,
      membershipModel: this.editForm.model || undefined,
      settings:        JSON.stringify({
        insuranceLines:   this.editForm.insuranceLines,
        // Use the custom label if the admin changed it; otherwise re-derive from lines
        providerRegLabel: this.editForm.providerRegLabel.trim()
          || deriveProviderRegLabel(this.editForm.insuranceLines),
      }),
    }).subscribe({
      next: () => {
        this.editSubmitting = false;
        this.showEditModal  = false;
        this.resetAndLoad();
      },
      error: (err) => {
        this.editSubmitting  = false;
        this.editServerError = err?.error?.message ?? err?.error?.detail ?? 'Failed to update tenant. Please try again.';
      },
    });
  }

  // ── Create ───────────────────────────────────────────────────────────────────

  createTenant(): void {
    if (!this.validateAll()) return;
    this.submitting = true;
    this.serverError = '';
    this.adminService
      .createTenant({
        name: this.form.name,
        slug: this.form.slug,
        contactEmail: this.form.email || undefined,
        countryCode: this.form.country || undefined,
        membershipModel: this.form.model,
        settings: JSON.stringify({
          insuranceLines: this.form.insuranceLines,
          providerRegLabel: deriveProviderRegLabel(this.form.insuranceLines),
        }),
      })
      .subscribe({
        next: () => {
          this.submitting = false;
          this.showCreateModal = false;
          this.resetAndLoad();
        },
        error: (err) => {
          this.submitting = false;
          const body = err?.error;
          if (body?.detail) {
            this.serverError = body.detail;
          } else if (body?.message) {
            this.serverError = body.message;
          } else if (err?.status === 409) {
            this.serverError = 'A tenant with this slug already exists.';
            this.formErrors['slug'] = 'Slug is already taken.';
          } else {
            this.serverError = 'Something went wrong. Please try again.';
          }
        },
      });
  }
}
