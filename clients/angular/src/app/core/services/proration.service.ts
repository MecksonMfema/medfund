import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * The seven baked-in strategies. Names match the {@code ProrationStrategy}
 * enum in rules-engine (which drives both the tenant_proration_config CHECK
 * constraint and the {@code applyProrationStrategy} rule action).
 */
export type ProrationStrategy =
  | 'NONE'
  | 'DELTA_CREDIT'
  | 'RATIO_CARRY'
  | 'CALENDAR'
  | 'SPLIT_YEAR'
  | 'WAITING_PERIOD_ON_INCREMENT'
  | 'HYBRID_BY_DIRECTION';

/** Direction-specific overrides — the union of strategies EXCEPT the meta-strategy HYBRID_BY_DIRECTION. */
export type DirectionalStrategy = Exclude<ProrationStrategy, 'HYBRID_BY_DIRECTION'>;

export interface TenantProrationConfig {
  tenantId: string;
  defaultStrategy: ProrationStrategy;
  upgradeStrategy: DirectionalStrategy | null;
  downgradeStrategy: DirectionalStrategy | null;
  currencyStrategy: DirectionalStrategy | null;
  incrementWaitDays: number | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface UpdateTenantProrationConfigPayload {
  defaultStrategy: ProrationStrategy;
  upgradeStrategy?: DirectionalStrategy | null;
  downgradeStrategy?: DirectionalStrategy | null;
  currencyStrategy?: DirectionalStrategy | null;
  incrementWaitDays?: number | null;
}

@Injectable({ providedIn: 'root' })
export class ProrationService {
  constructor(private api: ApiService) {}

  get(tenantId: string): Observable<TenantProrationConfig> {
    return this.api.get<TenantProrationConfig>(`/tenants/${tenantId}/proration-config`);
  }

  update(
    tenantId: string,
    body: UpdateTenantProrationConfigPayload,
  ): Observable<TenantProrationConfig> {
    return this.api.put<TenantProrationConfig>(`/tenants/${tenantId}/proration-config`, body);
  }
}
