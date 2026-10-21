import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type BalanceType = 'LRC' | 'LIC';

export interface Ifrs17OpeningBalanceSeedRow {
  id: string;
  portfolioId: string;
  cohortId: string;
  currency: string;
  balanceType: BalanceType;
  amount: string;
  effectiveFrom: string;
  reasonNote: string;
  actorId: string | null;
  actorEmail: string | null;
  createdAt: string | null;
}

export interface CreateIfrs17OpeningBalanceSeed {
  portfolioId: string;
  cohortId: string;
  currency: string;
  balanceType: BalanceType;
  amount: string;
  effectiveFrom: string;
  reasonNote: string;
}

export interface UpdateIfrs17OpeningBalanceSeed {
  amount: string;
  reasonNote: string;
}

/**
 * Thin HTTP wrapper for the user-service IFRS 17 opening balance seed
 * CRUD shipped in Phase 15 §7 (I29). Tenant-admin overrides on
 * auto-derived opening balances feed into §17 shaping as the first-consult
 * lookup; the compute path falls back to the auto-derived value when no
 * seed row exists for the (portfolio, cohort, currency, balance_type)
 * tuple as of the report period start.
 */
@Injectable({ providedIn: 'root' })
export class Ifrs17OpeningBalanceSeedService {
  constructor(private api: ApiService) {}

  list(): Observable<Ifrs17OpeningBalanceSeedRow[]> {
    return this.api.get<Ifrs17OpeningBalanceSeedRow[]>('/underwriting/opening-balances');
  }

  add(body: CreateIfrs17OpeningBalanceSeed): Observable<Ifrs17OpeningBalanceSeedRow> {
    return this.api.post<Ifrs17OpeningBalanceSeedRow>('/underwriting/opening-balances', body);
  }

  update(id: string, body: UpdateIfrs17OpeningBalanceSeed): Observable<Ifrs17OpeningBalanceSeedRow> {
    return this.api.put<Ifrs17OpeningBalanceSeedRow>(`/underwriting/opening-balances/${id}`, body);
  }

  delete(id: string): Observable<void> {
    return this.api.delete<void>(`/underwriting/opening-balances/${id}`);
  }
}
