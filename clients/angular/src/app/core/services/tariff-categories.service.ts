import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * V063 tenant catalogue of tariff categories. Required on every tariff
 * (single) and every scheme_benefit (many). {@code isCapOnly=true} means
 * tariffs in this category deduct from the scheme's annual cap without
 * touching any per-benefit ledger row.
 */
export interface TariffCategory {
  id: string;
  code: string;
  label: string;
  description?: string;
  isCapOnly: boolean;
  isActive: boolean;
  sortOrder: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface UpsertTariffCategoryPayload {
  code: string;
  label: string;
  description?: string;
  isCapOnly?: boolean;
  isActive?: boolean;
  sortOrder?: number;
}

/** Envelope for GET /tariff-categories/page. */
export interface TariffCategoryPageResponse {
  content: TariffCategory[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface TariffCategoryPageParams {
  activeOnly?: boolean;
  capOnly?: boolean;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class TariffCategoriesService {
  constructor(private api: ApiService) {}

  list(activeOnly = false): Observable<TariffCategory[]> {
    return this.api.get<TariffCategory[]>('/tariff-categories', { activeOnly: String(activeOnly) });
  }

  /** Server-side paginated tariff-categories list. */
  listPaged(opts: TariffCategoryPageParams): Observable<TariffCategoryPageResponse> {
    const params: Record<string, string> = {};
    if (opts.activeOnly !== undefined) params['activeOnly']    = String(opts.activeOnly);
    if (opts.capOnly !== undefined)    params['capOnly']       = String(opts.capOnly);
    if (opts.q)                        params['q']             = opts.q;
    if (opts.sortKey)                  params['sortKey']       = opts.sortKey;
    if (opts.sortDirection)            params['sortDirection'] = opts.sortDirection;
    if (opts.page !== undefined)       params['page']          = String(opts.page);
    if (opts.size !== undefined)       params['size']          = String(opts.size);
    return this.api.get<TariffCategoryPageResponse>('/tariff-categories/page', params);
  }

  get(id: string): Observable<TariffCategory> {
    return this.api.get<TariffCategory>(`/tariff-categories/${id}`);
  }

  create(payload: UpsertTariffCategoryPayload): Observable<TariffCategory> {
    return this.api.post<TariffCategory>('/tariff-categories', payload);
  }

  update(id: string, payload: UpsertTariffCategoryPayload): Observable<TariffCategory> {
    return this.api.put<TariffCategory>(`/tariff-categories/${id}`, payload);
  }

  deactivate(id: string): Observable<void> {
    return this.api.delete<void>(`/tariff-categories/${id}`);
  }
}
