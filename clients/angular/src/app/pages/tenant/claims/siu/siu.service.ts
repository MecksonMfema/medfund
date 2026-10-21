import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../../../core/services/api.service';

/**
 * Data layer for the SIU workflow surface (Phase 19 §A Phase 6).
 * Backend at {@code /api/v1/siu/cases} (claims-service via gateway).
 * §B Phase 7 adds manual case-open + assignment methods; §B Phase 8 adds
 * four-eyes approve / reject.
 */

/** Case-summary row surfaced on the queue page. */
export interface SiuCaseSummary {
  id: string;
  caseNumber: string;
  status: string;
  priority: string | null;
  assignedTo: string | null;
  openedAt: string;
  flagCount: number;
}

/** Persisted fraud_flag row linked to a case (rendered in the Flags tab). */
export interface FraudFlag {
  id: string;
  claimId: string;
  siuCaseId: string | null;
  flagSource: 'AI_MODEL' | 'MANUAL_OFFICER';
  modelVersion: string | null;
  riskScore: string | null;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  indicatorsJson: string;
  flaggedAt: string;
}

/** Append-only note row rendered in the Notes tab / activity timeline. */
export interface SiuCaseNote {
  id: string;
  caseId: string;
  authorId: string;
  authorEmail: string;
  noteType: string;
  body: string;
  createdAt: string;
}

/** SIU evidence row — file-service handle plus metadata (§B Phase 10). */
export interface SiuEvidence {
  id: string;
  caseId: string;
  fileServiceRef: string;
  description: string;
  evidenceType: 'DOCUMENT' | 'PHOTO' | 'PROVIDER_RECORD' | 'MEMBER_RECORD' | 'OTHER';
  uploadedBy: string;
  uploadedByEmail: string;
  uploadedAt: string;
}

/** External referral row — law enforcement / regulator / HR (§B Phase 10). */
export interface SiuReferral {
  id: string;
  caseId: string;
  referralTo: 'LAW_ENFORCEMENT' | 'REGULATOR' | 'INTERNAL_HR';
  referralReference: string | null;
  referredBy: string;
  referredByEmail: string;
  referredAt: string;
  responseReceivedAt: string | null;
  responseNotes: string | null;
}

/** Full case-detail response. §B Phase 10 inlines evidence + referrals. */
export interface SiuCaseDetail {
  id: string;
  caseNumber: string;
  status: string;
  priority: string | null;
  tags: string[] | null;
  assignedTo: string | null;
  openedBy: string;
  openedByEmail: string;
  openedAt: string;
  closedBy: string | null;
  closedByEmail: string | null;
  closedAt: string | null;
  closureReason: string | null;
  outcome: string | null;
  savedAmount: string | null;
  savedCurrency: string | null;
  // §B Phase 8 four-eyes staging (populated while status = PENDING_APPROVAL)
  proposedBy: string | null;
  proposedByEmail: string | null;
  proposedAt: string | null;
  proposedOutcome: string | null;
  proposedSavedAmount: string | null;
  proposedSavedCurrency: string | null;
  proposedClosureReason: string | null;
  createdAt: string;
  updatedAt: string;
  flags: FraudFlag[];
  notes: SiuCaseNote[];
  evidence: SiuEvidence[];
  referrals: SiuReferral[];
}

export interface AddEvidenceRequest {
  fileServiceRef: string;
  description: string;
  evidenceType: SiuEvidence['evidenceType'];
}

export interface AddReferralRequest {
  referralTo: SiuReferral['referralTo'];
  referralReference: string | null;
}

export interface CloseCaseRequest {
  savedAmount: string | null;
  savedCurrency: string | null;
  closureReason: string;
}

export interface ProposeClosureRequest {
  outcome: 'CONFIRMED_FRAUD' | 'REFERRED_LAW_ENFORCEMENT' | 'ACTION_TAKEN';
  savedAmount: string;
  savedCurrency: string;
  closureReason: string;
}

@Injectable({ providedIn: 'root' })
export class SiuService {
  constructor(private api: ApiService) {}

  list(status?: string, assignedTo?: string): Observable<SiuCaseSummary[]> {
    const params: Record<string, string> = {};
    if (status) params['status'] = status;
    if (assignedTo) params['assignedTo'] = assignedTo;
    return this.api.get<SiuCaseSummary[]>('/siu/cases', params);
  }

  get(caseId: string): Observable<SiuCaseDetail> {
    return this.api.get<SiuCaseDetail>(`/siu/cases/${caseId}`);
  }

  startReview(caseId: string): Observable<SiuCaseDetail> {
    return this.api.post<SiuCaseDetail>(`/siu/cases/${caseId}/start-review`, {});
  }

  // §B Phase 8 — four-eyes closure lane. Investigator proposes; a
  // different actor with claims:siu:approve must call approveClosure.
  proposeClosure(caseId: string, req: ProposeClosureRequest): Observable<SiuCaseDetail> {
    return this.api.post<SiuCaseDetail>(`/siu/cases/${caseId}/propose-closure`, req);
  }

  approveClosure(caseId: string): Observable<SiuCaseDetail> {
    return this.api.post<SiuCaseDetail>(`/siu/cases/${caseId}/approve-closure`, {});
  }

  rejectClosure(caseId: string, rejectionNote: string): Observable<SiuCaseDetail> {
    return this.api.post<SiuCaseDetail>(`/siu/cases/${caseId}/reject-closure`,
      { rejectionNote });
  }

  reopen(caseId: string, reopenReason: string): Observable<SiuCaseDetail> {
    return this.api.post<SiuCaseDetail>(`/siu/cases/${caseId}/reopen`, { reopenReason });
  }

  assign(caseId: string, assigneeId: string): Observable<SiuCaseDetail> {
    return this.api.post<SiuCaseDetail>(`/siu/cases/${caseId}/assign`, { assigneeId });
  }

  startReviewFromAssigned(caseId: string): Observable<SiuCaseDetail> {
    return this.api.post<SiuCaseDetail>(`/siu/cases/${caseId}/start-review-from-assigned`, {});
  }

  closeDismissed(caseId: string, req: CloseCaseRequest): Observable<SiuCaseDetail> {
    return this.api.post<SiuCaseDetail>(`/siu/cases/${caseId}/close-dismissed`,
      { savedAmount: null, savedCurrency: null, closureReason: req.closureReason });
  }

  // §B Phase 10 — evidence + referral mutation endpoints. Both round-trip
  // the whole case-detail response so the caller can splice the new row
  // in without a second GET.
  addEvidence(caseId: string, req: AddEvidenceRequest): Observable<SiuEvidence> {
    return this.api.post<SiuEvidence>(`/siu/cases/${caseId}/evidence`, req);
  }

  addReferral(caseId: string, req: AddReferralRequest): Observable<SiuReferral> {
    return this.api.post<SiuReferral>(`/siu/cases/${caseId}/referrals`, req);
  }
}
