import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Phase 22 REG8 client for finance-service's AML Suspicious Transaction
 * Alert workflow (`/api/v1/regulatory/aml/alerts`).
 *
 * <p>Workflow: {@code RAISED → REVIEWED → FILED} (terminal) or
 * {@code RAISED|REVIEWED → CLOSED} (terminal). Every transition writes an
 * audit event with a friendly entity name; the response projection carries
 * every actor id + email so the queue can show "raised by X, reviewed by Y"
 * without a second call.
 */

export type AmlAlertStatus = 'RAISED' | 'REVIEWED' | 'FILED' | 'CLOSED';

export type AmlTransactionType =
  | 'PREMIUM'
  | 'CLAIM_PAYOUT'
  | 'ADVANCE_PAYMENT'
  | 'REFUND'
  | 'COMMISSION'
  | 'ADJUSTMENT'
  | 'OTHER';

export interface RaiseAmlAlertRequest {
  transactionRef: string;
  transactionType: AmlTransactionType;
  amountNative: string;   // BigDecimal serialised — send as string
  currency: string;
  memberId?: string | null;
  providerId?: string | null;
  description: string;
}

export interface ReviewAmlAlertRequest {
  reviewNote: string;
}

export interface FileAmlAlertRequest {
  filedRef: string;
  filedXlsxRef?: string | null;
}

export interface CloseAmlAlertRequest {
  closedReason: string;
}

export interface AmlAlertResponse {
  id: string;
  status: AmlAlertStatus;
  transactionRef: string;
  transactionType: AmlTransactionType;
  amountNative: string;
  currency: string;
  memberId: string | null;
  providerId: string | null;
  description: string;
  raisedByActorId: string | null;
  raisedByActorEmail: string | null;
  raisedAt: string;
  reviewerActorId: string | null;
  reviewerActorEmail: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  filerActorId: string | null;
  filerActorEmail: string | null;
  filedAt: string | null;
  filedRef: string | null;
  filedXlsxRef: string | null;
  closerActorId: string | null;
  closerActorEmail: string | null;
  closedAt: string | null;
  closedReason: string | null;
}

export interface AmlAlertPage {
  content: AmlAlertResponse[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

@Injectable({ providedIn: 'root' })
export class AmlAlertService {
  private readonly base = '/regulatory/aml/alerts';

  constructor(private api: ApiService) {}

  queue(status: string | null, page: number, size: number): Observable<AmlAlertPage> {
    const params: Record<string, string> = { page: String(page), size: String(size) };
    if (status) params['status'] = status;
    return this.api.get<AmlAlertPage>(this.base, params);
  }

  get(id: string): Observable<AmlAlertResponse> {
    return this.api.get<AmlAlertResponse>(`${this.base}/${id}`);
  }

  raise(body: RaiseAmlAlertRequest): Observable<AmlAlertResponse> {
    return this.api.post<AmlAlertResponse>(this.base, body);
  }

  review(id: string, body: ReviewAmlAlertRequest): Observable<AmlAlertResponse> {
    return this.api.put<AmlAlertResponse>(`${this.base}/${id}/review`, body);
  }

  file(id: string, body: FileAmlAlertRequest): Observable<AmlAlertResponse> {
    return this.api.put<AmlAlertResponse>(`${this.base}/${id}/file`, body);
  }

  close(id: string, body: CloseAmlAlertRequest): Observable<AmlAlertResponse> {
    return this.api.post<AmlAlertResponse>(`${this.base}/${id}/close`, body);
  }
}
