import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { AdminService, Role, StaffUser } from '../../core/services/admin.service';
import { MembersService, Member } from '../../core/services/members.service';
import { GroupsService, Group } from '../../core/services/groups.service';
import { ContributionsService, Scheme } from '../../core/services/contributions.service';
import { TenantService } from '../../core/services/tenant.service';
import { DataTableComponent, TableAction } from '../../shared/components/data-table/data-table.component';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../shared/components/select/select.component';

type ActiveTab = 'staff' | 'members';

const PAGE_SIZE = 20;

@Component({
  selector: 'app-tenant-users',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, StatCardComponent, IconComponent, SelectComponent],
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

  /**
   * Tenant-defined DB roles loaded at component init via {@code GET /api/v1/roles}.
   * Mapped onto the {value,label} shape the existing template expects so the
   * dropdown can swap drop-in. The {@code value} is the role's UUID — submit
   * sends it as both {@code roleIds: [id]} (writes user_roles) and as
   * {@code realmRole: <name>} (kept for legacy Keycloak gating until Phase 5).
   */
  availableRoles: { value: string; label: string }[] = [];
  /** Full Role objects keyed by id — used to derive the legacy realmRole string on submit. */
  private rolesById: Record<string, Role> = {};

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

  // The empty-value entry ("All statuses") was dropped because the filter now
  // uses the shared toolbar-cell pattern from /tenant/finance/runs, where the
  // "All" state is expressed via the placeholder rather than a synthetic
  // option. memberStatus === '' still means "no filter".
  memberStatusOptions = [
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

  // Members are read-only on this admin page. All mutations (edit,
  // activate/suspend/terminate, swap, record death) live on the operational
  // member detail page at /tenant/members/{id}; this tab is discovery + a
  // read-only card via the View action.
  memberActions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (m: Member) => this.openViewMemberModal(m),
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

  // View-only member detail modal. Opens on the View row action; carries no
  // form state because nothing is editable here.
  showViewMemberModal = false;
  viewingMember: Member | null = null;

  // Lookup maps for the view modal — resolves member.groupId / member.schemeId
  // to friendly names so the modal never surfaces a raw UUID.
  private groupNameById  = new Map<string, string>();
  private schemeNameById = new Map<string, string>();

  // ── SelectComponent options ─────────────────────────────────────────────
  /** Member-status filter — already shaped as {value,label}, just retype it. */
  get memberStatusSelectOptions(): SelectOption[] {
    return this.memberStatusOptions.map(o => ({ value: o.value, label: o.label }));
  }
  /** Tenant-defined role catalogue lifted into SelectOption[]. Add-staff modal. */
  get availableRoleOptions(): SelectOption[] {
    return this.availableRoles.map(r => ({ value: r.value, label: r.label }));
  }
  /** Same roles, plus an optional legacy entry — Edit-staff modal. */
  get editStaffRoleSelectOptions(): SelectOption[] {
    return this.editStaffRoleOptions.map(r => ({ value: r.value, label: r.label }));
  }
  readonly genderOptions: SelectOption[] = [
    { value: 'male',   label: 'Male' },
    { value: 'female', label: 'Female' },
    { value: 'other',  label: 'Other' },
  ];

  private destroy$ = new Subject<void>();

  constructor(
    private adminService: AdminService,
    private membersService: MembersService,
    private groupsService: GroupsService,
    private contributionsService: ContributionsService,
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
        this.loadRoles();
        this.loadHolderLookups();
      } else {
        this.staff          = [];
        this.members        = [];
        this.availableRoles = [];
        this.rolesById      = {};
        this.groupNameById.clear();
        this.schemeNameById.clear();
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

  /**
   * Pull tenant-defined DB roles from the backend so the invite/edit dropdown
   * shows whatever the IT admin has configured under
   * Settings → Roles & Permissions. Falls back to an empty list on error;
   * the validation message tells the admin to create a role first.
   */
  private loadRoles(): void {
    this.adminService.getRoles().subscribe({
      next: roles => {
        this.rolesById      = Object.fromEntries(roles.map(r => [r.id, r]));
        this.availableRoles = roles.map(r => ({ value: r.id, label: r.displayName }));
      },
      error: () => { this.availableRoles = []; this.rolesById = {}; },
    });
  }

  submitAddStaff(): void {
    this.addStaffErrors = {};
    if (!this.addStaffForm.firstName.trim()) this.addStaffErrors['firstName'] = 'Required';
    if (!this.addStaffForm.lastName.trim())  this.addStaffErrors['lastName']  = 'Required';
    if (!this.addStaffForm.email.trim())     this.addStaffErrors['email']     = 'Required';
    if (!this.addStaffForm.realmRole)        this.addStaffErrors['realmRole'] = 'Required';
    if (Object.keys(this.addStaffErrors).length) return;

    this.addStaffSubmitting = true;
    // The dropdown holds role UUIDs; submit sends them as roleIds (writes
    // user_roles in the tenant schema) AND derives the legacy realmRole
    // string from the picked role's name to satisfy the @NotBlank Keycloak
    // gate until Phase 5 retires the column.
    const selectedRole = this.rolesById[this.addStaffForm.realmRole];
    const realmRoleName = selectedRole?.name ?? this.addStaffForm.realmRole;
    const roleIds = selectedRole ? [selectedRole.id] : undefined;

    this.adminService.createStaffUser({
      ...this.addStaffForm,
      realmRole: realmRoleName,
      roleIds,
    }).subscribe({
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
    // Dropdown values are role UUIDs. Match by role.name = realmRole (the
    // legacy field still set on staff_users) so the user's current role is
    // pre-selected when its corresponding tenant role exists. If the
    // realmRole doesn't map to any tenant role, leave the dropdown empty
    // and surface the legacy name as a synthetic option.
    const match = Object.values(this.rolesById).find(r => r.name === u.realmRole);
    this.editStaffRoleOptions = match
      ? this.availableRoles
      : (u.realmRole
          ? [{ value: '__legacy__', label: this.formatRoleLabel(u.realmRole) + ' (legacy)' }, ...this.availableRoles]
          : this.availableRoles);
    this.editStaffForm = {
      firstName: u.firstName, lastName: u.lastName, email: u.email,
      phone: u.phone ?? '', jobTitle: u.jobTitle ?? '',
      department: u.department ?? '', realmRole: match?.id ?? (u.realmRole ? '__legacy__' : ''),
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
    // Same pattern as create: derive realmRole from the picked role's name
    // and send roleIds so user_roles is replaced atomically.
    const selected = this.rolesById[this.editStaffForm.realmRole];
    const payload = {
      ...this.editStaffForm,
      realmRole: selected?.name ?? this.editStaffForm.realmRole,
      roleIds: selected ? [selected.id] : undefined,
    };
    this.adminService.updateStaffUser(this.editingStaff.id, payload).subscribe({
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

  /**
   * Search handler for the data-table's built-in toolbar search on the members
   * tab. The term arrives already debounced (400ms) inside the data-table.
   * Resets cursor state and re-fetches the first page.
   */
  onMemberSearchChange(term: string): void {
    this.memberQuery = term;
    this.memberCursors = [];
    this.loadMembers();
  }

  /** Same, but for the staff tab's built-in toolbar search. */
  onStaffSearchChange(term: string): void {
    this.staffQuery = term;
    this.staffCursors = [];
    this.loadStaff();
  }

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

  openViewMemberModal(m: Member): void {
    this.viewingMember = m;
    this.showViewMemberModal = true;
  }

  /** Group display name for the currently-viewed member, or "—" when unset / unknown. */
  get viewingGroupName(): string {
    const id = this.viewingMember?.groupId;
    if (!id) return '—';
    return this.groupNameById.get(id) ?? '—';
  }

  /** Scheme display name for the currently-viewed member, or "—" when unset / unknown. */
  get viewingSchemeName(): string {
    const id = this.viewingMember?.schemeId;
    if (!id) return '—';
    return this.schemeNameById.get(id) ?? '—';
  }

  // National ID and address exist on the wire payload but aren't declared on
  // the typed Member interface (the operational members module reads them via
  // `as any`). Mirror the same escape-hatch here so the view modal renders
  // them without widening the shared Member type just for this page.
  get viewingNationalId(): string { return (this.viewingMember as any)?.nationalId || '—'; }
  get viewingAddress(): string    { return (this.viewingMember as any)?.address    || '—'; }

  /**
   * Populate the group + scheme name lookups used by the read-only member
   * modal. Reload on tenant switch alongside the roles/stats fetch. Silent on
   * error: the modal falls back to "—" for any id it can't resolve.
   */
  private loadHolderLookups(): void {
    this.groupsService.list().subscribe({
      next: (groups: Group[]) => {
        this.groupNameById.clear();
        groups.forEach(g => this.groupNameById.set(g.id, g.name));
      },
      error: () => { this.groupNameById.clear(); },
    });
    this.contributionsService.getSchemes().subscribe({
      next: (schemes: Scheme[]) => {
        this.schemeNameById.clear();
        schemes.forEach(s => this.schemeNameById.set(s.id, s.name));
      },
      error: () => { this.schemeNameById.clear(); },
    });
  }
}
