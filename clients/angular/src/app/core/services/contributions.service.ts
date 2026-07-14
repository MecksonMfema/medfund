import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface Scheme {
  id: string;
  name: string;
  description?: string;
  schemeType?: string;
  /** Insurance product line this scheme belongs to (HEALTH, LIFE, VEHICLE, …).
   *  Drives whether Age Groups / Scheme Benefits screens apply. Backed by the
   *  schemes.insurance_line column (NOT NULL, default HEALTH). */
  insuranceLine?: string;
  status: string;
  effectiveDate: string;
  endDate?: string;
  currencyCode?: string;
  /** V050 age-eligibility gate — see {@link UpsertSchemePayload}. */
  minAge?: number;
  maxAge?: number;
  /** V061 product-level ledger opt-out. Null defaults to true. When false
   *  no per-member beneficiary_benefits rows are seeded and Stage 3
   *  skips the balance check (indemnity model). */
  tracksMemberBalances?: boolean;
}

/**
 * V061 — combined scheme + per-benefit usage-mode snapshot. Used by the
 * claim-detail page to decide whether to render the utilization card
 * and how each row should look (progress bar vs one-time chip vs
 * per-event counter). One call, no N+1 fetches.
 */
export interface SchemeProductProfile {
  schemeId: string;
  insuranceLine: string | null;
  schemeType: string | null;
  tracksMemberBalances: boolean;
  /**
   * V062 scheme-level aggregate cap per beneficiary per policy year.
   * NULL means no cap; only multi-benefit insurance lines typically set
   * one. When present, the claim-detail utilization card renders an
   * "Annual cap" progress row above the per-benefit rows.
   */
  annualMemberCap?: number | null;
  benefitUsageModes: SchemeBenefitUsageMode[];
}

export interface SchemeBenefitUsageMode {
  benefitId: string;
  name: string;
  benefitType: string | null;
  usageMode: BenefitUsageMode;
}

/**
 * V062 scheme-level annual cap utilization. Populated by
 * GET /beneficiary-annual-totals/for. capAmount is null when the scheme
 * opts out of the aggregate cap — the UI omits the row entirely in that
 * case. consumedAmount is 0 when the beneficiary hasn't consumed yet.
 */
export interface AnnualCapUtilization {
  schemeId: string;
  memberId: string;
  dependantId?: string | null;
  policyYear: number;
  consumedAmount: number;
  capAmount: number | null;
  currencyCode: string;
}

/** V061 benefit-level usage classification. See scheme_benefits.usage_mode. */
export type BenefitUsageMode =
  | 'RUNNING_BALANCE'
  | 'ONE_TIME_PER_BENEFICIARY'
  | 'ONE_TIME_PER_PERIOD'
  | 'PER_EVENT_COUNTER'
  | 'NO_TRACKING';

export interface SchemesPage {
  content: Scheme[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface BenefitsPage {
  content: SchemeBenefit[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface AgeGroup {
  id: string;
  schemeId: string;
  name: string;
  minAge: number;
  maxAge: number;
  contributionAmount: string;
  currencyCode: string;
  status?: string;
}

export interface GroupOption {
  id: string;
  name: string;
  registrationNumber?: string;
}

/**
 * Per-(member or dependant, benefit, policy year) utilization row.
 * Denormalised on the backend so the response carries the benefit's
 * name + limits inline — one round trip per beneficiary is enough for
 * the claim-detail utilization strip.
 */
export interface BeneficiaryBenefitUtilization {
  id: string;
  memberId: string;
  dependantId?: string | null;
  benefitId: string;
  benefitName: string | null;
  benefitType: string | null;
  /** V061 usage classification. Drives conditional rendering on the
   *  claim-detail utilization card (progress bar vs. one-time chip
   *  vs. per-event counter). Defaults to RUNNING_BALANCE server-side. */
  usageMode: BenefitUsageMode;
  policyYear: number;
  annualLimit?: string | null;
  eventLimit?: string | null;
  dailyLimit?: string | null;
  waitingPeriodDays?: number | null;
  consumedAmount: string;
  consumedCount: number;
  /** V061 pre-computed remaining amount for RUNNING_BALANCE /
   *  PER_EVENT_COUNTER benefits. Null when the benefit is untracked
   *  or has no annual limit. */
  remaining?: string | null;
  /** V061 status hint: available | exhausted | unlimited | untracked. */
  status: 'available' | 'exhausted' | 'unlimited' | 'untracked';
  currencyCode: string;
}

export interface SchemeBenefit {
  id: string;
  schemeId: string;
  name: string;
  benefitType: string;
  annualLimit?: string;
  dailyLimit?: string;
  eventLimit?: string;
  currencyCode: string;
  waitingPeriodDays?: number;
  description?: string;
  status?: string;
  /** V051 benefit-age gate. Null = unbounded. AdjudicationPipeline Stage 3
   *  rejects with AGE_OUT_OF_RANGE when member.age (at service date) falls outside. */
  minAge?: number;
  maxAge?: number;
  /** V051 payout-mode flag. When false, rules-engine template R51 rejects
   *  CASH-payout claims for this benefit (must pay provider directly). */
  cashClaimAllowed?: boolean;
  /** V061 usage classification. Defaults to RUNNING_BALANCE server-side. */
  usageMode?: BenefitUsageMode;
  /** V063 tariff-category coverage. Populated on the detail endpoint
   *  (GET /schemes/benefits/{id}) so the edit form can pre-populate the
   *  Categories multi-select. Empty on the list endpoint. */
  categoryIds?: string[];
  createdAt?: string;
  updatedAt?: string;
}

export interface UpsertBenefitPayload {
  schemeId?: string;
  name: string;
  benefitType: string;
  annualLimit?: string;
  dailyLimit?: string;
  eventLimit?: string;
  currencyCode?: string;
  waitingPeriodDays?: number;
  description?: string;
  minAge?: number;
  maxAge?: number;
  cashClaimAllowed?: boolean;
  /** V063 — mandatory on create, replaces the join rows on update.
   *  Undefined on update = leave links as-is. Empty array = wipe all
   *  links (backend validator @NotEmpty enforces at least one on
   *  typical submits). */
  categoryIds?: string[];
}

export interface Contribution {
  id: string;
  memberId: string;
  groupId: string | null;
  schemeId: string;
  amount: number;
  currencyCode: string;
  periodStart: string;
  periodEnd: string;
  status: string;
}

/**
 * Row shape returned by GET /contributions/page. Member, group, and
 * scheme display fields pre-joined server-side so the statements
 * table renders inline without a second lookup.
 */
export interface ContributionRow {
  id: string;
  memberId?: string;
  memberName?: string;
  memberNumber?: string;
  groupId?: string;
  groupName?: string;
  schemeId?: string;
  schemeName?: string;
  amount: string;
  currencyCode: string;
  periodStart: string;
  periodEnd?: string;
  status: string;
  paymentMethod?: string;
  paymentReference?: string;
  paidAt?: string;
  createdAt: string;
  updatedAt?: string;
}

/**
 * Envelope for every server-side paginated endpoint in contributions-
 * service. Kept local so contributions.service.ts doesn't reach into
 * balance.service.ts for a shared symbol.
 */
export interface ContributionsPageResponse<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface ContributionPageParams {
  status?: string;
  memberId?: string;
  groupId?: string;
  schemeId?: string;
  currencyCode?: string;
  periodStartFrom?: string;
  periodStartTo?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export interface Transaction {
  id: string;
  transactionNumber: string;
  contributionId?: string;
  invoiceId?: string;
  amount: string;
  currencyCode: string;
  transactionType: string;
  paymentMethod?: string;
  reference?: string;
  status: string;
  transactionDate?: string;
  createdAt: string;
}

export interface Invoice {
  id: string;
  invoiceNumber: string;
  groupId: string | null;
  totalAmount: number;
  currencyCode: string;
  status: string;
  dueDate: string;
}

// ── Per-invoice listing + statement (V035 / plan §1, §3d) ─────────────
export interface InvoiceListRow {
  id: string;
  invoiceNumber: string;
  holderType: 'GROUP' | 'INDIVIDUAL';
  holderName: string;
  holderNumber: string | null;
  schemeNames: string;
  insuranceLines: string[];
  periodStart: string;
  periodEnd: string;
  totalAmount: string;
  currencyCode: string;
  contributionCount: number;
  status: string;
  dueDate: string;
  issuedAt: string;
  paidAt: string | null;
  committedAt: string | null;
  openingBalance: string | null;
  closingBalance: string | null;
  pdfReady: boolean;
}

export interface InvoicesPage {
  content: InvoiceListRow[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface InvoiceListFilter {
  year?: number;
  month?: number;
  status?: string;
  insuranceLine?: string;
  currency?: string;
  holderType?: 'GROUP' | 'INDIVIDUAL';
  q?: string;
  page?: number;
  size?: number;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
}

export interface InvoiceContributionRow {
  contributionId: string;
  memberNumber: string;
  memberName: string;
  personType: 'MEMBER' | 'DEPENDANT';
  dependantName: string | null;
  schemeName: string;
  insuranceLine: string;
  ageBand: string | null;
  amount: string;
  currencyCode: string;
}

export interface UpsertSchemePayload {
  name: string;
  description?: string;
  schemeType?: string;
  /** Derived from schemeType via LINE_FOR_SCHEME_TYPE on the form. The form
   *  always sets this; the backend defaults to HEALTH if omitted. */
  insuranceLine?: string;
  effectiveDate: string;
  endDate?: string;
  currencyCode?: string;
  /** V050 enrolment age-eligibility gate. Backend rejects enrolments whose
   *  age at enrolment falls outside [minAge, maxAge]. Null / omitted = unbounded.
   *  Only sent for person-centric insurance lines. */
  minAge?: number;
  maxAge?: number;
}

export interface UpsertAgeGroupPayload {
  schemeId: string;
  name: string;
  minAge: number;
  maxAge: number;
  contributionAmount: string;
  currencyCode?: string;
}

export interface BillingFilterPayload {
  periodStart: string;
  periodEnd: string;
  groupIds?: string[];
  memberIds?: string[];
}

export interface BillingPreviewSampleRow {
  memberId: string;
  /** Set only when this row is a dependant's line. Null for the member's own line. */
  dependantId: string | null;
  memberNumber: string;
  /** Display name — member's name on the member's line, dependant's name on the dependant's line. */
  personName: string;
  /** "MEMBER" or "DEPENDANT". */
  personType: 'MEMBER' | 'DEPENDANT';
  schemeId: string;
  schemeName: string;
  groupId: string | null;
  /** Friendly group name — null for individual invoices and ungrouped members. */
  groupName: string | null;
  /** Friendly band label (Adult, Senior, …) — null when no band matched. */
  ageBand: string | null;
  amount: string;
  currencyCode: string;
}

export interface BillingPreviewResponse {
  totalRows: number;
  totalsByCurrency: Record<string, string>;
  sample: BillingPreviewSampleRow[];
  cooldownActive: boolean;
  cooldownRemainingMinutes: number | null;
  /** Aggregate invoices the commit would create (one per group + currency). */
  groupInvoicesProjected: number;
  /** Per-member invoices the commit would create. */
  individualInvoicesProjected: number;
  /** Tenant's current membership model (INDIVIDUAL_ONLY / GROUP_ONLY / BOTH). */
  membershipModel: string;
}

export interface BillingCommitResponse {
  contributionsCreated: number;
  totalsByCurrency: Record<string, string>;
  committedAt: string;
  groupInvoicesCreated: number;
  individualInvoicesCreated: number;
  membershipModel: string;
}

export interface ChargePreviewLine {
  memberId: string;
  dependantId: string | null;
  memberNumber: string;
  personName: string;
  personType: 'MEMBER' | 'DEPENDANT';
  schemeId: string;
  schemeName: string;
  groupId: string | null;
  groupName: string | null;
  ageGroupId: string | null;
  ageBandName: string | null;
  amount: string;             // BigDecimal serialised as string
  currencyCode: string;
  isCustomPriced: boolean;
  scheduledSchemeChangeFrom: string | null;   // ISO date, populated when a future age-band kicks in during the projected cycle
}

export interface ChargePreviewResponse {
  subjectType: 'GROUP' | 'MEMBER';
  subjectId: string;
  subjectName: string;
  periodStart: string;                          // ISO date
  periodEnd: string;                            // ISO date
  lines: ChargePreviewLine[];
  totals: Record<string, string>;               // currency → sum
  excludedTerminating: number;                  // rows dropped due to termination_date < periodStart
  asOf: string;                                 // ISO instant — surface as "as of HH:MM:SS" so the operator knows the number is live
}

export interface EnqueueBillingPayload extends BillingFilterPayload {
  kind: 'preview' | 'commit';
  /**
   * Multi-line wizard tab choice (Part 4.5). Omit for single-line
   * tenants — backend defaults to HEALTH. Multi-line tenants must
   * pick a line so the dispatcher routes to the matching
   * CandidateResolver.
   */
  insuranceLine?: string;
}

export interface RevokeBillingPayload {
  periodStart: string;
  periodEnd: string;
  insuranceLine?: string;
}

export interface BillingRevokeResponse {
  contributionsDeleted: number;
  invoicesDeleted: number;
  periodStart: string;
  periodEnd: string;
  insuranceLine: string | null;
  revokedAt: string;
}

export interface EnqueueBillingResponse {
  configId: string;
  runId: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  startedAt: string;
}

/** Shape of /api/v1/scheduled-jobs/{configId}/runs rows that the wizard polls. */
export interface ScheduledJobRun {
  id: string;
  configId: string;
  tenantId: string | null;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  triggerKind: 'schedule' | 'manual';
  errorMessage: string | null;
  triggeredBy: string | null;
  /** JSON string returned by ResultfulJobExecutor — parse to read the
   *  preview/commit response. {@code null} until the run finishes. */
  resultPayload: string | null;
}

export interface RecordTransactionPayload {
  /** Exactly one of groupId/memberId is required — the owner whose
   *  balance the transaction moves. Payments no longer link to a
   *  contribution at creation time; the balance service allocates the
   *  amount against outstanding contributions later. */
  groupId?: string;
  memberId?: string;
  amount: string;
  currencyCode: string;
  transactionType: string;
  paymentMethod?: string;
  reference?: string;
  /** Operator-supplied justification. Required by the backend for
   *  CREDIT / DEBIT adjustments; ignored for other types. */
  reason?: string;
}

export interface TransactionSearchParams {
  currency?: string;
  transactionType?: string;
  paymentMethod?: string;
  periodStart?: string;  // YYYY-MM-DD
  periodEnd?: string;    // YYYY-MM-DD
  contributionId?: string;
  invoiceId?: string;
  q?: string;
  page?: number;
  size?: number;
}

export interface TransactionsPage {
  content: Transaction[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

@Injectable({ providedIn: 'root' })
export class ContributionsService {
  constructor(private api: ApiService) {}

  // ── Schemes ──
  getSchemes(): Observable<Scheme[]> {
    return this.api.get<Scheme[]>('/schemes');
  }

  /**
   * Server-side paginated + sortable scheme list. Sort key must be one of the
   * backend-whitelisted camelCase keys (name, schemeType, currencyCode, status,
   * effectiveDate, createdAt) — anything else falls back to name ASC on the
   * server.
   */
  getSchemesPaged(opts: {
    page: number;            // 0-indexed
    size: number;
    sortKey?: string;
    sortDirection?: 'asc' | 'desc';
    q?: string;
    status?: string;
    insuranceLine?: string;
    schemeType?: string;
  }): Observable<SchemesPage> {
    const params: Record<string, string> = {
      page: String(opts.page),
      size: String(opts.size),
    };
    if (opts.sortKey)       params['sortKey']       = opts.sortKey;
    if (opts.sortDirection) params['sortDirection'] = opts.sortDirection;
    if (opts.q)             params['q']             = opts.q;
    if (opts.status)        params['status']        = opts.status;
    if (opts.insuranceLine) params['insuranceLine'] = opts.insuranceLine;
    if (opts.schemeType)    params['schemeType']    = opts.schemeType;
    return this.api.get<SchemesPage>('/schemes/page', params);
  }

  getSchemeById(id: string): Observable<Scheme> {
    return this.api.get<Scheme>(`/schemes/${id}`);
  }

  /**
   * V061 — product profile for a scheme. Returns scheme-level
   * tracks_member_balances plus the usage_mode of every active benefit
   * on the scheme. Powers the claim-detail header product-type badge and
   * the utilization card's branch logic without an N+1 fetch.
   */
  getSchemeProductProfile(id: string): Observable<SchemeProductProfile> {
    return this.api.get<SchemeProductProfile>(`/schemes/${id}/product-profile`);
  }

  /** Free-text scheme search — feeds the operational scheme picker. */
  searchSchemes(q: string, limit = 10): Observable<Scheme[]> {
    return this.api.get<Scheme[]>('/schemes/search', { q, limit: String(limit) });
  }

  createScheme(data: UpsertSchemePayload): Observable<Scheme> {
    return this.api.post<Scheme>('/schemes', data);
  }

  updateScheme(id: string, data: UpsertSchemePayload): Observable<Scheme> {
    return this.api.put<Scheme>(`/schemes/${id}`, data);
  }

  /** Soft-delete: flips the scheme's status to 'inactive'. */
  deactivateScheme(id: string): Observable<Scheme> {
    return this.api.post<Scheme>(`/schemes/${id}/deactivate`, {});
  }

  /** Re-enables a previously-deactivated scheme. */
  activateScheme(id: string): Observable<Scheme> {
    return this.api.post<Scheme>(`/schemes/${id}/activate`, {});
  }

  // ── Groups (read-only autocomplete) ──
  searchGroups(q: string, limit = 20): Observable<GroupOption[]> {
    const params: Record<string, string> = { limit: String(limit) };
    if (q) params['q'] = q;
    return this.api.get<GroupOption[]>('/billing/groups/search', params);
  }

  // ── Age groups ──
  getAgeGroupsByScheme(schemeId: string): Observable<AgeGroup[]> {
    return this.api.get<AgeGroup[]>(`/schemes/${schemeId}/age-groups`);
  }

  createAgeGroup(data: UpsertAgeGroupPayload): Observable<AgeGroup> {
    return this.api.post<AgeGroup>('/schemes/age-groups', data);
  }

  getAgeGroupById(id: string): Observable<AgeGroup> {
    return this.api.get<AgeGroup>(`/schemes/age-groups/${id}`);
  }

  updateAgeGroup(id: string, data: UpsertAgeGroupPayload): Observable<AgeGroup> {
    return this.api.put<AgeGroup>(`/schemes/age-groups/${id}`, data);
  }

  /** Soft-delete: flips the age group's status to 'inactive'. */
  deactivateAgeGroup(id: string): Observable<AgeGroup> {
    return this.api.post<AgeGroup>(`/schemes/age-groups/${id}/deactivate`, {});
  }

  /** Re-enables a previously-deactivated age group. */
  activateAgeGroup(id: string): Observable<AgeGroup> {
    return this.api.post<AgeGroup>(`/schemes/age-groups/${id}/activate`, {});
  }

  // ── Scheme benefits ──
  /** Per-beneficiary utilization vs. scheme limits. Powers the claim-
   *  detail "used $180 of $500" strip. Pass memberId alone for the
   *  member's own rows; add dependantId to scope to a dependant. */
  getBeneficiaryUtilization(memberId: string, dependantId?: string | null,
                              policyYear?: number): Observable<BeneficiaryBenefitUtilization[]> {
    const params: Record<string, string> = { memberId };
    if (dependantId) params['dependantId'] = dependantId;
    if (policyYear) params['policyYear'] = String(policyYear);
    return this.api.get<BeneficiaryBenefitUtilization[]>('/beneficiary-benefits/for', params);
  }

  /**
   * V062 scheme-level annual cap utilization for a beneficiary in one
   * policy year. Only meaningful for schemes with annualMemberCap set —
   * NULL capAmount in the response means the scheme opts out and the
   * UI omits the cap row.
   */
  getAnnualCapUtilization(memberId: string, schemeId: string,
                            dependantId?: string | null,
                            policyYear?: number): Observable<AnnualCapUtilization> {
    const params: Record<string, string> = { memberId, schemeId };
    if (dependantId) params['dependantId'] = dependantId;
    if (policyYear) params['policyYear'] = String(policyYear);
    return this.api.get<AnnualCapUtilization>('/beneficiary-annual-totals/for', params);
  }

  getBenefitsByScheme(schemeId: string): Observable<SchemeBenefit[]> {
    return this.api.get<SchemeBenefit[]>(`/schemes/${schemeId}/benefits`);
  }

  /**
   * Server-side paginated + sortable + searchable benefits list. The sort
   * key must be one of the backend-whitelisted camelCase keys (name,
   * benefitType, status, waitingPeriodDays, annualLimit, dailyLimit,
   * eventLimit, createdAt) — anything else falls back to name ASC on the
   * server.
   */
  getBenefitsBySchemePaged(schemeId: string, opts: {
    page: number;            // 0-indexed
    size: number;
    sortKey?: string;
    sortDirection?: 'asc' | 'desc';
    q?: string;
    status?: string;
    benefitType?: string;
  }): Observable<BenefitsPage> {
    const params: Record<string, string> = {
      page: String(opts.page),
      size: String(opts.size),
    };
    if (opts.sortKey)       params['sortKey']       = opts.sortKey;
    if (opts.sortDirection) params['sortDirection'] = opts.sortDirection;
    if (opts.q)             params['q']             = opts.q;
    if (opts.status)        params['status']        = opts.status;
    if (opts.benefitType)   params['benefitType']   = opts.benefitType;
    return this.api.get<BenefitsPage>(`/schemes/${schemeId}/benefits/page`, params);
  }

  getBenefitById(id: string): Observable<SchemeBenefit> {
    return this.api.get<SchemeBenefit>(`/schemes/benefits/${id}`);
  }

  createBenefit(data: UpsertBenefitPayload): Observable<SchemeBenefit> {
    return this.api.post<SchemeBenefit>('/schemes/benefits', data);
  }

  updateBenefit(id: string, data: UpsertBenefitPayload): Observable<SchemeBenefit> {
    return this.api.put<SchemeBenefit>(`/schemes/benefits/${id}`, data);
  }

  /** Soft-delete: flips the benefit's status to 'inactive'. Hard-delete
   *  is no longer supported — see the controller comment in SchemeController. */
  deactivateBenefit(id: string): Observable<SchemeBenefit> {
    return this.api.post<SchemeBenefit>(`/schemes/benefits/${id}/deactivate`, {});
  }

  /** Re-enables a previously-deactivated benefit. */
  activateBenefit(id: string): Observable<SchemeBenefit> {
    return this.api.post<SchemeBenefit>(`/schemes/benefits/${id}/activate`, {});
  }

  // ── Contributions ──
  getContributionsByMember(memberId: string): Observable<Contribution[]> {
    return this.api.get<Contribution[]>(`/contributions/member/${memberId}`);
  }

  getContributionsByGroup(groupId: string): Observable<Contribution[]> {
    return this.api.get<Contribution[]>(`/contributions/group/${groupId}`);
  }

  getContributionsByStatus(status: string): Observable<Contribution[]> {
    return this.api.get<Contribution[]>(`/contributions/status/${status}`);
  }

  /**
   * Server-side paginated contributions list. Feeds
   * /tenant/billing/contributions. Rows carry pre-joined member +
   * group + scheme display fields.
   */
  listContributionsPaged(opts: ContributionPageParams): Observable<ContributionsPageResponse<ContributionRow>> {
    const params: Record<string, string> = {};
    if (opts.status)          params['status']          = opts.status;
    if (opts.memberId)        params['memberId']        = opts.memberId;
    if (opts.groupId)         params['groupId']         = opts.groupId;
    if (opts.schemeId)        params['schemeId']        = opts.schemeId;
    if (opts.currencyCode)    params['currencyCode']    = opts.currencyCode;
    if (opts.periodStartFrom) params['periodStartFrom'] = opts.periodStartFrom;
    if (opts.periodStartTo)   params['periodStartTo']   = opts.periodStartTo;
    if (opts.q)               params['q']               = opts.q;
    if (opts.sortKey)         params['sortKey']         = opts.sortKey;
    if (opts.sortDirection)   params['sortDirection']   = opts.sortDirection;
    if (opts.page !== undefined) params['page']         = String(opts.page);
    if (opts.size !== undefined) params['size']         = String(opts.size);
    return this.api.get<ContributionsPageResponse<ContributionRow>>('/contributions/page', params);
  }

  previewBilling(filters: BillingFilterPayload): Observable<BillingPreviewResponse> {
    return this.api.post<BillingPreviewResponse>('/contributions/preview', filters);
  }

  /**
   * Live per-subject charge projection for the next billing cycle.
   * Server-side only per feedback_stats_serverside — the response is
   * pre-aggregated so the client just renders. Backend excludes
   * terminating members and applies the same pricing rules as a real
   * commit; the projected amount matches what the next billing run
   * would post.
   */
  chargePreview(
    subjectType: 'GROUP' | 'MEMBER',
    subjectId: string,
    currency?: string,
  ): Observable<ChargePreviewResponse> {
    const params: Record<string, string> = { subjectType, subjectId };
    if (currency) params['currency'] = currency;
    return this.api.get<ChargePreviewResponse>('/contributions/charge-preview', params);
  }

  commitBilling(filters: BillingFilterPayload): Observable<BillingCommitResponse> {
    return this.api.post<BillingCommitResponse>('/contributions/commit', filters);
  }

  /**
   * Enqueue a billing preview or commit as a background job. Returns
   * configId + runId so the caller can short-poll the runs endpoint.
   */
  enqueueBilling(payload: EnqueueBillingPayload): Observable<EnqueueBillingResponse> {
    return this.api.post<EnqueueBillingResponse>('/contributions/billing/enqueue', payload);
  }

  /**
   * Revoke a billing run for the next-month window. RBAC-gated by
   * 'billing:revoke_billing' on the caller's permissions; the backend
   * also enforces the next-month-only rule (returns 422 outside the
   * window). Deletes all contributions + invoices for the (period,
   * line) so a corrected re-commit can run.
   */
  revokeBilling(payload: RevokeBillingPayload): Observable<BillingRevokeResponse> {
    return this.api.post<BillingRevokeResponse>('/contributions/billing/revoke', payload);
  }

  // ── Per-invoice listing + statement + PDF (V035) ────────────────────
  listInvoices(filter: InvoiceListFilter = {}): Observable<InvoicesPage> {
    const params: Record<string, string> = {};
    if (filter.year !== undefined)        params['year']          = String(filter.year);
    if (filter.month !== undefined)       params['month']         = String(filter.month);
    if (filter.status)                    params['status']        = filter.status;
    if (filter.insuranceLine)             params['insuranceLine'] = filter.insuranceLine;
    if (filter.currency)                  params['currency']      = filter.currency;
    if (filter.holderType)                params['holderType']    = filter.holderType;
    if (filter.q)                         params['q']             = filter.q;
    if (filter.page !== undefined)        params['page']          = String(filter.page);
    if (filter.size !== undefined)        params['size']          = String(filter.size);
    if (filter.sortKey)                   params['sortKey']       = filter.sortKey;
    if (filter.sortDirection)             params['sortDirection'] = filter.sortDirection;
    return this.api.get<InvoicesPage>('/invoices', params);
  }

  /** Snapshot-backed per-invoice statement (opening/closing fixed at commit time). */
  getInvoiceStatement(id: string): Observable<any> {
    return this.api.get<any>(`/invoices/${id}/statement`);
  }

  /** Per-scheme member breakdown for the statement detail page. */
  getInvoiceContributions(id: string): Observable<InvoiceContributionRow[]> {
    return this.api.get<InvoiceContributionRow[]>(`/invoices/${id}/contributions`);
  }

  /** Absolute URL the row's Download PDF button hrefs to. The browser streams. */
  getInvoicePdfUrl(id: string): string {
    return this.api.absoluteUrl(`/invoices/${id}/pdf`);
  }

  /** Single-invoice metadata for the statement page header. */
  getInvoiceById(id: string): Observable<any> {
    return this.api.get<any>(`/invoices/${id}`);
  }

  /**
   * Fetch the latest N runs for a scheduled-job config. The wizard polls
   * this with limit=1 to track the running/success/failed state of an
   * enqueued billing job.
   */
  listJobRuns(configId: string, limit = 1): Observable<ScheduledJobRun[]> {
    return this.api.get<ScheduledJobRun[]>(
      `/scheduled-jobs/${configId}/runs`,
      { limit: String(limit) },
    );
  }

  // ── Transactions ──
  searchTransactions(params: TransactionSearchParams = {}): Observable<TransactionsPage> {
    const q: Record<string, string> = {};
    if (params.currency)        q['currency'] = params.currency;
    if (params.transactionType) q['transactionType'] = params.transactionType;
    if (params.paymentMethod)   q['paymentMethod'] = params.paymentMethod;
    if (params.periodStart)     q['periodStart'] = params.periodStart;
    if (params.periodEnd)       q['periodEnd'] = params.periodEnd;
    if (params.contributionId)  q['contributionId'] = params.contributionId;
    if (params.invoiceId)       q['invoiceId'] = params.invoiceId;
    if (params.q)               q['q'] = params.q;
    if (params.page !== undefined) q['page'] = String(params.page);
    if (params.size !== undefined) q['size'] = String(params.size);
    return this.api.get<TransactionsPage>('/transactions', q);
  }

  recordTransaction(data: RecordTransactionPayload): Observable<Transaction> {
    return this.api.post<Transaction>('/transactions', data);
  }

  getTransactionsByContribution(contributionId: string): Observable<Transaction[]> {
    return this.api.get<Transaction[]>(`/transactions/contribution/${contributionId}`);
  }

  // ── Invoices ──
  getInvoicesByGroup(groupId: string): Observable<Invoice[]> {
    return this.api.get<Invoice[]>(`/invoices/group/${groupId}`);
  }

  getInvoicesByStatus(status: string): Observable<Invoice[]> {
    return this.api.get<Invoice[]>(`/invoices/status/${status}`);
  }
}
