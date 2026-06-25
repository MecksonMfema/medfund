import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { DataTableComponent, TableAction } from '../../shared/components/data-table/data-table.component';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../shared/components/select/select.component';
import { AdminService, StaffUser } from '../../core/services/admin.service';
import { TenantService } from '../../core/services/tenant.service';
import { BrandingService, TenantBranding, TenantTemplate, TENANT_TEMPLATES } from '../../core/services/branding.service';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DataTableComponent, StatCardComponent, IconComponent, SelectComponent],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.scss',
})
export class AdminComponent implements OnInit {
  tenantName = '';

  // ── User stats ────────────────────────────────────────────────────────────
  totalUsers = 0;
  activeUsers = 0;
  suspendedUsers = 0;
  pendingUsers = 0;

  // ── Users ─────────────────────────────────────────────────────────────────
  users: StaffUser[] = [];
  usersLoading = false;
  showCreateModal = false;
  createForm = { firstName: '', lastName: '', email: '', jobTitle: '', department: '', realmRole: '' };
  createErrors: Record<string, string> = {};
  createSubmitting = false;

  userColumns = [
    { key: 'firstName',   label: 'First Name' },
    { key: 'lastName',    label: 'Last Name' },
    { key: 'email',       label: 'Email' },
    { key: 'jobTitle',    label: 'Job Title' },
    { key: 'realmRole',   label: 'Role',   type: 'label' },
    { key: 'status',      label: 'Status', type: 'status' },
    { key: 'createdAt',   label: 'Created', type: 'date' },
  ];

  userActions: TableAction[] = [
    {
      label: 'Suspend',
      icon: 'pause-circle',
      color: 'warning',
      visible: (u: StaffUser) => u.status === 'active',
      handler: (u: StaffUser) => this.suspendUser(u),
    },
    {
      label: 'Activate',
      icon: 'play-circle',
      color: 'success',
      visible: (u: StaffUser) => u.status === 'suspended',
      handler: (u: StaffUser) => this.activateUser(u),
    },
    {
      label: 'Resend invite',
      icon: 'mail',
      color: 'default',
      visible: (u: StaffUser) => u.status === 'invited' || u.status === 'pending',
      handler: (u: StaffUser) => this.resendInvite(u),
    },
  ];

  availableRoles = [
    { value: 'tenant_admin',     label: 'Tenant Admin' },
    { value: 'claims_clerk',     label: 'Claims Clerk' },
    { value: 'finance_officer',  label: 'Finance Officer' },
    { value: 'operations_staff', label: 'Operations Staff' },
  ];

  // ── Audit logs ────────────────────────────────────────────────────────────
  auditEvents: any[] = [];
  auditTotal = 0;
  auditPage = 1;
  auditLoading = false;
  auditSearch = '';

  auditColumns = [
    { key: '_entityDisplayFormatted', label: 'Entity' },
    { key: 'action',                  label: 'Action' },
    { key: '_actorDisplayFormatted',  label: 'Actor' },
    { key: 'timestamp',               label: 'Time', type: 'date' },
  ];

  // ── Branding ──────────────────────────────────────────────────────────────
  templates: TenantTemplate[] = TENANT_TEMPLATES;
  branding: TenantBranding = { templateId: 'platform' };
  brandingSaving = false;
  brandingSaved = false;

  /** Realm role picker — used by the legacy Add user modal. */
  get availableRoleOptions(): SelectOption[] {
    return this.availableRoles.map(r => ({ value: r.value, label: r.label }));
  }

  constructor(
    private adminService: AdminService,
    private tenantService: TenantService,
    private brandingService: BrandingService,
  ) {}

  ngOnInit(): void {
    const tenant = this.tenantService.getTenant();
    this.tenantName = tenant?.name ?? '';
    if (tenant?.branding) {
      this.branding = { ...tenant.branding };
    }

    this.loadUsers();
    this.loadAudit();
  }

  // ── Users ─────────────────────────────────────────────────────────────────

  loadUsers(): void {
    this.usersLoading = true;
    this.adminService.getStaffUsers().subscribe({
      next: (users) => {
        this.users = users;
        this.totalUsers     = users.length;
        this.activeUsers    = users.filter(u => u.status === 'active').length;
        this.suspendedUsers = users.filter(u => u.status === 'suspended').length;
        this.pendingUsers   = users.filter(u =>
          u.status === 'invited' || u.status === 'pending'
        ).length;
        this.usersLoading = false;
      },
      error: () => { this.usersLoading = false; },
    });
  }

  openCreateModal(): void {
    this.createForm   = { firstName: '', lastName: '', email: '', jobTitle: '', department: '', realmRole: '' };
    this.createErrors = {};
    this.showCreateModal = true;
  }

  submitCreateUser(): void {
    this.createErrors = {};
    if (!this.createForm.firstName.trim()) this.createErrors['firstName'] = 'Required';
    if (!this.createForm.lastName.trim())  this.createErrors['lastName']  = 'Required';
    if (!this.createForm.email.trim())     this.createErrors['email']     = 'Required';
    if (!this.createForm.realmRole)        this.createErrors['realmRole'] = 'Required';
    if (Object.keys(this.createErrors).length) return;

    this.createSubmitting = true;
    this.adminService.createStaffUser(this.createForm).subscribe({
      next: () => {
        this.showCreateModal  = false;
        this.createSubmitting = false;
        this.loadUsers();
      },
      error: () => { this.createSubmitting = false; },
    });
  }

  suspendUser(user: StaffUser): void {
    this.adminService.suspendStaffUser(user.id).subscribe({ next: () => this.loadUsers() });
  }

  activateUser(user: StaffUser): void {
    this.adminService.activateStaffUser(user.id).subscribe({ next: () => this.loadUsers() });
  }

  resendInvite(user: StaffUser): void {
    this.adminService.resendStaffInvite(user.id).subscribe();
  }

  // ── Audit ─────────────────────────────────────────────────────────────────

  loadAudit(): void {
    this.auditLoading = true;
    const params: Record<string, string> = {
      page: this.auditPage.toString(),
      size: '20',
    };
    if (this.auditSearch.trim()) params['q'] = this.auditSearch.trim();

    const tenantId = this.tenantService.getTenantId() || undefined;
    this.adminService.getAuditEvents(params, tenantId).subscribe({
      next: (a) => {
        this.auditEvents = (a.events || []).map((e: any) => {
          const entityRaw = e.entityName || e.entityId;
          const actorRaw  = e.actorEmail  || e.actorId;
          return {
            ...e,
            _entityDisplay: entityRaw,
            _actorDisplay:  actorRaw,
            _entityDisplayFormatted: this.formatDisplay(entityRaw),
            _actorDisplayFormatted:  this.formatDisplay(actorRaw),
          };
        });
        this.auditTotal   = a.total || 0;
        this.auditLoading = false;
      },
      error: () => { this.auditLoading = false; },
    });
  }

  onAuditSearch(): void {
    this.auditPage = 1;
    this.loadAudit();
  }

  onAuditPageChange(page: number): void {
    this.auditPage = page;
    this.loadAudit();
  }

  // ── Branding ──────────────────────────────────────────────────────────────

  selectTemplate(id: string): void {
    this.branding = { ...this.branding, templateId: id };
    this.previewBranding();
  }

  onColorChange(): void {
    this.previewBranding();
  }

  clearColor(field: 'primaryColor' | 'accentColor' | 'sidebarBg'): void {
    this.branding = { ...this.branding, [field]: undefined };
    this.previewBranding();
  }

  clearLogo(): void {
    this.branding = { ...this.branding, logoUrl: undefined };
    this.previewBranding();
  }

  saveBranding(): void {
    const tenant = this.tenantService.getTenant();
    if (!tenant) return;

    this.brandingSaving = true;
    this.brandingSaved  = false;

    const sanitized   = this.sanitizeBranding(this.branding);
    const brandingJson = JSON.stringify(sanitized);

    this.adminService.updateTenant(tenant.id, { branding: brandingJson }).subscribe({
      next: () => {
        this.tenantService.setTenant({ ...tenant, branding: sanitized });
        this.branding       = sanitized;
        this.brandingSaving = false;
        this.brandingSaved  = true;
        setTimeout(() => (this.brandingSaved = false), 3000);
      },
      error: () => { this.brandingSaving = false; },
    });
  }

  private previewBranding(): void {
    const tenant = this.tenantService.getTenant();
    if (tenant) this.tenantService.setTenant({ ...tenant, branding: this.branding });
  }

  formatDisplay(value: string | null | undefined): string {
    if (!value) return '—';
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    return uuidPattern.test(value.trim()) ? '—' : value;
  }

  private sanitizeBranding(b: TenantBranding): TenantBranding {
    return {
      templateId:   this.sanitizeText(b.templateId),
      logoUrl:      b.logoUrl      ? this.sanitizeUrl(b.logoUrl)       : undefined,
      primaryColor: b.primaryColor ? this.sanitizeColor(b.primaryColor) : undefined,
      accentColor:  b.accentColor  ? this.sanitizeColor(b.accentColor)  : undefined,
      sidebarBg:    b.sidebarBg    ? this.sanitizeColor(b.sidebarBg)    : undefined,
    };
  }

  private sanitizeColor(v: string): string | undefined {
    return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(v.trim()) ? v.trim() : undefined;
  }

  private sanitizeUrl(v: string): string | undefined {
    const t = v.trim();
    return t.startsWith('https://') ? t : undefined;
  }

  private sanitizeText(v: string): string {
    return v.replace(/<[^>]*>/g, '').trim();
  }
}
