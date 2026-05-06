import { Component, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { StatCardComponent } from '../../../shared/components/stat-card/stat-card.component';
import { DataTableComponent, TableAction } from '../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { AdminService, StaffUser, Tenant, TenantMember } from '../../../core/services/admin.service';

type Tab = 'staff' | 'members' | 'invitations';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [CommonModule, FormsModule, StatCardComponent, DataTableComponent, IconComponent],
  templateUrl: './users.component.html',
  styleUrl: './users.component.scss',
  // None encapsulation so the modal overlay can escape Angular's style scoping
  // and render correctly over the full viewport via position:fixed.
  encapsulation: ViewEncapsulation.None,
})
export class UsersComponent implements OnInit, OnDestroy {
  activeTab: Tab = 'staff';

  // Platform staff
  staffUsers: StaffUser[] = [];
  staffLoading = false;
  staffSearch = '';

  // Edit modal
  showEditModal = false;
  editingUser?: StaffUser;
  edit         = { firstName: '', lastName: '', email: '', jobTitle: '', department: '', realmRole: '' };
  editOriginal = { ...this.edit };
  editSubmitting = false;
  editServerError = '';

  get editHasChanges(): boolean {
    return (Object.keys(this.edit) as Array<keyof typeof this.edit>)
      .some(k => this.edit[k] !== this.editOriginal[k]);
  }

  // Invite modal
  showInviteModal = false;
  inviteSubmitting = false;
  inviteSuccess = false;
  inviteServerError = '';
  invite = { firstName: '', lastName: '', email: '', jobTitle: '', department: '', realmRole: '', tenantId: '' };
  inviteErrors: Record<string, string> = {};
  inviteTouched: Record<string, boolean> = {};

  /** True when the selected role requires a tenant link (anything except super_admin). */
  get inviteNeedsTenant(): boolean {
    return !!this.invite.realmRole && this.invite.realmRole !== 'super_admin';
  }

  /** When role changes, clear the tenantId so a stale selection is not carried over. */
  onInviteRoleChange(): void {
    if (!this.inviteNeedsTenant) this.invite.tenantId = '';
    if (this.inviteTouched['tenantId']) this.validateInviteField('tenantId');
  }

  // Tenant members
  members: TenantMember[] = [];
  membersLoading = false;
  memberSearch = '';
  selectedTenantId = '';
  tenants: Tenant[] = [];

  // Invitations (status='invited')
  invitations: StaffUser[] = [];
  invitationsLoading = false;

  staffColumns = [
    { key: 'firstName',  label: 'First Name' },
    { key: 'lastName',   label: 'Last Name' },
    { key: 'email',      label: 'Email' },
    { key: 'jobTitle',   label: 'Job Title' },
    { key: 'department', label: 'Department' },
    { key: 'realmRole',  label: 'Role', type: 'label' },
    { key: 'status',     label: 'Status', type: 'status' },
    { key: 'createdAt',  label: 'Created', type: 'date' },
  ];

  memberColumns = [
    { key: 'memberNumber',   label: 'Member #' },
    { key: 'firstName',      label: 'First Name' },
    { key: 'lastName',       label: 'Last Name' },
    { key: 'email',          label: 'Email' },
    { key: 'phone',          label: 'Phone' },
    { key: 'status',         label: 'Status', type: 'status' },
    { key: 'enrollmentDate', label: 'Enrolled', type: 'date' },
  ];

  invitationColumns = [
    { key: 'firstName',       label: 'First Name' },
    { key: 'lastName',        label: 'Last Name' },
    { key: 'email',           label: 'Email' },
    { key: 'realmRole',       label: 'Role', type: 'label' },
    { key: 'invitedAt',       label: 'Invited', type: 'date' },
    { key: 'inviteExpiresAt', label: 'Expires', type: 'date' },
  ];

  invitationActions: TableAction[] = [
    {
      label: 'Resend',
      icon: 'bell',
      color: 'default',
      handler: (row: StaffUser) => this.resendInvite(row),
    },
    {
      label: 'Cancel',
      icon: 'x-circle',
      color: 'danger',
      handler: (row: StaffUser) => this.cancelInvitation(row),
    },
  ];

  staffActions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'settings',
      color: 'default',
      handler: (row: StaffUser) => this.openEditModal(row),
    },
    {
      label: 'Resend Invite',
      icon: 'bell',
      color: 'default',
      visible: (row: StaffUser) => !row.inviteAccepted && row.status?.toLowerCase() === 'invited',
      handler: (row: StaffUser) => this.resendInvite(row),
    },
    {
      label: 'Suspend',
      icon: 'pause-circle',
      color: 'warning',
      visible: (row: StaffUser) => row.status?.toLowerCase() === 'active',
      handler: (row: StaffUser) => this.suspendStaff(row),
    },
    {
      label: 'Activate',
      icon: 'play-circle',
      color: 'success',
      visible: (row: StaffUser) => row.status?.toLowerCase() !== 'active',
      handler: (row: StaffUser) => this.activateStaff(row),
    },
  ];

  private staffSearch$ = new Subject<string>();
  private memberSearch$ = new Subject<string>();
  private destroy$ = new Subject<void>();

  constructor(private adminService: AdminService) {}

  ngOnInit(): void {
    this.loadTenants();
    this.loadStaffUsers();
    this.loadInvitations();

    this.staffSearch$.pipe(
      debounceTime(400), distinctUntilChanged(), takeUntil(this.destroy$),
    ).subscribe(() => this.loadStaffUsers());

    this.memberSearch$.pipe(
      debounceTime(400), distinctUntilChanged(), takeUntil(this.destroy$),
    ).subscribe(() => this.loadMembers());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get activeStaff(): number {
    return this.staffUsers.filter(u => u.status?.toLowerCase() === 'active').length;
  }

  get activeMembers(): number {
    return this.members.filter(m => m.status?.toLowerCase() === 'active').length;
  }

  setTab(tab: Tab): void {
    this.activeTab = tab;
  }

  onStaffSearchChange(q: string): void {
    this.staffSearch = q;
    this.staffSearch$.next(q);
  }

  onTenantChange(): void {
    this.members = [];
    if (this.selectedTenantId) this.loadMembers();
  }

  onMemberSearchChange(q: string): void {
    this.memberSearch = q;
    this.memberSearch$.next(q);
  }

  loadTenants(): void {
    this.adminService.getTenants({ size: 100 }).subscribe({
      next: p => (this.tenants = p.content),
      error: () => {},
    });
  }

  loadStaffUsers(): void {
    this.staffLoading = true;
    const params: Record<string, string> = {};
    if (this.staffSearch.trim()) params['q'] = this.staffSearch.trim();
    this.adminService.getStaffUsers(params).subscribe({
      next: (raw: any) => {
        // Backend now returns CursorPage<StaffUser>; handle both shapes defensively
        this.staffUsers = Array.isArray(raw) ? raw : (raw?.content ?? []);
        this.staffLoading = false;
      },
      error: () => { this.staffLoading = false; },
    });
  }

  loadMembers(): void {
    if (!this.selectedTenantId) return;
    this.membersLoading = true;
    this.adminService.getTenantMembers(this.selectedTenantId, this.memberSearch.trim() || undefined).subscribe({
      next: m => { this.members = m ?? []; this.membersLoading = false; },
      error: () => { this.membersLoading = false; },
    });
  }

  suspendStaff(user: StaffUser): void {
    this.adminService.suspendStaffUser(user.id).subscribe({
      next: () => this.loadStaffUsers(),
      error: () => {},
    });
  }

  activateStaff(user: StaffUser): void {
    this.adminService.activateStaffUser(user.id).subscribe({
      next: () => this.loadStaffUsers(),
      error: () => {},
    });
  }

  // ── Edit modal ────────────────────────────────────────────────────────────

  openEditModal(user: StaffUser): void {
    this.editingUser = user;
    this.edit = {
      firstName:  user.firstName,
      lastName:   user.lastName,
      email:      user.email,
      jobTitle:   user.jobTitle  ?? '',
      department: user.department ?? '',
      realmRole:  user.realmRole,
    };
    this.editOriginal = { ...this.edit };
    this.editServerError = '';
    this.editSubmitting = false;
    this.showEditModal = true;
  }

  saveEdit(): void {
    if (!this.editingUser) return;
    this.editSubmitting = true;
    this.editServerError = '';
    this.adminService.updateStaffUser(this.editingUser.id, this.edit).subscribe({
      next: () => {
        this.editSubmitting = false;
        this.showEditModal = false;
        this.loadStaffUsers();
      },
      error: (err) => {
        this.editSubmitting = false;
        this.editServerError = err?.error?.detail ?? err?.error?.message ?? 'Failed to update user.';
      },
    });
  }

  resendInvite(user: StaffUser): void {
    this.adminService.resendStaffInvite(user.id).subscribe({
      next: () => this.loadInvitations(),
      error: () => {},
    });
  }

  loadInvitations(): void {
    this.invitationsLoading = true;
    this.adminService.getInvitations().subscribe({
      next: (rows) => { this.invitations = rows ?? []; this.invitationsLoading = false; },
      error: () => { this.invitationsLoading = false; },
    });
  }

  cancelInvitation(user: StaffUser): void {
    if (!confirm(`Cancel the invitation for ${user.email}? The user will be removed.`)) return;
    this.adminService.deleteStaffUser(user.id).subscribe({
      next: () => { this.loadInvitations(); this.loadStaffUsers(); },
      error: () => {},
    });
  }

  // ── Invite modal ──────────────────────────────────────────────────────────

  openInviteModal(): void {
    this.invite = { firstName: '', lastName: '', email: '', jobTitle: '', department: '', realmRole: '', tenantId: '' };
    this.inviteErrors = {};
    this.inviteTouched = {};
    this.inviteServerError = '';
    this.inviteSuccess = false;
    this.inviteSubmitting = false;
    this.showInviteModal = true;
  }

  touchInvite(field: string): void {
    this.inviteTouched[field] = true;
    this.validateInviteField(field);
  }

  inviteFieldError(field: string): string {
    return this.inviteTouched[field] ? (this.inviteErrors[field] ?? '') : '';
  }

  private validateInviteField(field: string): void {
    const v = this.invite as any;
    switch (field) {
      case 'firstName':  this.inviteErrors[field] = v.firstName.trim()  ? '' : 'First name is required.'; break;
      case 'lastName':   this.inviteErrors[field] = v.lastName.trim()   ? '' : 'Last name is required.';  break;
      case 'email':
        if (!v.email.trim()) this.inviteErrors[field] = 'Email is required.';
        else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email)) this.inviteErrors[field] = 'Enter a valid email.';
        else this.inviteErrors[field] = '';
        break;
      case 'realmRole':  this.inviteErrors[field] = v.realmRole ? '' : 'Please select a role.'; break;
      case 'tenantId':
        this.inviteErrors[field] = (this.inviteNeedsTenant && !v.tenantId) ? 'Please select a tenant.' : '';
        break;
    }
  }

  private validateAllInvite(): boolean {
    const fields = ['firstName', 'lastName', 'email', 'realmRole'];
    if (this.inviteNeedsTenant) fields.push('tenantId');
    fields.forEach(f => {
      this.inviteTouched[f] = true;
      this.validateInviteField(f);
    });
    return Object.values(this.inviteErrors).every(e => !e);
  }

  sendInvite(): void {
    if (!this.validateAllInvite()) return;
    this.inviteSubmitting = true;
    this.inviteServerError = '';
    this.inviteSuccess = false;
    const payload = {
      ...this.invite,
      tenantId: this.inviteNeedsTenant ? (this.invite.tenantId || null) : null,
    };
    this.adminService.createStaffUser(payload).subscribe({
      next: () => {
        this.inviteSubmitting = false;
        this.inviteSuccess = true;
        this.loadStaffUsers();
      },
      error: (err) => {
        this.inviteSubmitting = false;
        const body = err?.error;
        if (body?.detail) this.inviteServerError = body.detail;
        else if (err?.status === 409) this.inviteServerError = 'A user with this email already exists.';
        else this.inviteServerError = 'Failed to send invitation. Please try again.';
      },
    });
  }
}
