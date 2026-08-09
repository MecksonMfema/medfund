import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Per-tenant Claims-to-Contributions auto-draft configuration.
 * Backed by GET/PUT /api/v1/tenants/{id}/ctc-auto-config on tenancy-service
 * (V129 public schema). When enabled, finance-service auto-drafts a CTC
 * row whenever a MEMBER-payee claim adjudicates and the member's
 * outstanding contribution balance clears the threshold. Never
 * auto-commits.
 */
export interface TenantCtcAutoConfig {
  tenantId: string;
  enabled: boolean;
  minMemberBalanceThreshold: string;
  maxPerCtcAmount: string | null;
  thresholdCurrency: string;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface UpdateTenantCtcAutoConfigPayload {
  enabled: boolean;
  minMemberBalanceThreshold: string;
  maxPerCtcAmount?: string | null;
  thresholdCurrency: string;
}

@Injectable({ providedIn: 'root' })
export class CtcAutoConfigService {
  constructor(private api: ApiService) {}

  get(tenantId: string): Observable<TenantCtcAutoConfig> {
    return this.api.get<TenantCtcAutoConfig>(`/tenants/${tenantId}/ctc-auto-config`);
  }

  update(
    tenantId: string,
    body: UpdateTenantCtcAutoConfigPayload,
  ): Observable<TenantCtcAutoConfig> {
    return this.api.put<TenantCtcAutoConfig>(`/tenants/${tenantId}/ctc-auto-config`, body);
  }
}
