import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Per-tenant high-cost claimant threshold. Backed by
 * GET/PUT /api/v1/tenants/{id}/high-cost-claimant-config on tenancy-service
 * (V132 public schema). The HIGH_COST_CLAIMANT report (claims-service,
 * Phase 4) flags any member whose cumulative PAID claims across the window
 * clear this threshold once converted to the reporting currency. Absent row
 * = the report renders empty with a "threshold not configured" warning.
 */
export interface TenantHighCostClaimantConfig {
  tenantId: string;
  /** Null when the tenant has never configured a threshold. */
  thresholdAmount: string | null;
  currencyCode: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface UpdateTenantHighCostClaimantConfigPayload {
  thresholdAmount: string;
  currencyCode: string;
}

@Injectable({ providedIn: 'root' })
export class HighCostClaimantConfigService {
  constructor(private api: ApiService) {}

  get(tenantId: string): Observable<TenantHighCostClaimantConfig> {
    return this.api.get<TenantHighCostClaimantConfig>(`/tenants/${tenantId}/high-cost-claimant-config`);
  }

  update(
    tenantId: string,
    body: UpdateTenantHighCostClaimantConfigPayload,
  ): Observable<TenantHighCostClaimantConfig> {
    return this.api.put<TenantHighCostClaimantConfig>(`/tenants/${tenantId}/high-cost-claimant-config`, body);
  }
}
