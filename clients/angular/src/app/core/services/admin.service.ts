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
  /**
   * Pricing mode (V118/V119). STANDARD | INDIVIDUAL | AI_DRIVEN.
   * Drives whether billing honours per-member overrides and/or
   * applies the AI risk multiplier.
   */
  pricingModel?: 'STANDARD' | 'INDIVIDUAL' | 'AI_DRIVEN';
  /**
   * Member-number issuance scheme (V120). INDEPENDENT (default) gives
   * members "MBR-XXXXXX" + dependants "DEP-XXXXXX"; SHARED_WITH_SUFFIX
   * gives members "MBR-XXXXXX-01" + dependants "-02", "-03", … under
   * the same base.
   */
  memberNumberScheme?: 'INDEPENDENT' | 'SHARED_WITH_SUFFIX';
  /**
   * Regulator jurisdiction code (V131). Gates regulator-templated
   * reports — e.g. ZW_IPEC_SHORT_TERM, ZA_CMS_MEDICAL_SCHEME, US_NAIC.
   * Empty / undefined = no regulator-format reports surfaced.
   */
  jurisdictionCode?: string;
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
  /** Count of invoices at status 'issued' or 'overdue' — the real
   *  "requests awaiting payment" that drives the dashboard's Payments
   *  Requested card. Contributions are line items on an invoice; the
   *  operator wants to see how many INVOICES they have outstanding. */
  invoicesOutstanding: number;
  contributionsAmountThisMonth: number;
  contributionsAmountThisYear: number;
  // Finance — payment counts + currency totals.
  paymentsPending: number;
  paymentsAmountThisMonth: number;
  paymentsAmountThisYear: number;
  /** Advance payments — money disbursed to providers/members before a claim
   *  is adjudicated; the eventual claim is expected to cover it. */
  paymentsAdvanceCount: number;
  paymentsAdvanceAmount: number;
  paymentsFailedCount: number;
  /** Sum of (claimed_amount − paid_amount) across adjudicated claims with an
   *  outstanding gap — the insurer's unbooked liability. */
  claimsShortfallAmount: number;
  /** Per-currency breakdowns for multi-currency tenants. Keys are ISO codes
   *  ("USD", "ZAR", "ZWL"); values are BigDecimal amounts. Empty {} for
   *  tenants with no activity yet. */
  contributionsAmountThisMonthByCurrency: Record<string, number>;
  contributionsAmountThisYearByCurrency:  Record<string, number>;
  paymentsAmountThisMonthByCurrency:      Record<string, number>;
  paymentsAmountThisYearByCurrency:       Record<string, number>;
}

/**
 * 12-month trend points for the operational dashboard charts. Each series is
 * an array of {@code {name, value}} objects already shaped for ngx-charts —
 * the dashboard component wraps each in a single-series `[{ name: 'X', series: ... }]`
 * envelope before passing to {@code <app-area-chart>}.
 */
export interface TrendPoint { name: string; value: number; }

/** Pipeline distribution — drives the operational-dashboard pie chart. */
export interface ClaimsStatusBucket { status: string; count: number; }
export interface ClaimsStatusDistribution {
  total: number;
  buckets: ClaimsStatusBucket[];
}

/** Row shape for the dashboard's "10 most recent claims" table. */
export interface RecentClaim {
  id: string;
  claimNumber: string;
  status: string;
  claimedAmount: string;
  currencyCode: string;
  serviceDate: string;
  createdAt: string;
  /** insurance_line of the joined scheme (Part 4.6 — chip column). */
  insuranceLine?: string | null;
}

/** Per-adjudicator in-progress workload + the unassigned tail. */
export interface AdjudicatorRow {
  id: string;
  name: string;
  email: string;
  count: number;
}
export interface AdjudicatorWorkload {
  unassigned: number;
  adjudicators: AdjudicatorRow[];
}

/** Pipeline distribution for the Billing tab pie (paid/pending/...). Same shape as claims. */
export type ContributionsStatusDistribution = ClaimsStatusDistribution;

/** Row shape for the Billing tab "Recent contributions" table. */
export interface RecentContribution {
  id: string;
  amount: string;
  currencyCode: string;
  status: string;
  paymentMethod: string;
  periodStart: string;
  periodEnd: string;
  createdAt: string;
  memberNumber: string;
  memberName: string;
  /** insurance_line of the joined scheme (Part 4.6 — chip column). */
  insuranceLine?: string | null;
}

/** Row shape for the dashboard's Recent Invoices widget (V035). */
export interface RecentInvoice {
  id: string;
  invoiceNumber: string;
  holderType: 'GROUP' | 'INDIVIDUAL';
  holderName: string;
  holderNumber: string | null;
  totalAmount: string;
  currencyCode: string;
  openingBalance: string | null;
  closingBalance: string | null;
  periodStart: string;
  periodEnd: string;
  dueDate: string;
  issuedAt: string;
  committedAt: string | null;
  status: string;
  insuranceLines: string[];
  contributionCount: number;
  pdfReady: boolean;
}

/** Top debtor row — drives the "Outstanding by member" side card on Billing. */
export interface TopDebtor {
  id: string;
  memberNumber: string;
  name: string;
  outstanding: string;
  count: number;
}

// ── Finance tab ──────────────────────────────────────────────────────────

/** Pipeline distribution of payments by status. Same shape as claims/contributions. */
export type PaymentsStatusDistribution = ClaimsStatusDistribution;

/** Row shape for the "Recent payments" table on the Finance tab. */
export interface RecentPayment {
  id: string;
  paymentNumber: string;
  amount: string;
  currencyCode: string;
  paymentType: string;
  paymentMethod: string;
  status: string;
  reference: string;
  paidAt: string;
  createdAt: string;
  providerName: string;
}

/** Top-payee row — providers ranked by total payments received. */
export interface TopPayee {
  id: string;
  name: string;
  received: string;
  count: number;
}

/** Distribution of payments by method (EFT / mobile money / cash / ...). */
export interface PaymentMethodBucket {
  method: string;
  count: number;
  amount: string;
}
export interface PaymentMethodDistribution {
  total: number;
  buckets: PaymentMethodBucket[];
}

export interface TenantCharts {
  /** Blended-currency series (legacy single-line view, kept for back-compat). */
  claimsByMonth:              TrendPoint[];
  contributionsAmountByMonth: TrendPoint[];
  paymentsAmountByMonth:      TrendPoint[];
  /**
   * Per-currency 12-month trends — keys are currency codes from the tenant's
   * tenant_currency_config. Single-currency tenants see one entry; configure
   * additional currencies in the tenant-admin currencies screen and they
   * surface here. Backend orders the keys with the default currency first.
   */
  claimsByMonthByCurrency:              Record<string, TrendPoint[]>;
  contributionsAmountByMonthByCurrency: Record<string, TrendPoint[]>;
  paymentsAmountByMonthByCurrency:      Record<string, TrendPoint[]>;
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

/**
 * Projection returned by /scheduled-jobs/runs/recent-for-me. Joins
 * scheduled_job_runs to scheduled_job_configs so the header bell can
 * show "Billing commit · September billing" instead of "Job a1b2c3d4".
 * configName / jobType are nullable because the LEFT JOIN preserves
 * runs whose config has since been deleted.
 */
export interface ScheduledJobRunSummary {
  id: string;
  configId: string;
  configName: string | null;
  jobType: string | null;
  tenantId: string | null;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  status: ScheduledJobRunStatus;
  triggerKind: 'schedule' | 'manual';
  errorMessage: string | null;
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

  updateTenant(id: string, data: { name?: string; domain?: string; contactEmail?: string; timezone?: string; membershipModel?: string; settings?: string; branding?: string; pricingModel?: 'STANDARD' | 'INDIVIDUAL' | 'AI_DRIVEN'; memberNumberScheme?: 'INDEPENDENT' | 'SHARED_WITH_SUFFIX'; jurisdictionCode?: string }): Observable<Tenant> {
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

  /** Fetch a single staff user by UUID. Used by the liaison picker to
   *  resolve a saved staff liaisonUserId to a display chip. */
  getStaffUserById(id: string, tenantId?: string): Observable<StaffUser> {
    if (tenantId) {
      return this.api.getWithHeaders<StaffUser>(`/staff-users/${id}`, { 'X-Tenant-ID': tenantId });
    }
    return this.api.get<StaffUser>(`/staff-users/${id}`);
  }

  /**
   * Flat staff-user search for typeahead pickers. Pulls the first page of the
   * cursor-paginated list endpoint, scoped to {@code tenantId} if provided.
   * Returns the bare {@code StaffUser[]} content array so callers don't have
   * to unpack {@link CursorPage}.
   */
  searchStaffUsers(q: string, tenantId?: string, limit = 10): Observable<StaffUser[]> {
    return this.getStaffPage({ q, limit }, tenantId).pipe(
      map(page => page.content || []),
    );
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

  /**
   * Create a new scheduled job. Tenant-bound when tenantId is provided;
   * platform-wide when omitted. Backend accepts query params (matching the
   * Spring @RequestParam controller signature).
   */
  createScheduledJob(args: {
    jobType: string;
    name: string;
    cronExpression: string;
    settings?: string;
    tenantId?: string | null;
  }): Observable<ScheduledJob> {
    const qs = new URLSearchParams();
    qs.set('jobType', args.jobType);
    qs.set('name', args.name);
    qs.set('cronExpression', args.cronExpression);
    if (args.settings) qs.set('settings', args.settings);
    if (args.tenantId) qs.set('tenantId', args.tenantId);
    return this.api.post<ScheduledJob>(`/scheduled-jobs?${qs.toString()}`, {});
  }

  /**
   * Seed the 6 default jobs for a tenant. Useful for tenants provisioned
   * before the auto-seed hook landed, or when an admin wants to reset to
   * the default cadence. Pass null tenantId to seed platform-wide defaults.
   */
  seedDefaultJobs(tenantId: string | null): Observable<void> {
    const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : '';
    return this.api.post<void>(`/scheduled-jobs/seed-defaults${qs}`, {});
  }

  /** List the available job types (enum values + labels) for the create form. */
  listJobTypes(): Observable<{ type: string; displayName: string; description: string }[]> {
    return this.api.get<{ type: string; displayName: string; description: string }[]>('/scheduled-jobs/types');
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

  getTenantCharts(tenantId: string, period: 'day' | 'week' | 'month' = 'month'): Observable<TenantCharts> {
    return this.api.getWithHeaders<TenantCharts>('/tenant-stats/charts', { 'X-Tenant-ID': tenantId }, { period });
  }

  /** Claims grouped by status — drives the pipeline pie chart. */
  getClaimsStatusDistribution(tenantId: string): Observable<ClaimsStatusDistribution> {
    return this.api.getWithHeaders<ClaimsStatusDistribution>(
      '/tenant-stats/claims-status-distribution', { 'X-Tenant-ID': tenantId });
  }

  /** 10 most recent claims for the dashboard table. */
  getRecentClaims(tenantId: string): Observable<RecentClaim[]> {
    return this.api.getWithHeaders<RecentClaim[]>(
      '/tenant-stats/recent-claims', { 'X-Tenant-ID': tenantId });
  }

  /** Unassigned pending count + per-adjudicator in-progress counts. */
  getAdjudicatorWorkload(tenantId: string): Observable<AdjudicatorWorkload> {
    return this.api.getWithHeaders<AdjudicatorWorkload>(
      '/tenant-stats/adjudicator-workload', { 'X-Tenant-ID': tenantId });
  }

  /** Contributions grouped by status — Billing tab pipeline pie. */
  getContributionsStatusDistribution(tenantId: string): Observable<ContributionsStatusDistribution> {
    return this.api.getWithHeaders<ContributionsStatusDistribution>(
      '/tenant-stats/contributions-status-distribution', { 'X-Tenant-ID': tenantId });
  }

  /** 10 most recent contributions for the Billing tab table. */
  getRecentContributions(tenantId: string): Observable<RecentContribution[]> {
    return this.api.getWithHeaders<RecentContribution[]>(
      '/tenant-stats/recent-contributions', { 'X-Tenant-ID': tenantId });
  }

  /** 10 most recent invoices for the Billing tab (per-invoice rollup, V035). */
  getRecentInvoices(tenantId: string): Observable<RecentInvoice[]> {
    return this.api.getWithHeaders<RecentInvoice[]>(
      '/tenant-stats/recent-invoices', { 'X-Tenant-ID': tenantId });
  }

  /** Top 10 members by outstanding contribution amount — Billing side card. */
  getTopDebtors(tenantId: string): Observable<TopDebtor[]> {
    return this.api.getWithHeaders<TopDebtor[]>(
      '/tenant-stats/top-debtors', { 'X-Tenant-ID': tenantId });
  }

  /** Payments grouped by status — Finance pipeline pie. */
  getPaymentsStatusDistribution(tenantId: string): Observable<PaymentsStatusDistribution> {
    return this.api.getWithHeaders<PaymentsStatusDistribution>(
      '/tenant-stats/payments-status-distribution', { 'X-Tenant-ID': tenantId });
  }

  /** 10 most recent payments — Finance table. */
  getRecentPayments(tenantId: string): Observable<RecentPayment[]> {
    return this.api.getWithHeaders<RecentPayment[]>(
      '/tenant-stats/recent-payments', { 'X-Tenant-ID': tenantId });
  }

  /** Top 10 providers by total received — Finance side card. */
  getTopPayees(tenantId: string): Observable<TopPayee[]> {
    return this.api.getWithHeaders<TopPayee[]>(
      '/tenant-stats/top-payees', { 'X-Tenant-ID': tenantId });
  }

  /** Payment volume per method — Finance breakdown card. */
  getPaymentMethodDistribution(tenantId: string): Observable<PaymentMethodDistribution> {
    return this.api.getWithHeaders<PaymentMethodDistribution>(
      '/tenant-stats/payment-method-distribution', { 'X-Tenant-ID': tenantId });
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
