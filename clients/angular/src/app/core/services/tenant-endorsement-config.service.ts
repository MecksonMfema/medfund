import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Per-tenant endorsement four-eyes gate (V134). Backed by
 * {@code GET/PUT /api/v1/tenants/{id}/endorsement-config} on
 * tenancy-service. When {@code enabled} is false, or the row is
 * absent, user-service auto-commits every endorsement regardless
 * of {@code premiumDelta}. When enabled, an endorsement whose
 * absolute {@code premiumDelta} equals or exceeds
 * {@code fourEyesThresholdAmount} (in {@code thresholdCurrency})
 * enters DRAFT and requires a second actor to approve + commit.
 */
export interface TenantEndorsementConfig {
  tenantId: string;
  enabled: boolean;
  fourEyesThresholdAmount: string | null;
  thresholdCurrency: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  updatedByEmail: string | null;
}

export interface UpdateTenantEndorsementConfigPayload {
  enabled: boolean;
  fourEyesThresholdAmount: string | number | null;
  thresholdCurrency: string | null;
}

@Injectable({ providedIn: 'root' })
export class TenantEndorsementConfigService {
  constructor(private api: ApiService) {}

  get(tenantId: string): Observable<TenantEndorsementConfig> {
    return this.api.get<TenantEndorsementConfig>(
      `/tenants/${tenantId}/endorsement-config`,
    );
  }

  update(
    tenantId: string,
    body: UpdateTenantEndorsementConfigPayload,
  ): Observable<TenantEndorsementConfig> {
    return this.api.put<TenantEndorsementConfig>(
      `/tenants/${tenantId}/endorsement-config`,
      body,
    );
  }
}
