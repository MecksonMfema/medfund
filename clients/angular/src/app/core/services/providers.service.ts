import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { map, tap } from 'rxjs/operators';
import { ApiService } from './api.service';

export interface Provider {
  id: string;
  name: string;
  providerType: string;    // HEALTHCARE | AUTOMOTIVE | LEGAL | FUNERAL | FINANCIAL | OTHER
  specialty: string;
  registrationNumber: string; // Maps to registration_number — AHFOZ / licence / practice number depending on tenant type
  email: string;
  phone: string;
  city: string;
  address: string;
  status: string;
  createdAt: string;
  updatedAt?: string;
}

export interface ProviderPage {
  content: Provider[];
  totalCount: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface ProviderQueryParams {
  q?: string;
  status?: string;
  providerType?: string;
  page?: number;
  size?: number;
}

const CACHE_TTL_MS = 2 * 60 * 1000; // 2 minutes

@Injectable({ providedIn: 'root' })
export class ProvidersService {
  private cache = new Map<string, { data: ProviderPage; ts: number }>();

  constructor(private api: ApiService) {}

  query(params: ProviderQueryParams = {}): Observable<ProviderPage> {
    const p: Record<string, string> = {};
    if (params.q)            p['q']            = params.q;
    if (params.status)       p['status']       = params.status;
    if (params.providerType) p['providerType'] = params.providerType;
    if (params.page)         p['page']         = String(params.page);
    if (params.size)         p['size']         = String(params.size);

    const cacheKey = JSON.stringify(p);
    const cached = this.cache.get(cacheKey);
    if (cached && Date.now() - cached.ts < CACHE_TTL_MS) {
      return of(cached.data);
    }

    return this.api.get<Provider[] | ProviderPage>('/providers', Object.keys(p).length ? p : undefined).pipe(
      map(response => {
        // Backend currently returns a plain array (Flux<ProviderResponse>).
        // Normalise it into the ProviderPage shape so the component works correctly.
        if (Array.isArray(response)) {
          const all = response as Provider[];
          // Apply client-side filtering until the backend supports query params
          let filtered = all;
          if (params.q) {
            const q = params.q.toLowerCase();
            filtered = all.filter(p =>
              p.name?.toLowerCase().includes(q) ||
              p.registrationNumber?.toLowerCase().includes(q)
            );
          }
          if (params.status) {
            filtered = filtered.filter(p => p.status?.toLowerCase() === params.status!.toLowerCase());
          }
          if (params.providerType) {
            filtered = filtered.filter(p => p.providerType === params.providerType);
          }
          const size      = params.size  ?? 20;
          const page      = params.page  ?? 1;
          const offset    = (page - 1) * size;
          const content   = filtered.slice(offset, offset + size);
          return {
            content,
            totalCount: filtered.length,
            totalPages: Math.max(1, Math.ceil(filtered.length / size)),
            page,
            size,
          } as ProviderPage;
        }
        return response as ProviderPage;
      }),
      tap(data => this.cache.set(cacheKey, { data, ts: Date.now() }))
    );
  }

  getById(id: string): Observable<Provider> {
    return this.api.get<Provider>(`/providers/${id}`);
  }

  onboard(data: Partial<Provider>): Observable<Provider> {
    this.invalidate();
    return this.api.post<Provider>('/providers', data);
  }

  verify(id: string): Observable<Provider> {
    this.invalidate();
    return this.api.post<Provider>(`/providers/${id}/verify`, {});
  }

  suspend(id: string): Observable<Provider> {
    this.invalidate();
    return this.api.post<Provider>(`/providers/${id}/suspend`, {});
  }

  activate(id: string): Observable<Provider> {
    this.invalidate();
    return this.api.post<Provider>(`/providers/${id}/activate`, {});
  }

  private invalidate(): void {
    this.cache.clear();
  }
}
