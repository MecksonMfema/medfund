import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type InsuranceLine =
  | 'HEALTH' | 'LIFE' | 'FUNERAL' | 'GROUP'
  | 'TRAVEL' | 'DISABILITY' | 'VEHICLE' | 'PROPERTY';

export interface Ifrs17Portfolio {
  id: string;
  name: string;
  description?: string | null;
  insuranceLine?: InsuranceLine | null;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateIfrs17PortfolioPayload {
  name: string;
  description?: string | null;
  insuranceLine?: InsuranceLine | null;
}

export type UpdateIfrs17PortfolioPayload = CreateIfrs17PortfolioPayload;

@Injectable({ providedIn: 'root' })
export class Ifrs17PortfolioService {
  constructor(private api: ApiService) {}

  list(includeInactive = false): Observable<Ifrs17Portfolio[]> {
    return this.api.get<Ifrs17Portfolio[]>('/underwriting/portfolios',
      { includeInactive: String(includeInactive) });
  }

  search(q: string, limit = 10): Observable<Ifrs17Portfolio[]> {
    return this.api.get<Ifrs17Portfolio[]>('/underwriting/portfolios/search',
      { q, limit: String(limit) });
  }

  getById(id: string): Observable<Ifrs17Portfolio> {
    return this.api.get<Ifrs17Portfolio>(`/underwriting/portfolios/${id}`);
  }

  create(payload: CreateIfrs17PortfolioPayload): Observable<Ifrs17Portfolio> {
    return this.api.post<Ifrs17Portfolio>('/underwriting/portfolios', payload);
  }

  update(id: string, payload: UpdateIfrs17PortfolioPayload): Observable<Ifrs17Portfolio> {
    return this.api.put<Ifrs17Portfolio>(`/underwriting/portfolios/${id}`, payload);
  }

  delete(id: string): Observable<void> {
    return this.api.delete<void>(`/underwriting/portfolios/${id}`);
  }
}
