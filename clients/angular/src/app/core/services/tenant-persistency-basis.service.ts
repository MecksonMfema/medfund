import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface TenantPersistencyBasisRow {
  id: string;
  tenantId: string;
  insuranceLine: string;
  cohortMonths: number;
  expectedRetentionPct: string;
  sourceNote: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

export interface AddTenantPersistencyBasis {
  insuranceLine: string;
  cohortMonths: number;
  expectedRetentionPct: string;
  sourceNote?: string | null;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface UpdateTenantPersistencyBasis {
  expectedRetentionPct: string;
  sourceNote?: string | null;
  effectiveTo?: string | null;
}

/**
 * Thin HTTP wrapper for the tenancy-service persistency-basis CRUD endpoints
 * shipped in Phase 2. The three admin tabs in {@code ActuarialBasesComponent}
 * share the same list/add/update/delete shape as {@link TenantMortalityBasisService}
 * and {@link TenantMorbidityBasisService} — differences are just the payload
 * shape (cohort_months + expected_retention_pct here vs basis_name + multiplier
 * on the mortality/morbidity siblings).
 */
@Injectable({ providedIn: 'root' })
export class TenantPersistencyBasisService {
  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantPersistencyBasisRow[]> {
    return this.api.get<TenantPersistencyBasisRow[]>(`/tenants/${tenantId}/persistency-basis`);
  }

  add(tenantId: string, body: AddTenantPersistencyBasis): Observable<TenantPersistencyBasisRow> {
    return this.api.post<TenantPersistencyBasisRow>(`/tenants/${tenantId}/persistency-basis`, body);
  }

  update(tenantId: string, id: string, body: UpdateTenantPersistencyBasis): Observable<TenantPersistencyBasisRow> {
    return this.api.put<TenantPersistencyBasisRow>(`/tenants/${tenantId}/persistency-basis/${id}`, body);
  }

  delete(tenantId: string, id: string): Observable<void> {
    return this.api.delete<void>(`/tenants/${tenantId}/persistency-basis/${id}`);
  }
}
