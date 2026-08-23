import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Per-tenant auto-lapse configuration (V133). Backed by
 * GET/PUT /api/v1/tenants/{id}/auto-lapse-config on tenancy-service.
 *
 * <p>Wires the arrears pipeline that flips members to LAPSED after a
 * tenant-configured window of unbroken arrears. When {@code enabled}
 * is false, or the row is absent, the arrears sweep publishes nothing
 * and the user-service consumers short-circuit. When enabled,
 * {@code arrearsThresholdMonths} + {@code graceWindowDays} drive the
 * breach → grace → transition timing.
 */
export interface TenantAutoLapseConfig {
  tenantId: string;
  enabled: boolean;
  arrearsThresholdMonths: number | null;
  graceWindowDays: number | null;
  updatedAt: string | null;
  updatedBy: string | null;
  updatedByEmail: string | null;
}

export interface UpdateTenantAutoLapseConfigPayload {
  enabled: boolean;
  arrearsThresholdMonths: number | null;
  graceWindowDays: number | null;
}

@Injectable({ providedIn: 'root' })
export class TenantAutoLapseConfigService {
  constructor(private api: ApiService) {}

  get(tenantId: string): Observable<TenantAutoLapseConfig> {
    return this.api.get<TenantAutoLapseConfig>(`/tenants/${tenantId}/auto-lapse-config`);
  }

  update(
    tenantId: string,
    body: UpdateTenantAutoLapseConfigPayload,
  ): Observable<TenantAutoLapseConfig> {
    return this.api.put<TenantAutoLapseConfig>(`/tenants/${tenantId}/auto-lapse-config`, body);
  }
}
