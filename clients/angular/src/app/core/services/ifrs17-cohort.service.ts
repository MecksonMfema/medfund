import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type CohortType = 'ONEROUS' | 'NON_ONEROUS' | 'UNCERTAIN';

export interface Ifrs17Cohort {
  id: string;
  portfolioId: string;
  cohortYear: number;
  cohortType: CohortType;
  name: string;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateIfrs17CohortPayload {
  portfolioId: string;
  cohortYear: number;
  cohortType: CohortType;
  name: string;
}

export type UpdateIfrs17CohortPayload = CreateIfrs17CohortPayload;

@Injectable({ providedIn: 'root' })
export class Ifrs17CohortService {
  constructor(private api: ApiService) {}

  list(includeInactive = false, portfolioId?: string): Observable<Ifrs17Cohort[]> {
    const params: Record<string, string> = { includeInactive: String(includeInactive) };
    if (portfolioId) params['portfolioId'] = portfolioId;
    return this.api.get<Ifrs17Cohort[]>('/underwriting/cohorts', params);
  }

  getById(id: string): Observable<Ifrs17Cohort> {
    return this.api.get<Ifrs17Cohort>(`/underwriting/cohorts/${id}`);
  }

  create(payload: CreateIfrs17CohortPayload): Observable<Ifrs17Cohort> {
    return this.api.post<Ifrs17Cohort>('/underwriting/cohorts', payload);
  }

  update(id: string, payload: UpdateIfrs17CohortPayload): Observable<Ifrs17Cohort> {
    return this.api.put<Ifrs17Cohort>(`/underwriting/cohorts/${id}`, payload);
  }

  delete(id: string): Observable<void> {
    return this.api.delete<void>(`/underwriting/cohorts/${id}`);
  }
}
