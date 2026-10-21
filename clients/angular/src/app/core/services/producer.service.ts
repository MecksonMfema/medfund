import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Phase 11 producer / broker data layer. Backend lives in finance-service
 * under {@code /api/v1/producers/*} and {@code /api/v1/commission/*}; the
 * gateway proxies both those prefixes plus
 * {@code /api/v1/members/*}/producer-assignment} to finance-service.
 */

// ── Producer ────────────────────────────────────────────────────────────────
export interface Producer {
  id: string;
  producerCode: string;
  name: string;
  contactEmail?: string;
  contactPhone?: string;
  jurisdictionCode?: string;
  homeCurrency: string;
  parentProducerId?: string | null;
  whtPctOverride?: number | null;
  bankingDetailsJson?: string | null;
  active: boolean;
  activatedAt?: string | null;
  terminatedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateProducerPayload {
  producerCode: string;
  name: string;
  contactEmail?: string;
  contactPhone?: string;
  jurisdictionCode?: string;
  homeCurrency: string;
  parentProducerId?: string | null;
  whtPctOverride?: number | null;
  bankingDetailsJson?: string | null;
}

export interface UpdateProducerPayload {
  name: string;
  contactEmail?: string;
  contactPhone?: string;
  jurisdictionCode?: string;
  homeCurrency: string;
  parentProducerId?: string | null;
  whtPctOverride?: number | null;
  bankingDetailsJson?: string | null;
  active: boolean;
}

export interface ProducerPage {
  content: Producer[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

// ── Rate cards ──────────────────────────────────────────────────────────────
export type InsuranceLine =
  | 'HEALTH' | 'LIFE' | 'FUNERAL' | 'GROUP'
  | 'TRAVEL' | 'DISABILITY' | 'VEHICLE' | 'PROPERTY';

export interface RateCard {
  id: string;
  name: string;
  insuranceLine: InsuranceLine;
  producerTier?: string | null;
  baseRatePct: number;
  clawbackWindowDays?: number | null;
  effectiveFrom: string;
  effectiveTo?: string | null;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateRateCardPayload {
  name: string;
  insuranceLine: InsuranceLine;
  producerTier?: string | null;
  baseRatePct: number;
  clawbackWindowDays?: number | null;
  effectiveFrom: string;
  effectiveTo?: string | null;
}

export interface UpdateRateCardPayload extends CreateRateCardPayload {
  active: boolean;
}

export interface RateCardPage {
  content: RateCard[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

// ── Assignments ─────────────────────────────────────────────────────────────
export interface Assignment {
  id: string;
  memberId: string;
  producerId: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  changeReason?: string | null;
  createdAt?: string;
}

export interface AssignMemberPayload {
  producerId: string;
  effectiveFrom: string;
  changeReason?: string | null;
}

// ── Termination + bulk-reassign (Phase 9) ───────────────────────────────────
export interface TerminateProducerPayload {
  /** Snapped to last-day-of-month server-side. */
  effectiveDate: string;
}

export interface BulkReassignPayload {
  newProducerId: string;
  memberIds: string[];
  /** Snapped to 1st-of-month server-side. */
  effectiveFrom: string;
  changeReason?: string | null;
}

export interface BulkReassignItem {
  memberId: string;
  success: boolean;
  reason?: string | null;
}

export interface BulkReassignReport {
  total: number;
  succeeded: number;
  failed: number;
  items: BulkReassignItem[];
}

// ── Treaty backfill review (Phase 10) ──────────────────────────────────────
export interface BackfillProgress {
  startedAt: string | null;
  completedAt: string | null;
  processed: number;
  skipped: number;
  autoAccepted: number;
  pending: number;
  failed: number;
  running: boolean;
  errorMessage: string | null;
}

export interface BackfillCandidate {
  id: string;
  treatyId: string;
  treatyRef: string | null;
  treatyProducerRef: string;
  candidateProducerId: string | null;
  candidateProducerCode: string | null;
  candidateProducerName: string | null;
  confidenceScore: number;
  matchStrategy: string;
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  createdAt: string;
  resolvedAt?: string | null;
  resolvedActorEmail?: string | null;
}

@Injectable({ providedIn: 'root' })
export class ProducerService {
  constructor(private api: ApiService) {}

  // Producers
  listProducers(page = 0, size = 50, active?: boolean): Observable<ProducerPage> {
    const params: Record<string, string> = { page: String(page), size: String(size) };
    if (active !== undefined) params['active'] = String(active);
    return this.api.get<ProducerPage>('/producers', params);
  }

  getProducer(id: string): Observable<Producer> {
    return this.api.get<Producer>(`/producers/${id}`);
  }

  getProducerAncestry(id: string): Observable<Producer[]> {
    return this.api.get<Producer[]>(`/producers/${id}/ancestry`);
  }

  getProducerChildren(id: string): Observable<Producer[]> {
    return this.api.get<Producer[]>(`/producers/${id}/children`);
  }

  createProducer(payload: CreateProducerPayload): Observable<Producer> {
    return this.api.post<Producer>('/producers', payload);
  }

  updateProducer(id: string, payload: UpdateProducerPayload): Observable<Producer> {
    return this.api.put<Producer>(`/producers/${id}`, payload);
  }

  /**
   * Debounced typeahead lookup for producer pickers (see
   * {@code feedback_no_raw_id_inputs}). Backed by the /search endpoint
   * with a substring match on name / producer_code.
   */
  searchProducers(q: string, limit = 20): Observable<Producer[]> {
    return this.api.get<Producer[]>('/producers/search',
      { q, limit: String(limit) });
  }

  // Rate cards
  listRateCards(page = 0, size = 50, active?: boolean): Observable<RateCardPage> {
    const params: Record<string, string> = { page: String(page), size: String(size) };
    if (active !== undefined) params['active'] = String(active);
    return this.api.get<RateCardPage>('/commission/rate-cards', params);
  }

  getRateCard(id: string): Observable<RateCard> {
    return this.api.get<RateCard>(`/commission/rate-cards/${id}`);
  }

  createRateCard(payload: CreateRateCardPayload): Observable<RateCard> {
    return this.api.post<RateCard>('/commission/rate-cards', payload);
  }

  updateRateCard(id: string, payload: UpdateRateCardPayload): Observable<RateCard> {
    return this.api.put<RateCard>(`/commission/rate-cards/${id}`, payload);
  }

  deactivateRateCard(id: string): Observable<RateCard> {
    return this.api.delete<RateCard>(`/commission/rate-cards/${id}`);
  }

  // Member assignments
  currentAssignmentFor(memberId: string): Observable<Assignment | null> {
    return this.api.get<Assignment | null>(
      `/members/${memberId}/producer-assignment`);
  }

  assignmentHistoryFor(memberId: string): Observable<Assignment[]> {
    return this.api.get<Assignment[]>(
      `/members/${memberId}/producer-assignment/history`);
  }

  assignMember(memberId: string, payload: AssignMemberPayload): Observable<Assignment> {
    return this.api.post<Assignment>(
      `/members/${memberId}/producer-assignment`, payload);
  }

  closeCurrentAssignment(memberId: string): Observable<void> {
    return this.api.delete<void>(`/members/${memberId}/producer-assignment`);
  }

  listProducerAssignments(producerId: string, page = 0, size = 50): Observable<Assignment[]> {
    return this.api.get<Assignment[]>(
      `/producers/${producerId}/assignments`,
      { page: String(page), size: String(size) });
  }

  countOpenAssignments(producerId: string): Observable<number> {
    return this.api.get<number>(`/producers/${producerId}/assignments/count`);
  }

  // Phase 9 — termination + bulk reassign
  terminateProducer(id: string, payload: TerminateProducerPayload): Observable<Producer> {
    return this.api.post<Producer>(`/producers/${id}/terminate`, payload);
  }

  bulkReassign(sourceProducerId: string, payload: BulkReassignPayload): Observable<BulkReassignReport> {
    return this.api.post<BulkReassignReport>(
      `/producers/${sourceProducerId}/reassign-bulk`, payload);
  }

  // Phase 10 — treaty.producer_ref → producer_id fuzzy backfill
  runProducerBackfill(): Observable<void> {
    return this.api.post<void>('/producers/backfill/run', {});
  }

  getBackfillProgress(): Observable<BackfillProgress> {
    return this.api.get<BackfillProgress>('/producers/backfill/progress');
  }

  listBackfillCandidates(page = 0, size = 50): Observable<BackfillCandidate[]> {
    return this.api.get<BackfillCandidate[]>('/producers/backfill/candidates',
      { page: String(page), size: String(size) });
  }

  countPendingBackfillCandidates(): Observable<number> {
    return this.api.get<number>('/producers/backfill/candidates/pending-count');
  }

  acceptBackfillCandidate(id: string): Observable<void> {
    return this.api.put<void>(`/producers/backfill/candidates/${id}/accept`, {});
  }

  rejectBackfillCandidate(id: string): Observable<void> {
    return this.api.put<void>(`/producers/backfill/candidates/${id}/reject`, {});
  }
}
