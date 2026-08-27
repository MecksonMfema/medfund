import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface TenantMorbidityBasisRow {
  id: string;
  tenantId: string;
  insuranceLine: string;
  basisName: string;
  morbidityMultiplier: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

export interface AddTenantMorbidityBasis {
  insuranceLine: string;
  basisName: string;
  morbidityMultiplier: string;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface UpdateTenantMorbidityBasis {
  basisName: string;
  morbidityMultiplier: string;
  effectiveTo?: string | null;
}

/**
 * HTTP wrapper for the tenancy-service morbidity-basis CRUD endpoints.
 * Sibling of {@link TenantPersistencyBasisService} / {@link TenantMortalityBasisService}.
 */
@Injectable({ providedIn: 'root' })
export class TenantMorbidityBasisService {
  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantMorbidityBasisRow[]> {
    return this.api.get<TenantMorbidityBasisRow[]>(`/tenants/${tenantId}/morbidity-basis`);
  }

  add(tenantId: string, body: AddTenantMorbidityBasis): Observable<TenantMorbidityBasisRow> {
    return this.api.post<TenantMorbidityBasisRow>(`/tenants/${tenantId}/morbidity-basis`, body);
  }

  update(tenantId: string, id: string, body: UpdateTenantMorbidityBasis): Observable<TenantMorbidityBasisRow> {
    return this.api.put<TenantMorbidityBasisRow>(`/tenants/${tenantId}/morbidity-basis/${id}`, body);
  }

  delete(tenantId: string, id: string): Observable<void> {
    return this.api.delete<void>(`/tenants/${tenantId}/morbidity-basis/${id}`);
  }
}
