import { Injectable } from '@angular/core';
import { Observable, of, shareReplay } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { ApiService } from './api.service';

export interface CursorPage<T> {
  content: T[];
  nextCursor: string | null;
  hasMore: boolean;
  limit: number;
}

export interface StaffUser {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  phone?: string;
  jobTitle?: string;
  department?: string;
  realmRole: string;
  tenantId?: string;
  status: string;
  /** ISO timestamp the most recent invite email was sent. */
  invitedAt?: string;
  /** ISO timestamp the invite link expires (invitedAt + 7d). */
  inviteExpiresAt?: string;
  /** True when the user has completed the password-set flow. */
  inviteAccepted?: boolean;
  createdAt: string;
}

export interface TenantMember {
  id: string;
  memberNumber: string;
  firstName: string;
  lastName: string;
  email?: string;
  phone?: string;
  groupId?: string;
  schemeId?: string;
  status: string;
  enrollmentDate: string;
}

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  domain?: string;
  status: string;
  contactEmail: string;
  countryCode: string;
  membershipModel: string;
  timezone?: string;
  /** Raw JSON string — e.g. {"insuranceLines":["HEALTH","LIFE"]} */
  settings?: string;
  /** Raw JSON string as returned by the tenancy service. */
  branding?: string;
  createdAt: string;
}

export interface TenantPage {
  content: Tenant[];
  totalCount: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface TenantSearchParams {
  q?: string;
  status?: string;
  membershipModel?: string;
  countryCode?: string;
  page?: number;
  size?: number;
}

export interface Role {
  id: string;
  name: string;
  displayName: string;
  description: string;
  isSystem: boolean;
  /** Stable Keycloak realm-role identifier (set on creation, immutable). */
  keycloakRoleName?: string;
}

export interface RoleWithPermissions extends Role {
  permissions: { id: string; permission: string; accessLevel: string }[];
}

export interface PermissionDescriptor {
  key: string;
  label: string;
  description: string;
}

export interface PermissionDomain {
  id: string;
  label: string;
  permissions: PermissionDescriptor[];
}

export interface PermissionCatalogue {
  domains: PermissionDomain[];
}

/**
 * Aggregate counts powering the operational dashboard. Each block can be
 * absent when the user lacks the corresponding view permission — frontend
 * code should default missing fields to zero rather than fail.
 */
export interface TenantStats {
  // Staff (admin scope — used by /tenant/admin/users)
  totalStaff: number; activeStaff: number; suspendedStaff: number; pendingStaff: number;
  // Members
  totalMembers: number; activeMembers: number; enrolledMembers: number;
  newMembersThisMonth: number; newGroupsThisMonth: number;
  // Claims — labels mirror the legacy Masca-Claims-Admin dashboard
  claimsNewTasks: number;            // pending / in_adjudication
  claimsThisMonth: number;           // every claim created this month
  claimsAcceptedThisMonth: number;
  claimsRejectedThisMonth: number;
  // Billing — currency totals (BigDecimal serialised as number).
  schemesActive: number;
  contributionsPending: number;
  contributionsAmountThisMonth: number;
  contributionsAmountThisYear: number;
  // Finance — payment counts + currency totals.
  paymentsPending: number;
  paymentsAmountThisMonth: number;
  paymentsAmountThisYear: number;
}

/**
 * 12-month trend points for the operational dashboard charts. Each series is
 * an array of {@code {name, value}} objects already shaped for ngx-charts —
 * the dashboard component wraps each in a single-series `[{ name: 'X', series: ... }]`
 * envelope before passing to {@code <app-area-chart>}.
 */
export interface TrendPoint { name: string; value: number; }
export interface TenantCharts {
  claimsByMonth:              TrendPoint[];
  contributionsAmountByMonth: TrendPoint[];
  paymentsAmountByMonth:      TrendPoint[];
}

export interface UserRoleAssignment {
  id: string;
  userId: string;
  roleId: string;
  assignedAt: string;
  assignedBy?: string;
}

export interface ScheduledJob {
  id: string;
  /** Owning tenant — null for platform-global jobs. */
  tenantId: string | null;
  jobType: string;
  name: string;
  cronExpression: string;
  isEnabled: boolean;
  settings: string;
  lastExecutedAt: string | null;
  nextExecutionAt: string | null;
}

export type ScheduledJobRunStatus = 'RUNNING' | 'SUCCESS' | 'FAILED';

export interface ScheduledJobRun {
  id: string;
  configId: string;
  /** Tenant the run belongs to — null for platform-global jobs. */
  tenantId: string | null;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  status: ScheduledJobRunStatus;
  triggerKind: 'schedule' | 'manual';
  errorMessage: string | null;
  triggeredBy: string | null;
  createdAt: string;
}

export interface AuditEvent {
  events: any[];
  total: number;
  page: number;
}

@Injectable({ providedIn: 'root' })
export class AdminService {
  // ── Audit cache ───────────────────────────────────────────────────────────
  private auditCache: { data: AuditEvent; ts: number } | null = null;
  private readonly AUDIT_CACHE_TTL = 5 * 60 * 1000; // 5 min

  // ── Entity name resolution cache ──────────────────────────────────────────
  // Two levels: resolved (permanent) and pending (in-flight dedup via shareReplay)
  private resolvedNames = new Map<string, string>();
  private pendingNameRequests = new Map<string, Observable<string>>();

  constructor(private api: ApiService) {}

  // Tenants (super admin)
  getTenants(params: TenantSearchParams = {}): Observable<TenantPage> {
    const p: Record<string, string> = {};
    if (params.q) p['q'] = params.q;
    if (params.status) p['status'] = params.status;
    if (params.membershipModel) p['membershipModel'] = params.membershipModel;
    if (params.countryCode) p['countryCode'] = params.countryCode;
    if (params.page) p['page'] = params.page.toString();
    if (params.size) p['size'] = params.size.toString();
    return this.api.get<TenantPage>('/tenants', Object.keys(p).length ? p : undefined);
  }

  getTenantById(id: string): Observable<Tenant> {
    return this.api.get<Tenant>(`/tenants/${id}`);
  }

  createTenant(data: any): Observable<Tenant> {
    return this.api.post<Tenant>('/tenants', data);
  }

  updateTenant(id: string, data: { name?: string; domain?: string; contactEmail?: string; timezone?: string; membershipModel?: string; settings?: string; branding?: string }): Observable<Tenant> {
    return this.api.put<Tenant>(`/tenants/${id}`, data);
  }

  suspendTenant(id: string): Observable<Tenant> {
    return this.api.post<Tenant>(`/tenants/${id}/suspend`, {});
  }

  activateTenant(id: string): Observable<Tenant> {
    return this.api.post<Tenant>(`/tenants/${id}/activate`, {});
  }

  // Users
  getStaffPage(opts: { q?: string; cursor?: string; limit?: number }, tenantId?: string): Observable<CursorPage<StaffUser>> {
    const params: Record<string, string> = {};
    if (opts.q)      params['q']      = opts.q;
    if (opts.cursor) params['cursor'] = opts.cursor;
    if (opts.limit)  params['limit']  = String(opts.limit);
    if (tenantId) {
      return this.api.getWithHeaders<CursorPage<StaffUser>>('/staff-users', { 'X-Tenant-ID': tenantId }, params);
    }
    return this.api.get<CursorPage<StaffUser>>('/staff-users', params);
  }

  /** @deprecated use getStaffPage */
  getStaffUsers(params?: Record<string, string>, tenantId?: string): Observable<StaffUser[]> {
    if (tenantId) {
      return this.api.getWithHeaders<StaffUser[]>('/staff-users', { 'X-Tenant-ID': tenantId }, params);
    }
    return this.api.get<StaffUser[]>('/staff-users', params);
  }

  /** @deprecated use getStaffUsers */
  getUsers(): Observable<any[]> {
    return this.api.get<any[]>('/staff-users');
  }

  createStaffUser(data: {
    firstName: string; lastName: string; email: string;
    jobTitle?: string; department?: string; realmRole: string;
    /** Tenant DB role IDs — populates user_roles in the tenant schema. */
    roleIds?: string[];
    tenantId?: string | null;
  }): Observable<StaffUser> {
    return this.api.post<StaffUser>('/staff-users', data);
  }

  updateStaffUser(id: string, data: {
    firstName?: string; lastName?: string; email?: string;
    phone?: string; jobTitle?: string; department?: string; realmRole?: string;
    /** When non-null, replace the user's tenant role assignments with this set. */
    roleIds?: string[];
  }): Observable<StaffUser> {
    return this.api.put<StaffUser>(`/staff-users/${id}`, data);
  }

  resendStaffInvite(id: string): Observable<StaffUser> {
    return this.api.post<StaffUser>(`/staff-users/${id}/resend-invite`, {});
  }

  /** All staff users with status='invited' (platform-scoped when no tenantId). */
  getInvitations(tenantId?: string): Observable<StaffUser[]> {
    const params: Record<string, string> = {};
    if (tenantId) params['tenantId'] = tenantId;
    return this.api.get<StaffUser[]>('/staff-users/invitations', params);
  }

  deleteStaffUser(id: string): Observable<void> {
    return this.api.delete<void>(`/staff-users/${id}`);
  }

  suspendStaffUser(id: string): Observable<StaffUser> {
    return this.api.post<StaffUser>(`/staff-users/${id}/suspend`, {});
  }

  activateStaffUser(id: string): Observable<StaffUser> {
    return this.api.post<StaffUser>(`/staff-users/${id}/activate`, {});
  }

  getTenantMembers(tenantId: string, q?: string): Observable<TenantMember[]> {
    if (q?.trim()) {
      return this.api.getWithHeaders<TenantMember[]>(
        '/members/search', { 'X-Tenant-ID': tenantId }, { q });
    }
    return this.api.getWithHeaders<TenantMember[]>('/members', { 'X-Tenant-ID': tenantId });
  }

  // Roles
  getRoles(): Observable<Role[]> {
    return this.api.get<Role[]>('/roles');
  }

  getRole(id: string): Observable<RoleWithPermissions> {
    return this.api.get<RoleWithPermissions>(`/roles/${id}`);
  }

  createRole(data: { name: string; displayName: string; description?: string;
                     permissions?: { permission: string; accessLevel?: string }[] }): Observable<Role> {
    return this.api.post<Role>('/roles', data);
  }

  updateRole(id: string, data: { displayName: string; description?: string }): Observable<Role> {
    return this.api.put<Role>(`/roles/${id}`, data);
  }

  deleteRole(id: string): Observable<void> {
    return this.api.delete<void>(`/roles/${id}`);
  }

  /** Atomically replace a role's permission set. Backend rejects unknown keys with 400. */
  replaceRolePermissions(id: string, permissions: string[]): Observable<RoleWithPermissions> {
    return this.api.put<RoleWithPermissions>(`/roles/${id}/permissions`, { permissions });
  }

  /** Canonical platform-wide permission catalogue, grouped by domain. */
  getPermissionCatalogue(): Observable<PermissionCatalogue> {
    return this.api.get<PermissionCatalogue>('/permissions/catalogue');
  }

  /** All user_roles rows holding the given role — drives the Members drawer. */
  getRoleMembers(roleId: string): Observable<UserRoleAssignment[]> {
    // The backend exposes user-by-role via /roles/user/{userId} only; for
    // listing members we filter on the client. Optimised endpoint can come
    // later if member counts grow large.
    return this.api.get<UserRoleAssignment[]>(`/roles/${roleId}/members`);
  }

  /** Assign a role to a user — also syncs the underlying Keycloak realm role. */
  assignRoleToUser(userId: string, roleId: string): Observable<UserRoleAssignment> {
    return this.api.post<UserRoleAssignment>('/roles/assign', { userId, roleId });
  }

  /** Revoke a role from a user. */
  revokeRoleFromUser(userId: string, roleId: string): Observable<void> {
    return this.api.delete<void>(`/roles/user/${userId}/role/${roleId}`);
  }

  // Scheduled Jobs
  /**
   * Lists scheduled job configs. Pass a tenantId to scope to that tenant
   * (the platform-admin tenant filter). Without it, returns every config
   * across every tenant — each row carries its tenantId.
   */
  getScheduledJobs(tenantId?: string | null): Observable<ScheduledJob[]> {
    return this.api.get<ScheduledJob[]>('/scheduled-jobs', tenantId ? { tenantId } : {});
  }

  updateJob(id: string, data: any): Observable<ScheduledJob> {
    return this.api.put<ScheduledJob>(`/scheduled-jobs/${id}`, data);
  }

  enableJob(id: string): Observable<ScheduledJob> {
    return this.api.post<ScheduledJob>(`/scheduled-jobs/${id}/enable`, {});
  }

  disableJob(id: string): Observable<ScheduledJob> {
    return this.api.post<ScheduledJob>(`/scheduled-jobs/${id}/disable`, {});
  }

  /**
   * Recent runs for a job — newest first. Used by the platform job monitor.
   * Pass tenantId to filter when "All tenants" is on (a single config might
   * have runs across multiple tenants if it's been duplicated per-tenant).
   */
  listJobRuns(id: string, limit = 50, tenantId?: string | null): Observable<ScheduledJobRun[]> {
    const params: Record<string, string> = { limit: String(limit) };
    if (tenantId) params['tenantId'] = tenantId;
    return this.api.get<ScheduledJobRun[]>(`/scheduled-jobs/${id}/runs`, params);
  }

  /** Manually trigger a job (records a run with trigger_kind='manual'). */
  runJobNow(id: string): Observable<ScheduledJobRun> {
    return this.api.post<ScheduledJobRun>(`/scheduled-jobs/${id}/run-now`, {});
  }

  // Audit
  /**
   * Fetches audit events.
   * When `tenantId` is supplied the request includes `X-Tenant-ID` so the
   * audit service returns only events for that tenant.
   * Platform-level callers (super admin) omit `tenantId` to see all events.
   */
  getAuditEvents(params?: Record<string, string>, tenantId?: string): Observable<AuditEvent> {
    const isDefault = !params || Object.keys(params).length === 0;
    const isTenantScoped = !!tenantId;

    // Only cache the unscoped, unparameterised platform-level request
    if (isDefault && !isTenantScoped && this.auditCache && Date.now() - this.auditCache.ts < this.AUDIT_CACHE_TTL) {
      return of(this.auditCache.data);
    }

    const request$ = tenantId
      ? this.api.getWithHeaders<AuditEvent>('/audit/events', { 'X-Tenant-ID': tenantId }, params)
      : this.api.get<AuditEvent>('/audit/events', params);

    return request$.pipe(
      tap(data => {
        if (isDefault && !isTenantScoped) this.auditCache = { data, ts: Date.now() };
      })
    );
  }

  invalidateAuditCache(): void {
    this.auditCache = null;
  }

  /**
   * Resolves a human-readable name for an entity given its type and UUID.
   * Results are cached permanently for the lifetime of the service.
   * Duplicate in-flight requests for the same key are deduplicated.
   */
  resolveEntityName(entityType: string, entityId: string): Observable<string> {
    const key = `${entityType.toUpperCase()}:${entityId}`;

    if (this.resolvedNames.has(key)) {
      return of(this.resolvedNames.get(key)!);
    }
    if (this.pendingNameRequests.has(key)) {
      return this.pendingNameRequests.get(key)!;
    }

    let source$: Observable<string>;
    switch (entityType.toUpperCase()) {
      case 'TENANT':
        source$ = this.api.get<any>(`/tenants/${entityId}`).pipe(
          map((t: any) => t?.name || entityId),
          catchError(() => of(entityId))
        );
        break;
      case 'USER':
        // Audit events store actorId = JWT sub (Keycloak user id), not the
        // internal staff_users.id — resolve via the keycloak-id endpoint so
        // the actor column shows a real name/email instead of a UUID.
        source$ = this.api.get<any>(`/staff-users/by-keycloak-id/${entityId}`).pipe(
          map((u: any) => {
            if (!u) return entityId;
            if (u.email) return u.email;
            if (u.firstName && u.lastName) return `${u.firstName} ${u.lastName}`;
            return entityId;
          }),
          catchError(() => of(entityId))
        );
        break;
      case 'AUTH':
        // AUTH entityId is a Keycloak session UUID — not resolvable via staff-users primary key.
        // Return as-is; the actorEmail field on the event carries the meaningful identity.
        return of(entityId);
      default:
        return of(entityId);
    }

    const shared$ = source$.pipe(
      tap(name => {
        this.resolvedNames.set(key, name);
        this.pendingNameRequests.delete(key);
      }),
      shareReplay(1)
    );

    this.pendingNameRequests.set(key, shared$);
    return shared$;
  }

  getTenantStats(tenantId: string): Observable<TenantStats> {
    return this.api.getWithHeaders<TenantStats>('/tenant-stats', { 'X-Tenant-ID': tenantId });
  }

  getTenantCharts(tenantId: string): Observable<TenantCharts> {
    return this.api.getWithHeaders<TenantCharts>('/tenant-stats/charts', { 'X-Tenant-ID': tenantId });
  }

  /** Daily audit event counts. The tenant interceptor adds X-Tenant-ID automatically on /tenant/ routes. */
  getAuditDailyCounts(days = 30): Observable<{ date: string; count: number }[]> {
    return this.api.get<{ date: string; count: number }[]>(
      '/audit/events/daily-counts',
      { days: String(days) },
    );
  }

  // Platform Settings
  getPlatformSettings(): Observable<any> {
    return this.api.get<any>('/platform/settings');
  }

  getEmailTemplates(): Observable<any[]> {
    return this.api.get<any[]>('/platform/email-templates');
  }

  getFeatureFlags(): Observable<any[]> {
    return this.api.get<any[]>('/platform/feature-flags');
  }

  // ── Tenant email templates ────────────────────────────────────────────────

  getTenantEmailTemplates(tenantId: string): Observable<TenantEmailTemplate[]> {
    return this.api.get<TenantEmailTemplate[]>(`/tenants/${tenantId}/email-templates`);
  }

  upsertTenantEmailTemplate(tenantId: string, key: string, body: TenantEmailTemplateUpdate): Observable<TenantEmailTemplate> {
    return this.api.put<TenantEmailTemplate>(`/tenants/${tenantId}/email-templates/${key}`, body);
  }

  resetTenantEmailTemplate(tenantId: string, key: string): Observable<void> {
    return this.api.delete<void>(`/tenants/${tenantId}/email-templates/${key}`);
  }
}

export interface TenantEmailTemplate {
  key: string;
  name: string;
  description: string;
  overridden: boolean;
  enabled: boolean;
  subject: string;
  htmlBody: string;
  textBody?: string;
  defaultSubject: string;
  defaultHtmlBody: string;
  defaultTextBody?: string;
  id?: string;
  version?: number;
  updatedAt?: string;
}

export interface TenantEmailTemplateUpdate {
  subject: string;
  htmlBody: string;
  textBody?: string;
  enabled?: boolean;
}
