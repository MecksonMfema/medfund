import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface TenantRaConfigRow {
  id: string;
  tenantId: string;
  portfolioId: string;
  methodology: 'COC' | 'CI';
  cocRate: string | null;
  targetConfidenceLevel: string | null;
  sourceNote: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

export interface AddTenantRaConfig {
  portfolioId: string;
  methodology: 'COC' | 'CI';
  cocRate?: string | null;
  targetConfidenceLevel?: string | null;
  sourceNote?: string | null;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface UpdateTenantRaConfig {
  cocRate?: string | null;
  targetConfidenceLevel?: string | null;
  sourceNote?: string | null;
  effectiveTo?: string | null;
}

/**
 * Thin HTTP wrapper for the tenancy-service IFRS 17 RA config CRUD
 * endpoints shipped in Phase 15 §2. Shares the CRUD shape with
 * {@link TenantPersistencyBasisService} — one row per (portfolio_id,
 * effective_from) with methodology-specific parameters (cocRate for CoC,
 * targetConfidenceLevel for CI, never both).
 */
@Injectable({ providedIn: 'root' })
export class TenantRaConfigService {
  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantRaConfigRow[]> {
    return this.api.get<TenantRaConfigRow[]>(`/tenants/${tenantId}/ifrs17-ra-config`);
  }

  add(tenantId: string, body: AddTenantRaConfig): Observable<TenantRaConfigRow> {
    return this.api.post<TenantRaConfigRow>(`/tenants/${tenantId}/ifrs17-ra-config`, body);
  }

  update(tenantId: string, id: string, body: UpdateTenantRaConfig): Observable<TenantRaConfigRow> {
    return this.api.put<TenantRaConfigRow>(`/tenants/${tenantId}/ifrs17-ra-config/${id}`, body);
  }

  delete(tenantId: string, id: string): Observable<void> {
    return this.api.delete<void>(`/tenants/${tenantId}/ifrs17-ra-config/${id}`);
  }
}
