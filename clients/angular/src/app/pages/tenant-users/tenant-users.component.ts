import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { AdminService, StaffUser } from '../../core/services/admin.service';
import { MembersService, Member } from '../../core/services/members.service';
import { TenantService } from '../../core/services/tenant.service';
import { DataTableComponent, TableAction } from '../../shared/components/data-table/data-table.component';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { IconComponent } from '../../shared/components/icon/icon.component';

type ActiveTab = 'staff' | 'members';

const PAGE_SIZE = 20;

@Component({
  selector: 'app-tenant-users',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, StatCardComponent, IconComponent],
  templateUrl: './tenant-users.component.html',
  styleUrl: './tenant-users.component.scss',
})
export class TenantUsersComponent implements OnInit, OnDestroy {
  activeTab: ActiveTab = 'staff';

  // ── Stat totals ───────────────────────────────────────────────────────────
  staffTotal = 0;
  staffActive = 0;
  membersTotal = 0;
  membersActive = 0;

  // ── Staff ─────────────────────────────────────────────────────────────────
  staff: StaffUser[] = [];
  staffLoading = false;
  staffQuery = '';
  staffCursors: string[] = [];   // cursor stack for prev navigation
  staffNextCursor: string | null = null;
  get staffHasPrev(): boolean { return this.staffCursors.length > 0; }
  get staffHasNext(): boolean { return !!this.staffNextCursor; }

  private staffSearch$ = new Subject<string>();

  staffColumns = [
    { key: 'firstName',  label: 'First Name' },
    { key: 'lastName',   label: 'Last Name' },
    { key: 'email',      label: 'Email' },
    { key: 'jobTitle',   label: 'Job Title' },
    { key: 'department', label: 'Department' },
    { key: 'realmRole',  label: 'Role',    type: 'label' },
    { key: 'status',     label: 'Status',  type: 'status' },
    { key: 'createdAt',  label: 'Invited', type: 'date' },
  ];

  staffActions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      visible: () => true,
      handler: (u: StaffUser) => this.openEditStaffModal(u),
    },
    {
      label: 'Suspend',
      icon: 'pause-circle',
      color: 'warning',
      visible: (u: StaffUser) => u.status === 'active',
      handler: (u: StaffUser) => this.suspendStaff(u),
    },
    {
      label: 'Activate',
      icon: 'play-circle',
      color: 'success',
      visible: (u: StaffUser) => u.status === 'suspended',
      handler: (u: StaffUser) => this.activateStaff(u),
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
    { value: 'tenant_admin',          label: 'Tenant Admin' },
    { value: 'claims_clerk',          label: 'Claims Clerk' },
    { value: 'claims_assessor',       label: 'Claims Assessor' },
    { value: 'finance_officer',       label: 'Finance Officer' },
    { value: 'contributions_officer', label: 'Contributions Officer' },
    { value: 'operations_staff',      label: 'Operations Staff' },
  ];

  showAddStaffModal = false;
  addStaffForm = { firstName: '', lastName: '', email: '', jobTitle: '', department: '', realmRole: '' };
  addStaffErrors: Record<string, string> = {};
  addStaffSubmitting = false;

  showEditStaffModal = false;
  editingStaff: StaffUser | null = null;
  editStaffForm = { firstName: '', lastName: '', email: '', phone: '', jobTitle: '', department: '', realmRole: '' };
  editStaffErrors: Record<string, string> = {};
  editStaffSubmitting = false;
  editStaffRoleOptions: { value: string; label: string }[] = [];

  // ── Members ───────────────────────────────────────────────────────────────
  members: Member[] = [];
  membersLoading = false;
  memberQuery = '';
  memberStatus = '';
  memberCursors: string[] = [];
  memberNextCursor: string | null = null;
  get memberHasPrev(): boolean { return this.memberCursors.length > 0; }
  get memberHasNext(): boolean { return !!this.memberNextCursor; }

  private memberSearch$ = new Subject<string>();

  memberStatusOptions = [
    { value: '',           label: 'All statuses' },
    { value: 'active',     label: 'Active' },
    { value: 'enrolled',   label: 'Enrolled' },
    { value: 'suspended',  label: 'Suspended' },
    { value: 'terminated', label: 'Terminated' },
  ];

  memberColumns = [
    { key: 'memberNumber',   label: 'Member #' },
    { key: 'firstName',      label: 'First Name' },
    { key: 'lastName',       label: 'Last Name' },
    { key: 'email',          label: 'Email' },
    { key: 'phone',          label: 'Phone' },
    { key: 'status',         label: 'Status',   type: 'status' },
    { key: 'enrollmentDate', label: 'Enrolled', type: 'date' },
  ];

  memberActions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      visible: () => true,
      handler: (m: Member) => this.openEditMemberModal(m),
    },
    {
      label: 'Activate',
      icon: 'play-circle',
      color: 'success',
      visible: (m: Member) => m.status === 'enrolled' || m.status === 'suspended',
      handler: (m: Member) => this.activateMember(m),
    },
    {
      label: 'Suspend',
      icon: 'pause-circle',
      color: 'warning',
      visible: (m: Member) => m.status === 'active',
      handler: (m: Member) => this.suspendMember(m),
    },
    {
      label: 'Terminate',
      icon: 'x-circle',
      color: 'danger',
      visible: (m: Member) => m.status !== 'terminated',
      handler: (m: Member) => this.terminateMember(m),
    },
  ];

  showEnrollModal = false;
  enrollFromStaffName = '';
  enrollForm = {
    firstName: '', lastName: '', dateOfBirth: '', gender: '',
    nationalId: '', email: '', phone: '', address: '', groupId: '', schemeId: '',
  };
  enrollErrors: Record<string, string> = {};
  enrollSubmitting = false;

  showEditMemberModal = false;
  editingMember: Member | null = null;
  editMemberForm = {
    firstName: '', lastName: '', dateOfBirth: '', gender: '',
    nationalId: '', email: '', phone: '', address: '', groupId: '', schemeId: '',
  };
  editMemberErrors: Record<string, string> = {};
  editMemberSubmitting = false;

  private destroy$ = new Subject<void>();

  constructor(
    private adminService: AdminService,
    private membersService: MembersService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.staffSearch$.pipe(debounceTime(400), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => { this.staffCursors = []; this.loadStaff(); });

    this.memberSearch$.pipe(debounceTime(400), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => { this.memberCursors = []; this.loadMembers(); });

    // React to tenant context — fires synchronously if already set, async if bootstrapping.
    // distinctUntilChanged prevents double-load on branding-only updates.
    this.tenantService.tenant$.pipe(
      distinctUntilChanged((a, b) => a?.id === b?.id),
      takeUntil(this.destroy$),
    ).subscribe(tenant => {
      if (tenant?.id) {
        this.staffCursors  = [];
        this.memberCursors = [];
        this.loadStats();
        this.loadStaff();
        this.loadMembers();
      } else {
        this.staff          = [];
        this.members        = [];
        this.staffLoading   = false;
        this.membersLoading = false;
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  selectTab(tab: ActiveTab): void { this.activeTab = tab; }

  // ── Staff ─────────────────────────────────────────────────────────────────

  private loadStats(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.adminService.getTenantStats(tenantId).subscribe({
      next: s => {
        this.staffTotal   = s.totalStaff;
        this.staffActive  = s.activeStaff;
        this.membersTotal  = s.totalMembers;
        this.membersActive = s.activeMembers;
      },
      error: () => {},
    });
  }

  loadStaff(cursor?: string): void {
    this.staffLoading = true;
    const tenantId = this.tenantService.getTenantId() || undefined;
    this.adminService.getStaffPage({ q: this.staffQuery || undefined, cursor, limit: PAGE_SIZE }, tenantId).subscribe({
      next: (raw: any) => {
        const content: StaffUser[] = Array.isArray(raw) ? raw : (raw?.content ?? []);
        this.staff           = content;
        this.staffNextCursor = Array.isArray(raw) ? null : (raw?.nextCursor ?? null);
        this.staffLoading    = false;
      },
      error: () => { this.staffLoading = false; },
    });
  }

  onStaffQueryChange(): void { this.staffSearch$.next(this.staffQuery); }

  staffNextPage(): void {
    if (!this.staffNextCursor) return;
    this.staffCursors.push(this.staffNextCursor);
    this.loadStaff(this.staffNextCursor);
  }

  staffPrevPage(): void {
    this.staffCursors.pop();
    const cursor = this.staffCursors.at(-1);
    this.loadStaff(cursor);
  }

  openAddStaffModal(): void {
    this.addStaffForm   = { firstName: '', lastName: '', email: '', jobTitle: '', department: '', realmRole: '' };
    this.addStaffErrors = {};
    this.showAddStaffModal = true;
  }

  submitAddStaff(): void {
    this.addStaffErrors = {};
    if (!this.addStaffForm.firstName.trim()) this.addStaffErrors['firstName'] = 'Required';
    if (!this.addStaffForm.lastName.trim())  this.addStaffErrors['lastName']  = 'Required';
    if (!this.addStaffForm.email.trim())     this.addStaffErrors['email']     = 'Required';
    if (!this.addStaffForm.realmRole)        this.addStaffErrors['realmRole'] = 'Required';
    if (Object.keys(this.addStaffErrors).length) return;

    this.addStaffSubmitting = true;
    this.adminService.createStaffUser(this.addStaffForm).subscribe({
      next: () => {
        this.showAddStaffModal  = false;
        this.addStaffSubmitting = false;
        this.staffCursors = [];
        this.loadStaff();
      },
      error: () => { this.addStaffSubmitting = false; },
    });
  }

  openEditStaffModal(u: StaffUser): void {
    this.editingStaff = u;
    const matchedRole = this.availableRoles.find(
      r => r.value === u.realmRole || r.value === u.realmRole?.toLowerCase()
    )?.value ?? u.realmRole ?? '';
    const inList = this.availableRoles.some(r => r.value === matchedRole);
    this.editStaffRoleOptions = inList
      ? this.availableRoles
      : [{ value: u.realmRole, label: this.formatRoleLabel(u.realmRole) }, ...this.availableRoles];
    this.editStaffForm = {
      firstName: u.firstName, lastName: u.lastName, email: u.email,
      phone: u.phone ?? '', jobTitle: u.jobTitle ?? '',
      department: u.department ?? '', realmRole: matchedRole,
    };
    this.editStaffErrors = {};
    this.showEditStaffModal = true;
  }

  submitEditStaff(): void {
    if (!this.editingStaff) return;
    this.editStaffErrors = {};
    if (!this.editStaffForm.firstName.trim()) this.editStaffErrors['firstName'] = 'Required';
    if (!this.editStaffForm.lastName.trim())  this.editStaffErrors['lastName']  = 'Required';
    if (!this.editStaffForm.email.trim())     this.editStaffErrors['email']     = 'Required';
    if (!this.editStaffForm.realmRole)        this.editStaffErrors['realmRole'] = 'Required';
    if (Object.keys(this.editStaffErrors).length) return;

    this.editStaffSubmitting = true;
    this.adminService.updateStaffUser(this.editingStaff.id, this.editStaffForm).subscribe({
      next: () => {
        this.showEditStaffModal  = false;
        this.editStaffSubmitting = false;
        this.loadStaff(this.staffCursors.at(-1));
      },
      error: () => { this.editStaffSubmitting = false; },
    });
  }

  openEnrollFromStaff(u: StaffUser): void {
    this.showEditStaffModal  = false;
    this.enrollFromStaffName = `${u.firstName} ${u.lastName}`;
    this.enrollForm = {
      firstName: u.firstName, lastName: u.lastName, dateOfBirth: '',
      gender: '', nationalId: '', email: u.email,
      phone: u.phone ?? '', address: '', groupId: '', schemeId: '',
    };
    this.enrollErrors = {};
    this.showEnrollModal = true;
  }

  suspendStaff(u: StaffUser): void {
    this.adminService.suspendStaffUser(u.id).subscribe({ next: () => { this.loadStats(); this.loadStaff(this.staffCursors.at(-1)); } });
  }

  activateStaff(u: StaffUser): void {
    this.adminService.activateStaffUser(u.id).subscribe({ next: () => { this.loadStats(); this.loadStaff(this.staffCursors.at(-1)); } });
  }

  resendInvite(u: StaffUser): void {
    this.adminService.resendStaffInvite(u.id).subscribe();
  }

  private formatRoleLabel(role: string): string {
    return role.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
  }

  // ── Members ───────────────────────────────────────────────────────────────

  loadMembers(cursor?: string): void {
    this.membersLoading = true;
    this.membersService.getPage({
      q: this.memberQuery || undefined,
      status: this.memberStatus || undefined,
      cursor,
      limit: PAGE_SIZE,
    }).subscribe({
      next: (raw: any) => {
        const content: Member[] = Array.isArray(raw) ? raw : (raw?.content ?? []);
        this.members          = content;
        this.memberNextCursor = Array.isArray(raw) ? null : (raw?.nextCursor ?? null);
        this.membersLoading   = false;
      },
      error: () => { this.membersLoading = false; },
    });
  }

  onMemberQueryChange(): void { this.memberSearch$.next(this.memberQuery); }

  onMemberStatusChange(): void {
    this.memberCursors = [];
    this.loadMembers();
  }

  memberNextPage(): void {
    if (!this.memberNextCursor) return;
    this.memberCursors.push(this.memberNextCursor);
    this.loadMembers(this.memberNextCursor);
  }

  memberPrevPage(): void {
    this.memberCursors.pop();
    const cursor = this.memberCursors.at(-1);
    this.loadMembers(cursor);
  }

  openEnrollModal(): void {
    this.enrollFromStaffName = '';
    this.enrollForm = {
      firstName: '', lastName: '', dateOfBirth: '', gender: '',
      nationalId: '', email: '', phone: '', address: '', groupId: '', schemeId: '',
    };
    this.enrollErrors = {};
    this.showEnrollModal = true;
  }

  submitEnroll(): void {
    this.enrollErrors = {};
    if (!this.enrollForm.firstName.trim())  this.enrollErrors['firstName']   = 'Required';
    if (!this.enrollForm.lastName.trim())   this.enrollErrors['lastName']    = 'Required';
    if (!this.enrollForm.dateOfBirth)       this.enrollErrors['dateOfBirth'] = 'Required';
    if (!this.enrollForm.gender)            this.enrollErrors['gender']      = 'Required';
    if (Object.keys(this.enrollErrors).length) return;

    this.enrollSubmitting = true;
    this.membersService.enroll(this.enrollForm).subscribe({
      next: () => {
        this.showEnrollModal  = false;
        this.enrollSubmitting = false;
        this.memberCursors = [];
        this.loadMembers();
      },
      error: () => { this.enrollSubmitting = false; },
    });
  }

  openEditMemberModal(m: Member): void {
    this.editingMember = m;
    const raw = m as any;
    const genderRaw = (raw.gender ?? '').toLowerCase();
    const gender = ['male', 'female', 'other'].includes(genderRaw) ? genderRaw : '';
    this.editMemberForm = {
      firstName: m.firstName, lastName: m.lastName,
      dateOfBirth: m.dateOfBirth ?? '', gender,
      nationalId: raw.nationalId ?? '', email: m.email ?? '',
      phone: m.phone ?? '', address: raw.address ?? '',
      groupId: m.groupId ?? '', schemeId: m.schemeId ?? '',
    };
    this.editMemberErrors = {};
    this.showEditMemberModal = true;
  }

  submitEditMember(): void {
    if (!this.editingMember) return;
    this.editMemberErrors = {};
    if (!this.editMemberForm.firstName.trim())  this.editMemberErrors['firstName']   = 'Required';
    if (!this.editMemberForm.lastName.trim())   this.editMemberErrors['lastName']    = 'Required';
    if (!this.editMemberForm.dateOfBirth)       this.editMemberErrors['dateOfBirth'] = 'Required';
    if (!this.editMemberForm.gender)            this.editMemberErrors['gender']      = 'Required';
    if (Object.keys(this.editMemberErrors).length) return;

    this.editMemberSubmitting = true;
    this.membersService.update(this.editingMember.id, this.editMemberForm).subscribe({
      next: () => {
        this.showEditMemberModal  = false;
        this.editMemberSubmitting = false;
        this.loadMembers(this.memberCursors.at(-1));
      },
      error: () => { this.editMemberSubmitting = false; },
    });
  }

  activateMember(m: Member): void {
    this.membersService.activate(m.id).subscribe({ next: () => { this.loadStats(); this.loadMembers(this.memberCursors.at(-1)); } });
  }

  suspendMember(m: Member): void {
    this.membersService.suspend(m.id).subscribe({ next: () => { this.loadStats(); this.loadMembers(this.memberCursors.at(-1)); } });
  }

  terminateMember(m: Member): void {
    if (!confirm(`Terminate membership for ${m.firstName} ${m.lastName}? This cannot be undone.`)) return;
    this.membersService.terminate(m.id).subscribe({ next: () => { this.loadStats(); this.loadMembers(this.memberCursors.at(-1)); } });
  }
}
