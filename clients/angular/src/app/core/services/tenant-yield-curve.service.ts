import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type YieldCurveSource = 'ADMIN' | 'RBZ_AUTO' | 'SARB_AUTO' | 'BACKFILL_FALLBACK';

export interface TenantYieldCurveRow {
  id: string;
  tenantId: string;
  currency: string;
  tenorMonths: number;
  spotRate: string;
  source: YieldCurveSource;
  effectiveFrom: string;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

export interface AddTenantYieldCurve {
  currency: string;
  tenorMonths: number;
  spotRate: string;
  source?: YieldCurveSource | null;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface UpdateTenantYieldCurve {
  spotRate: string;
  effectiveTo?: string | null;
}

/**
 * Thin HTTP wrapper for the tenancy-service IFRS 17 yield curve CRUD
 * endpoints shipped in Phase 15 §2. Includes a bulk-add helper used by
 * the CSV upload button on the yield-curves tab; each row lands in its
 * own audit envelope so per-row errors don't abort accepted rows.
 */
@Injectable({ providedIn: 'root' })
export class TenantYieldCurveService {
  constructor(private api: ApiService) {}

  list(tenantId: string, currency?: string): Observable<TenantYieldCurveRow[]> {
    const path = `/tenants/${tenantId}/ifrs17-yield-curves`;
    return currency
      ? this.api.get<TenantYieldCurveRow[]>(path, { currency })
      : this.api.get<TenantYieldCurveRow[]>(path);
  }

  add(tenantId: string, body: AddTenantYieldCurve): Observable<TenantYieldCurveRow> {
    return this.api.post<TenantYieldCurveRow>(`/tenants/${tenantId}/ifrs17-yield-curves`, body);
  }

  bulkAdd(tenantId: string, rows: AddTenantYieldCurve[]): Observable<TenantYieldCurveRow[]> {
    return this.api.post<TenantYieldCurveRow[]>(
      `/tenants/${tenantId}/ifrs17-yield-curves/csv-upload`,
      rows,
    );
  }

  update(tenantId: string, id: string, body: UpdateTenantYieldCurve): Observable<TenantYieldCurveRow> {
    return this.api.put<TenantYieldCurveRow>(`/tenants/${tenantId}/ifrs17-yield-curves/${id}`, body);
  }

  delete(tenantId: string, id: string): Observable<void> {
    return this.api.delete<void>(`/tenants/${tenantId}/ifrs17-yield-curves/${id}`);
  }
}
