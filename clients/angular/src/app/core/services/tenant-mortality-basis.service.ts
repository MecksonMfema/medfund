import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface TenantMortalityBasisRow {
  id: string;
  tenantId: string;
  insuranceLine: string;
  basisName: string;
  mortalityMultiplier: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

export interface AddTenantMortalityBasis {
  insuranceLine: string;
  basisName: string;
  mortalityMultiplier: string;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface UpdateTenantMortalityBasis {
  basisName: string;
  mortalityMultiplier: string;
  effectiveTo?: string | null;
}

/**
 * HTTP wrapper for the tenancy-service mortality-basis CRUD endpoints.
 * Sibling of {@link TenantPersistencyBasisService} / {@link TenantMorbidityBasisService};
 * see the persistency service for the shared contract shape.
 */
@Injectable({ providedIn: 'root' })
export class TenantMortalityBasisService {
  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantMortalityBasisRow[]> {
    return this.api.get<TenantMortalityBasisRow[]>(`/tenants/${tenantId}/mortality-basis`);
  }

  add(tenantId: string, body: AddTenantMortalityBasis): Observable<TenantMortalityBasisRow> {
    return this.api.post<TenantMortalityBasisRow>(`/tenants/${tenantId}/mortality-basis`, body);
  }

  update(tenantId: string, id: string, body: UpdateTenantMortalityBasis): Observable<TenantMortalityBasisRow> {
    return this.api.put<TenantMortalityBasisRow>(`/tenants/${tenantId}/mortality-basis/${id}`, body);
  }

  delete(tenantId: string, id: string): Observable<void> {
    return this.api.delete<void>(`/tenants/${tenantId}/mortality-basis/${id}`);
  }
}
