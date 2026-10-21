import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Phase 11 §B Phase 8 — four-eyes commission-adjustment surface. Backend
 * lives in finance-service under {@code /api/v1/commission/adjustments/*};
 * gateway wildcard proxy at {@code /api/v1/commission/*} forwards.
 */

export type AdjustmentStatus = 'DRAFT' | 'APPROVED' | 'COMMITTED' | 'VOIDED';
export type AdjustmentType =
  | 'EX_GRATIA'
  | 'VOID'
  | 'MANUAL_CLAWBACK'
  | 'MANUAL_REVERSAL';

export interface Adjustment {
  id: string;
  reference: string;
  targetCommissionTransactionId: string;
  adjustmentType: AdjustmentType;
  adjustmentAmount: number;
  nativeCurrency: string;
  justification: string;
  status: AdjustmentStatus;
  actorId?: string | null;
  actorEmail?: string | null;
  approverActorId?: string | null;
  approverActorEmail?: string | null;
  approvedAt?: string | null;
  committedAt?: string | null;
  committedTxnId?: string | null;
  voidedAt?: string | null;
  voidedReason?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface AdjustmentPage {
  content: Adjustment[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface CreateAdjustmentPayload {
  targetCommissionTransactionId: string;
  adjustmentType: AdjustmentType;
  adjustmentAmount: number;
  justification: string;
}

@Injectable({ providedIn: 'root' })
export class CommissionAdjustmentService {
  constructor(private api: ApiService) {}

  /**
   * Queue lookup. Omit {@code status} for the default DRAFT + APPROVED
   * feed the supervisor works through; pass {@code COMMITTED} or
   * {@code VOIDED} for the historical views.
   */
  list(status?: AdjustmentStatus, page = 0, size = 50): Observable<AdjustmentPage> {
    const params: Record<string, string> = {
      page: String(page),
      size: String(size),
    };
    if (status) params['status'] = status;
    return this.api.get<AdjustmentPage>('/commission/adjustments', params);
  }

  get(id: string): Observable<Adjustment> {
    return this.api.get<Adjustment>(`/commission/adjustments/${id}`);
  }

  create(payload: CreateAdjustmentPayload): Observable<Adjustment> {
    return this.api.post<Adjustment>('/commission/adjustments', payload);
  }

  approve(id: string): Observable<Adjustment> {
    return this.api.put<Adjustment>(`/commission/adjustments/${id}/approve`, {});
  }

  commit(id: string): Observable<Adjustment> {
    return this.api.put<Adjustment>(`/commission/adjustments/${id}/commit`, {});
  }

  void(id: string, reason: string): Observable<Adjustment> {
    return this.api.post<Adjustment>(`/commission/adjustments/${id}/void`, { reason });
  }
}
