import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type ClaimStatus =
  | 'SUBMITTED' | 'VERIFIED' | 'IN_ADJUDICATION'
  | 'ADJUDICATED' | 'REJECTED' | 'PENDING_INFO'
  | 'COMMITTED' | 'PAID' | 'CANCELLED';

export interface Claim {
  id: string;
  claimNumber: string;
  memberId: string;
  dependantId?: string;
  providerId: string;
  schemeId: string;
  benefitId?: string;
  claimType: string;
  /** Derived server-side from the picked scheme. Never null after V053. */
  insuranceLine?: string;
  /** Operator-supplied or auto-generated 8-char batch identifier. */
  batchNumber?: string;
  status: ClaimStatus;
  serviceDate: string;
  submissionDate?: string;
  claimedAmount: string;
  approvedAmount?: string;
  paidAmount?: string;
  currencyCode: string;
  diagnosisCodes?: string;
  procedureCodes?: string;
  notes?: string;
  rejectionReason?: string;
  rejectionNotes?: string;
  verificationCode?: string;
  verifiedAt?: string;
  adjudicatedAt?: string;
  adjudicatedBy?: string;
  createdAt: string;
  updatedAt?: string;
  attachments?: ClaimAttachment[];
}

/**
 * Response envelope from POST /api/v1/claims. Bundles the created
 * claim with the operator-facing capture metadata the form needs to
 * show inline: verification code, its 5-minute window, and the batch
 * number (auto-generated if the operator didn't supply one).
 *
 * <p>{@code verificationCode} and {@code verificationExpiresAt} are
 * null when the caller supplied {@code preVerified=true} — no code
 * was generated in that path.
 */
export interface ClaimSubmissionResponse {
  claim: Claim;
  /** Null when the operator didn't opt into batching. Never server-generated. */
  batchNumber: string | null;
}

export interface ClaimLine {
  id: string;
  claimId: string;
  tariffCode: string;
  description?: string;
  quantity: number;
  unitPrice: string;
  claimedAmount: string;
  approvedAmount?: string;
  modifierCodes?: string;
  currencyCode?: string;
  /** Per-line adjudication state — PENDING / ACCEPTED / REJECTED. */
  status?: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  /** Populated only when status is REJECTED. */
  rejectionReason?: string;
  /** Snapshot of tariffCode at capture time — populated only when the
   *  adjudicator overrode the code. Null on unedited rows. */
  originalTariffCode?: string;
  originalModifierCodes?: string;
}

/**
 * One entry in a per-line adjudication payload. Sent as a list to
 * {@code POST /api/v1/claims/{id}/lines/decisions}.
 */
export interface LineDecisionPayload {
  lineId: string;
  status: 'ACCEPTED' | 'REJECTED';
  /** Defaults server-side to the line's claimed amount when omitted on
   *  ACCEPTED; ignored on REJECTED (which zeroes the approved amount). */
  approvedAmount?: string;
  rejectionReason?: string;
  /** Optional adjudicator override. When present and different from the
   *  stored value, the server snapshots the original into
   *  {@code original_tariff_code} before overwriting. */
  tariffCode?: string;
  modifierCodes?: string;
}

export interface ClaimLinePayload {
  tariffCode: string;
  description?: string;
  quantity: number;
  unitPrice: string;
  claimedAmount: string;
  modifierCodes?: string;
  currencyCode?: string;
}

/**
 * Metadata for a document attached to a claim (scanned receipts,
 * incident photos, police reports, medical letters, …). The actual
 * bytes are held separately — this record carries only what the
 * backend needs to persist alongside the claim.
 */
export interface ClaimAttachment {
  filename: string;
  contentType: string;
  sizeBytes: number;
}

export interface SubmitClaimPayload {
  memberId: string;
  dependantId?: string;
  /** Optional — LIFE and DISABILITY claims are paid to the member with
   *  no provider on file; FUNERAL is operator-choice. Server enforces
   *  the per-line rule. */
  providerId?: string;
  schemeId: string;
  benefitId?: string;
  claimType?: string;
  /** Advisory hint — backend derives the authoritative value from the scheme. */
  insuranceLine?: string;
  batchNumber?: string;
  serviceDate: string;
  claimedAmount: string;
  currencyCode: string;
  diagnosisCodes?: string;
  procedureCodes?: string;
  notes?: string;
  lines?: ClaimLinePayload[];

  // Line-specific optional fields — presence enforced server-side per line.
  vehicleRegistration?: string;
  incidentLocation?: string;
  incidentReportRef?: string;
  policeReportRef?: string;
  propertyAddress?: string;
  deathCertificateRef?: string;
  deceasedRelationship?: string;
  travelDestination?: string;
  travelStartDate?: string;
  travelEndDate?: string;
  disabilityAssessmentRef?: string;
  lifeCertificateRef?: string;

  /** Zero or more documents the operator attached — scanned receipts,
   *  incident photos, medical letters. Stored server-side as JSON. */
  attachments?: ClaimAttachment[];
}

/**
 * Row shape returned by GET /claims/page. Member + provider names are
 * pre-joined server-side so the operational list renders them inline
 * without a second lookup.
 */
export interface ClaimRow {
  id: string;
  claimNumber: string;
  memberId: string;
  memberName?: string;
  memberNumber?: string;
  dependantId?: string;
  providerId?: string;
  providerName?: string;
  schemeId?: string;
  claimType: string;
  insuranceLine?: string;
  status: ClaimStatus;
  serviceDate: string;
  submissionDate?: string;
  claimedAmount: string;
  approvedAmount?: string;
  currencyCode: string;
  batchNumber?: string;
  createdAt: string;
}

export interface PageResponse<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface ClaimPageParams {
  status?: string;
  claimType?: string;
  insuranceLine?: string;
  memberId?: string;
  providerId?: string;
  schemeId?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class ClaimsService {
  constructor(private api: ApiService) {}

  list(): Observable<Claim[]> {
    return this.api.get<Claim[]>('/claims');
  }

  /**
   * Server-side paginated claims list. Feeds /tenant/claims and its
   * status-tabbed siblings. Rows carry pre-joined member + provider
   * names so the table renders each cell without a lookup.
   */
  listPaged(opts: ClaimPageParams): Observable<PageResponse<ClaimRow>> {
    const params: Record<string, string> = {};
    if (opts.status)         params['status']         = opts.status;
    if (opts.claimType)      params['claimType']      = opts.claimType;
    if (opts.insuranceLine)  params['insuranceLine']  = opts.insuranceLine;
    if (opts.memberId)       params['memberId']       = opts.memberId;
    if (opts.providerId)     params['providerId']     = opts.providerId;
    if (opts.schemeId)       params['schemeId']       = opts.schemeId;
    if (opts.q)              params['q']              = opts.q;
    if (opts.sortKey)        params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined) params['page']       = String(opts.page);
    if (opts.size !== undefined) params['size']       = String(opts.size);
    return this.api.get<PageResponse<ClaimRow>>('/claims/page', params);
  }

  getById(id: string): Observable<Claim> {
    return this.api.get<Claim>(`/claims/${id}`);
  }

  getByStatus(status: string): Observable<Claim[]> {
    return this.api.get<Claim[]>(`/claims/status/${status}`);
  }

  getByMember(memberId: string): Observable<Claim[]> {
    return this.api.get<Claim[]>(`/claims/member/${memberId}`);
  }

  getByProvider(providerId: string): Observable<Claim[]> {
    return this.api.get<Claim[]>(`/claims/provider/${providerId}`);
  }

  getLines(claimId: string): Observable<ClaimLine[]> {
    return this.api.get<ClaimLine[]>(`/claims/${claimId}/lines`);
  }

  submit(body: SubmitClaimPayload): Observable<ClaimSubmissionResponse> {
    return this.api.post<ClaimSubmissionResponse>('/claims', body);
  }

  // verify() removed 2026-07-11 — operator-captured claims land VERIFIED
  // on submit, so no separate verification step is needed. Will return
  // when the provider portal ships and providers self-capture.

  adjudicate(id: string): Observable<Claim> {
    return this.api.post<Claim>(`/claims/${id}/adjudicate`, {});
  }

  /** Apply per-line adjudicator decisions in a single POST. Server
   *  recomputes the claim's aggregate approvedAmount + status. */
  applyLineDecisions(id: string, decisions: LineDecisionPayload[]): Observable<Claim> {
    return this.api.post<Claim>(`/claims/${id}/lines/decisions`, decisions);
  }

  updateStatus(id: string, status: ClaimStatus): Observable<Claim> {
    return this.api.post<Claim>(`/claims/${id}/status?newStatus=${status}`, {});
  }
}
