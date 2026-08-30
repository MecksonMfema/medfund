import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type MarketDataSource = 'RBZ_AUTO' | 'SARB_AUTO';

export interface TenantMarketDataConfigRow {
  id: string;
  tenantId: string;
  currency: string;
  source: MarketDataSource;
  autoFetchEnabled: boolean;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

export interface AddTenantMarketDataConfig {
  currency: string;
  source: MarketDataSource;
  autoFetchEnabled?: boolean | null;
}

export interface UpdateTenantMarketDataConfig {
  source?: MarketDataSource | null;
  autoFetchEnabled?: boolean | null;
}

/**
 * Thin HTTP wrapper for the tenancy-service Phase 24 market-data
 * config CRUD endpoints. Rows drive the Go market-data-service's
 * per-tenant yield-curve auto-fetch schedule.
 */
@Injectable({ providedIn: 'root' })
export class TenantMarketDataConfigService {
  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantMarketDataConfigRow[]> {
    return this.api.get<TenantMarketDataConfigRow[]>(
      `/tenants/${tenantId}/ifrs17-market-data-config`,
    );
  }

  add(tenantId: string, body: AddTenantMarketDataConfig): Observable<TenantMarketDataConfigRow> {
    return this.api.post<TenantMarketDataConfigRow>(
      `/tenants/${tenantId}/ifrs17-market-data-config`,
      body,
    );
  }

  update(
    tenantId: string,
    id: string,
    body: UpdateTenantMarketDataConfig,
  ): Observable<TenantMarketDataConfigRow> {
    return this.api.put<TenantMarketDataConfigRow>(
      `/tenants/${tenantId}/ifrs17-market-data-config/${id}`,
      body,
    );
  }

  delete(tenantId: string, id: string): Observable<void> {
    return this.api.delete<void>(`/tenants/${tenantId}/ifrs17-market-data-config/${id}`);
  }
}
