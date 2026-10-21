import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Producer payout data layer — Phase 11 §A Phase 6.
 *
 * <p>Backed by the shared {@code /api/v1/payment-runs} surface widened for
 * a PRODUCER {@code payeeType}. This service is a thin wrapper that types
 * the producer variants of the request + response shapes so callers don't
 * pass provider payload fields to a producer create.
 */

export interface ProducerPayoutRun {
  id: string;
  runNumber: string;
  status: string;
  totalAmount: number | null;
  currencyCode: string;
  payeeType: string;
  paymentCount: number | null;
  description?: string | null;
  executedAt?: string | null;
  sourceBankAccountId: string;
  sourceBankAccountLabel?: string | null;
  periodStart?: string | null;
  periodEnd?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProducerPayoutRunItem {
  id: string;
  paymentRunId: string;
  paymentId: string | null;
  providerId: string | null;
  memberId: string | null;
  producerId: string | null;
  payeeType: string;
  amount: number | null;
  currencyCode: string;
  withholdingTaxPct: number | null;
  status: string;
  createdAt?: string;
}

export interface CreateProducerPayoutRequest {
  currencyCode: string;
  description?: string;
  sourceBankAccountId: string;
  periodStart: string;   // ISO date, will snap 1st-of-month server-side
  periodEnd: string;     // ISO date, will snap last-day-of-month server-side
}

@Injectable({ providedIn: 'root' })
export class ProducerPayoutService {

  private api = inject(ApiService);

  list(status?: string, currencyCode?: string): Observable<ProducerPayoutRun[]> {
    const params: Record<string, string> = { payeeType: 'PRODUCER' };
    if (status) params['status'] = status;
    if (currencyCode) params['currencyCode'] = currencyCode;
    return this.api.get<ProducerPayoutRun[]>('/payment-runs', params);
  }

  get(id: string): Observable<ProducerPayoutRun> {
    return this.api.get<ProducerPayoutRun>(`/payment-runs/${id}`);
  }

  items(id: string): Observable<ProducerPayoutRunItem[]> {
    return this.api.get<ProducerPayoutRunItem[]>(`/payment-runs/${id}/items`);
  }

  create(req: CreateProducerPayoutRequest): Observable<ProducerPayoutRun> {
    return this.api.post<ProducerPayoutRun>('/payment-runs', {
      currencyCode: req.currencyCode,
      description: req.description,
      payeeType: 'PRODUCER',
      sourceBankAccountId: req.sourceBankAccountId,
      periodStart: req.periodStart,
      periodEnd: req.periodEnd,
    });
  }

  approve(id: string): Observable<ProducerPayoutRun> {
    return this.api.post<ProducerPayoutRun>(`/payment-runs/${id}/approve`, {});
  }

  execute(id: string): Observable<ProducerPayoutRun> {
    return this.api.post<ProducerPayoutRun>(`/payment-runs/${id}/execute`, {});
  }
}
