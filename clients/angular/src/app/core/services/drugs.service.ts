import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type DrugType = 'ACUTE' | 'CHRONIC' | 'OFF_LIMIT';
export type DrugUnit = 'unit' | 'ml' | 'g' | 'mg' | 'tablet' | 'capsule';

export interface Drug {
  id: string;
  drugName: string;
  drugType: DrugType;
  unitOfMeasurement: DrugUnit;
  tariffCode?: string;
  wholesaleCostZwl?: string;
  wholesaleCostUsd?: string;
  paymentPercentage: string;
  doNotPay: boolean;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface UpsertDrugPayload {
  drugName: string;
  drugType?: DrugType;
  unitOfMeasurement?: DrugUnit;
  tariffCode?: string;
  wholesaleCostZwl?: string;
  wholesaleCostUsd?: string;
  paymentPercentage?: string;
  doNotPay?: boolean;
  isActive?: boolean;
}

/**
 * Envelope for GET /drugs/page. Mirrors the shared PageResponse<T>
 * shape used everywhere else in the platform.
 */
export interface DrugPageResponse {
  content: Drug[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface DrugPageParams {
  activeOnly?: boolean;
  drugType?: DrugType;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class DrugsService {
  constructor(private api: ApiService) {}

  list(activeOnly = false): Observable<Drug[]> {
    const params: Record<string, string> = {};
    if (activeOnly) params['activeOnly'] = 'true';
    return this.api.get<Drug[]>('/drugs', params);
  }

  /**
   * Server-side paginated drugs list. Feeds /tenant/claims/drugs.
   * Preferred over {@link list} for the operational surface — even
   * though the formulary is small, the uniform pattern makes the
   * page consistent with every other list on the platform.
   */
  listPaged(opts: DrugPageParams): Observable<DrugPageResponse> {
    const params: Record<string, string> = {};
    if (opts.activeOnly !== undefined) params['activeOnly'] = String(opts.activeOnly);
    if (opts.drugType)                 params['drugType']   = opts.drugType;
    if (opts.q)                        params['q']          = opts.q;
    if (opts.sortKey)                  params['sortKey']    = opts.sortKey;
    if (opts.sortDirection)            params['sortDirection'] = opts.sortDirection;
    if (opts.page !== undefined)       params['page']       = String(opts.page);
    if (opts.size !== undefined)       params['size']       = String(opts.size);
    return this.api.get<DrugPageResponse>('/drugs/page', params);
  }

  findById(id: string): Observable<Drug> {
    return this.api.get<Drug>(`/drugs/${id}`);
  }

  search(q: string): Observable<Drug[]> {
    return this.api.get<Drug[]>('/drugs/search', { q });
  }

  create(body: UpsertDrugPayload): Observable<Drug> {
    return this.api.post<Drug>('/drugs', body);
  }

  update(id: string, body: UpsertDrugPayload): Observable<Drug> {
    return this.api.put<Drug>(`/drugs/${id}`, body);
  }

  delete(id: string): Observable<void> {
    return this.api.delete<void>(`/drugs/${id}`);
  }
}
