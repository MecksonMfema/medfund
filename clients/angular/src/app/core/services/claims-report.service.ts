import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 4 §A + §B claims-financial report family (claims-service). Every
 * read returns the uniform {@link ReportResponse} envelope — rows stay
 * native-currency (G25), the envelope carries `perCurrency` + best-effort
 * `fxRates` for header strips. All wrapped endpoints are gated by
 * {@code finance:view_subledger} + the tenant report key on the server.
 */

/** Drill-down dimension — the §B group / member legs extend the §A pair. */
export type ReportDimension = 'scheme' | 'provider' | 'group' | 'member';

/** Per (scheme|group|member|provider, currency) funnel row — CLAIMS_SUMMARY. */
export interface ClaimsSummaryRow {
  dimensionId: string;
  dimensionName: string;
  /** Populated when dimension = MEMBER; null/absent otherwise. */
  insuranceLine: string | null;
  currencyCode: string;
  claimCount: number;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
}

/** Paged envelope payload shared by members + status-matrix drill. */
export interface ReportPage<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface ClaimsSummaryParams {
  periodStart: string;
  periodEnd: string;
  reportingCurrency?: string;
  /** Optional insurance-line filter applied directly on claims.insurance_line. */
  insuranceLine?: string;
}

export interface ClaimsDetailParams {
  periodStart: string;
  periodEnd: string;
  status?: string;
  /** Ledger filter — meaningful on scheme / group / member dimensions. */
  providerId?: string;
  currency?: string;
  reportingCurrency?: string;
  page?: number;
  size?: number;
}

/** Per-member summary params — paginated + searchable (§B G45). */
export interface MemberClaimsSummaryParams {
  periodStart: string;
  periodEnd: string;
  reportingCurrency?: string;
  /** Plain ILIKE over member_number / first / last name. */
  search?: string;
  insuranceLine?: string;
  schemeId?: string;
  providerId?: string;
  page?: number;
  size?: number;
}

export interface ClaimsDetailResponse {
  dimensionId: string;
  dimensionName: string;
  monthlyBuckets: ClaimsMonthlyBucket[];
  claims: ClaimsPage;
}

export interface ClaimsMonthlyBucket {
  month: string;
  currencyCode: string;
  claimCount: number;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
}

export interface ClaimsPage {
  content: ClaimsLedgerRow[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface ClaimsLedgerRow {
  id: string;
  claimNumber: string;
  memberName: string;
  providerName: string;
  /** ISO-8601 instant (submission_date). */
  submissionDate: string;
  /** ISO date — DATE column, not an instant. */
  serviceDate: string;
  /** ISO-8601 instant (adjudicated_at). */
  adjudicatedAt: string;
  status: string;
  rejectionCode: string;
  claimedAmount: string;
  approvedAmount: string;
  paidAmount: string;
  currencyCode: string;
}

/** One member whose cumulative paid claims clear the tenant threshold. */
export interface HighCostClaimantRow {
  memberId: string;
  memberNumber: string;
  memberName: string;
  currencyCode: string;
  cumulativePaid: string;
  contributingClaims: number;
  /** Converted figure the threshold filter used — populated when a reporting currency is in play. */
  cumulativePaidReporting: string;
}

export interface PreAuthActivityParams {
  periodStart: string;
  periodEnd: string;
  reportingCurrency?: string;
  status?: string;
  providerId?: string;
}

export interface PreAuthActivityResponse {
  byStatus: PreAuthActivityRow[];
  r04r05Signal: R04R05SignalRow;
}

export interface PreAuthActivityRow {
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  currencyCode: string;
  count: number;
  totalRequested: string;
  totalApproved: string;
  /** Null for PENDING (no decision date yet). */
  avgDecisionDays: string | null;
  /** Filled on APPROVED rows. */
  approvalRatePct: string | null;
  /** Filled on EXPIRED rows. */
  expiryRatePct: string | null;
}

/** Claims-side proxy-utilisation signal — how often claims were rejected on R04 / R05. */
export interface R04R05SignalRow {
  r04Count: number;
  r05Count: number;
  totalClaimedInR04R05: string;
}

// ── CLAIM_STATUS_LIST (G49) ───────────────────────────────────────────────

export interface ClaimStatusMatrixParams {
  submittedFrom: string;
  submittedTo: string;
  reportingCurrency?: string;
  insuranceLine?: string;
}

/** One (status × age-bucket × currency) cell — ages relative to `asOf`. */
export interface ClaimStatusMatrixCell {
  status: string;
  ageBucket: string;
  claimCount: number;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
  currencyCode: string;
}

export interface ClaimStatusMatrixResponse {
  submittedFrom: string;
  submittedTo: string;
  cells: ClaimStatusMatrixCell[];
  /** ISO-8601 server clock at report time — the age anchor. */
  asOf: string;
}

export interface StatusMatrixDrillParams {
  submittedFrom: string;
  submittedTo: string;
  reportingCurrency?: string;
  status?: string;
  ageBucket?: string;
  page?: number;
  size?: number;
}

// ── DENIAL_ANALYSIS (G47) ─────────────────────────────────────────────────

export interface DenialAnalysisParams {
  periodStart: string;
  periodEnd: string;
  reportingCurrency?: string;
  category?: string;
  code?: string;
  providerId?: string;
}

export interface DenialCategoryRow {
  category: string;
  claimCount: number;
  totalClaimed: string;
}

export interface DenialCodeRow {
  code: string;
  category: string;
  description: string;
  claimCount: number;
  totalClaimed: string;
}

export interface DenialProviderRow {
  providerId: string;
  providerName: string;
  claimCount: number;
  totalClaimed: string;
  /** denied/total × 100 — always FX-safe (share ratio). */
  denialRatePct: string;
}

export interface DenialMonthlyRow {
  month: string;
  claimCount: number;
  totalClaimed: string;
}

export interface DenialAnalysisResponse {
  byCategory: DenialCategoryRow[];
  byCode: DenialCodeRow[];
  byProvider: DenialProviderRow[];
  /** Populated only for multi-month windows. */
  monthlyTrend: DenialMonthlyRow[];
}

// ── CLAIMS_FREQUENCY_SEVERITY (G48) ───────────────────────────────────────

export interface FrequencySeverityParams {
  serviceFrom: string;
  serviceTo: string;
  reportingCurrency?: string;
  insuranceLine?: string;
}

export interface FrequencySeverityRow {
  schemeId: string;
  schemeName: string;
  insuranceLine: string;
  /** Exposure proxy in member-months (G48 fallback). */
  exposureMemberMonths: string;
  claimCount: number;
  /** Annualised claims per member — server-side ratio. */
  frequency: string;
  currencyCode: string;
  severityMean: string;
  severityMedian: string;
  severityP95: string;
}

function summaryParams(opts: ClaimsSummaryParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  return p;
}

function detailParams(opts: ClaimsDetailParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.status)            p['status']            = opts.status;
  if (opts.providerId)        p['providerId']        = opts.providerId;
  if (opts.currency)          p['currency']          = opts.currency;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.page  !== undefined) p['page'] = String(opts.page);
  if (opts.size  !== undefined) p['size'] = String(opts.size);
  return p;
}

function preAuthParams(opts: PreAuthActivityParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.status)            p['status']            = opts.status;
  if (opts.providerId)        p['providerId']        = opts.providerId;
  return p;
}

function memberParams(opts: MemberClaimsSummaryParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.search)            p['search']            = opts.search;
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  if (opts.schemeId)          p['schemeId']          = opts.schemeId;
  if (opts.providerId)        p['providerId']        = opts.providerId;
  if (opts.page !== undefined) p['page'] = String(opts.page);
  if (opts.size !== undefined) p['size'] = String(opts.size);
  return p;
}

function statusMatrixParams(opts: ClaimStatusMatrixParams): Record<string, string> {
  const p: Record<string, string> = {
    submittedFrom: opts.submittedFrom,
    submittedTo:   opts.submittedTo,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  return p;
}

function statusMatrixDrillParams(opts: StatusMatrixDrillParams): Record<string, string> {
  const p: Record<string, string> = {
    submittedFrom: opts.submittedFrom,
    submittedTo:   opts.submittedTo,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.status)            p['status']            = opts.status;
  if (opts.ageBucket)         p['ageBucket']         = opts.ageBucket;
  if (opts.page !== undefined) p['page'] = String(opts.page);
  if (opts.size !== undefined) p['size'] = String(opts.size);
  return p;
}

function denialParams(opts: DenialAnalysisParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.category)          p['category']          = opts.category;
  if (opts.code)              p['code']              = opts.code;
  if (opts.providerId)        p['providerId']        = opts.providerId;
  return p;
}

function frequencySeverityParams(opts: FrequencySeverityParams): Record<string, string> {
  const p: Record<string, string> = {
    serviceFrom: opts.serviceFrom,
    serviceTo:   opts.serviceTo,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  return p;
}

@Injectable({ providedIn: 'root' })
export class ClaimsReportService {
  constructor(private api: ApiService) {}

  // ── Per-scheme (CLAIMS_SUMMARY) ─────────────────────────────────────────
  getClaimsPerScheme(opts: ClaimsSummaryParams): Observable<ReportResponse<ClaimsSummaryRow[]>> {
    return this.api.get<ReportResponse<ClaimsSummaryRow[]>>('/reports/claims/schemes', summaryParams(opts));
  }
  exportClaimsPerSchemeExcel(opts: ClaimsSummaryParams): Observable<Blob> {
    return this.api.getBlob('/reports/claims/schemes/export/excel', summaryParams(opts));
  }

  // ── Per-provider (CLAIMS_SUMMARY) ────────────────────────────────────────
  getClaimsPerProvider(opts: ClaimsSummaryParams): Observable<ReportResponse<ClaimsSummaryRow[]>> {
    return this.api.get<ReportResponse<ClaimsSummaryRow[]>>('/reports/claims/providers', summaryParams(opts));
  }
  exportClaimsPerProviderExcel(opts: ClaimsSummaryParams): Observable<Blob> {
    return this.api.getBlob('/reports/claims/providers/export/excel', summaryParams(opts));
  }

  // ── Drill-down detail (CLAIMS_SUMMARY) — scheme | provider | group | member ─
  getClaimsDetail(dimension: ReportDimension, id: string,
                  opts: ClaimsDetailParams): Observable<ReportResponse<ClaimsDetailResponse>> {
    return this.api.get<ReportResponse<ClaimsDetailResponse>>(
      `/reports/claims/${dimension}s/${id}`, detailParams(opts));
  }
  exportClaimsDetailExcel(dimension: ReportDimension, id: string,
                          opts: ClaimsDetailParams): Observable<Blob> {
    return this.api.getBlob(`/reports/claims/${dimension}s/${id}/export/excel`, detailParams(opts));
  }

  // ── Per-group (CLAIMS_SUMMARY, §B) ──────────────────────────────────────
  getClaimsPerGroup(opts: ClaimsSummaryParams): Observable<ReportResponse<ClaimsSummaryRow[]>> {
    return this.api.get<ReportResponse<ClaimsSummaryRow[]>>('/reports/claims/groups', summaryParams(opts));
  }
  exportClaimsPerGroupExcel(opts: ClaimsSummaryParams): Observable<Blob> {
    return this.api.getBlob('/reports/claims/groups/export/excel', summaryParams(opts));
  }

  // ── Per-member (CLAIMS_SUMMARY, §B G45) — paginated + searchable ─────────
  getClaimsPerMember(opts: MemberClaimsSummaryParams): Observable<ReportResponse<ReportPage<ClaimsSummaryRow>>> {
    return this.api.get<ReportResponse<ReportPage<ClaimsSummaryRow>>>('/reports/claims/members', memberParams(opts));
  }
  exportClaimsPerMemberExcel(opts: MemberClaimsSummaryParams): Observable<Blob> {
    return this.api.getBlob('/reports/claims/members/export/excel', memberParams(opts));
  }

  // ── CLAIM_STATUS_LIST (G49) ─────────────────────────────────────────────
  getClaimStatusMatrix(opts: ClaimStatusMatrixParams): Observable<ReportResponse<ClaimStatusMatrixResponse>> {
    return this.api.get<ReportResponse<ClaimStatusMatrixResponse>>(
      '/reports/claims/status-matrix', statusMatrixParams(opts));
  }
  getClaimStatusMatrixDrill(opts: StatusMatrixDrillParams): Observable<ReportResponse<ReportPage<ClaimsLedgerRow>>> {
    return this.api.get<ReportResponse<ReportPage<ClaimsLedgerRow>>>(
      '/reports/claims/status-matrix/drill', statusMatrixDrillParams(opts));
  }
  exportClaimStatusMatrixExcel(opts: ClaimStatusMatrixParams): Observable<Blob> {
    return this.api.getBlob('/reports/claims/status-matrix/export/excel', statusMatrixParams(opts));
  }

  // ── DENIAL_ANALYSIS (G47) ───────────────────────────────────────────────
  getDenialAnalysis(opts: DenialAnalysisParams): Observable<ReportResponse<DenialAnalysisResponse>> {
    return this.api.get<ReportResponse<DenialAnalysisResponse>>(
      '/reports/claims/denial-analysis', denialParams(opts));
  }
  exportDenialAnalysisExcel(opts: DenialAnalysisParams): Observable<Blob> {
    return this.api.getBlob('/reports/claims/denial-analysis/export/excel', denialParams(opts));
  }

  // ── CLAIMS_FREQUENCY_SEVERITY (G48) ─────────────────────────────────────
  getFrequencySeverity(opts: FrequencySeverityParams): Observable<ReportResponse<FrequencySeverityRow[]>> {
    return this.api.get<ReportResponse<FrequencySeverityRow[]>>(
      '/reports/claims/frequency-severity', frequencySeverityParams(opts));
  }
  exportFrequencySeverityExcel(opts: FrequencySeverityParams): Observable<Blob> {
    return this.api.getBlob('/reports/claims/frequency-severity/export/excel', frequencySeverityParams(opts));
  }

  // ── HIGH_COST_CLAIMANT (G46) ─────────────────────────────────────────────
  getHighCostClaimants(opts: ClaimsSummaryParams): Observable<ReportResponse<HighCostClaimantRow[]>> {
    return this.api.get<ReportResponse<HighCostClaimantRow[]>>(
      '/reports/claims/high-cost-claimants', summaryParams(opts));
  }
  exportHighCostClaimantsExcel(opts: ClaimsSummaryParams): Observable<Blob> {
    return this.api.getBlob('/reports/claims/high-cost-claimants/export/excel', summaryParams(opts));
  }

  // ── PRE_AUTH_ACTIVITY (G43) ──────────────────────────────────────────────
  getPreAuthActivity(opts: PreAuthActivityParams): Observable<ReportResponse<PreAuthActivityResponse>> {
    return this.api.get<ReportResponse<PreAuthActivityResponse>>(
      '/reports/claims/pre-auth-activity', preAuthParams(opts));
  }
  exportPreAuthActivityExcel(opts: PreAuthActivityParams): Observable<Blob> {
    return this.api.getBlob('/reports/claims/pre-auth-activity/export/excel', preAuthParams(opts));
  }
}
