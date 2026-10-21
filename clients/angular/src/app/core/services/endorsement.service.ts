import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Phase 12 §C endorsement lifecycle wire layer. Backed by
 * {@code /api/v1/endorsements} on user-service; the gateway proxies
 * the whole {@code /api/v1/endorsements} prefix. Four-eyes state
 * machine mirrors Phase 11's {@code CommissionAdjustment}:
 * {@code DRAFT → APPROVED → COMMITTED} (terminal), with
 * {@code DRAFT|APPROVED → VOIDED} escape hatches; the async
 * earning-schedule recompute flips {@code COMMITTED → COMPUTED}
 * via the {@code /computed} endpoint.
 */

export type EndorsementStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'COMMITTED'
  | 'COMPUTED'
  | 'VOIDED';

export type EndorsementChangeType =
  | 'PREMIUM_ADJUSTMENT'
  | 'COVERAGE_EXTENSION'
  | 'BENEFIT_CHANGE'
  | 'BENEFICIARY_CHANGE'
  | 'ADMIN_CHANGE'
  | 'PRODUCT_SWITCH'
  | 'RENEWAL_ADVANCE';

/**
 * Six policy sources the endorsement API accepts. Excludes HEALTH
 * (Contribution-based) since HEALTH earns entirely within its
 * originating period and does not have annual-bind semantics.
 */
export type EndorsementPolicySource =
  | 'LIFE_POLICY'
  | 'FUNERAL_POLICY'
  | 'DISABILITY_POLICY'
  | 'TRAVEL_POLICY'
  | 'VEHICLE_POLICY'
  | 'PROPERTY_POLICY';

export interface EndorsementResponse {
  id: string;
  reference: string;
  policyId: string;
  policySource: EndorsementPolicySource | string;
  insuranceLine: string;
  changeType: EndorsementChangeType | string;
  effectiveFrom: string;
  premiumDelta: string | null;
  currencyCode: string | null;
  reason: string;
  status: EndorsementStatus;
  draftActorId: string;
  draftActorEmail: string;
  draftAt: string;
  approveActorId: string | null;
  approveActorEmail: string | null;
  approveAt: string | null;
  commitActorId: string | null;
  commitActorEmail: string | null;
  commitAt: string | null;
  voidedReason: string | null;
  voidedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateEndorsementPayload {
  policyId: string;
  policySource: EndorsementPolicySource | string;
  insuranceLine: string;
  changeType: EndorsementChangeType | string;
  effectiveFrom: string;
  premiumDelta?: string | number | null;
  currencyCode?: string | null;
  reason: string;
}

export interface VoidEndorsementPayload {
  reason: string;
}

export interface EndorsementQuery {
  status?: EndorsementStatus;
  page?: number;
  size?: number;
}

/** Fiber pagination envelope shape returned by the queue endpoint. */
export interface EndorsementPage {
  content: EndorsementResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

function queryParams(q: EndorsementQuery): Record<string, string> {
  const p: Record<string, string> = {};
  if (q.status) p['status'] = q.status;
  if (q.page !== undefined) p['page'] = String(q.page);
  if (q.size !== undefined) p['size'] = String(q.size);
  return p;
}

@Injectable({ providedIn: 'root' })
export class EndorsementService {
  constructor(private api: ApiService) {}

  list(query: EndorsementQuery = {}): Observable<EndorsementPage> {
    return this.api.get<EndorsementPage>('/endorsements', queryParams(query));
  }

  get(id: string): Observable<EndorsementResponse> {
    return this.api.get<EndorsementResponse>(`/endorsements/${id}`);
  }

  listByPolicy(policyId: string, policySource: string): Observable<EndorsementResponse[]> {
    return this.api.get<EndorsementResponse[]>('/endorsements/by-policy', {
      policyId,
      policySource,
    });
  }

  create(body: CreateEndorsementPayload): Observable<EndorsementResponse> {
    return this.api.post<EndorsementResponse>('/endorsements', body);
  }

  approve(id: string): Observable<EndorsementResponse> {
    return this.api.put<EndorsementResponse>(`/endorsements/${id}/approve`, {});
  }

  commit(id: string): Observable<EndorsementResponse> {
    return this.api.put<EndorsementResponse>(`/endorsements/${id}/commit`, {});
  }

  void(id: string, body: VoidEndorsementPayload): Observable<EndorsementResponse> {
    return this.api.post<EndorsementResponse>(`/endorsements/${id}/void`, body);
  }
}
