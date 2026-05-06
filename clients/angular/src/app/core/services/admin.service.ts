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
}

export interface ScheduledJob {
  id: string;
  jobType: string;
  name: string;
  cronExpression: string;
  isEnabled: boolean;
  settings: string;
  lastExecutedAt: string | null;
  nextExecutionAt: string | null;
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
    tenantId?: string | null;
  }): Observable<StaffUser> {
    return this.api.post<StaffUser>('/staff-users', data);
  }

  updateStaffUser(id: string, data: {
    firstName?: string; lastName?: string; email?: string;
    phone?: string; jobTitle?: string; department?: string; realmRole?: string;
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

  createRole(data: any): Observable<Role> {
    return this.api.post<Role>('/roles', data);
  }

  // Scheduled Jobs
  getScheduledJobs(): Observable<ScheduledJob[]> {
    return this.api.get<ScheduledJob[]>('/scheduled-jobs');
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

  getTenantStats(tenantId: string): Observable<{
    totalStaff: number; activeStaff: number; suspendedStaff: number; pendingStaff: number;
    totalMembers: number; activeMembers: number; enrolledMembers: number;
  }> {
    return this.api.getWithHeaders('/tenant-stats', { 'X-Tenant-ID': tenantId });
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
}
