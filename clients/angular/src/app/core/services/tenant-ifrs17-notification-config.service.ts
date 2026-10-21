import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type Ifrs17EventType =
  | 'ONEROUS_TRANSITION'
  | 'CSM_NEGATIVE'
  | 'LOCKED_IN_CURVE_FALLBACK'
  | 'IBNR_SUB_JOB_STALE'
  | 'OPENING_BALANCE_AUTO_DERIVED'
  | 'ALL';

export type Ifrs17DeliveryMethod = 'EMAIL' | 'WEBHOOK' | 'BOTH';

export interface TenantIfrs17NotificationConfigRow {
  id: string;
  tenantId: string;
  eventType: Ifrs17EventType;
  deliveryMethod: Ifrs17DeliveryMethod;
  recipient: string;
  throttleMinutes: number;
  isActive: boolean;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

export interface AddTenantIfrs17NotificationConfig {
  eventType: Ifrs17EventType;
  deliveryMethod: Ifrs17DeliveryMethod;
  recipient: string;
  throttleMinutes?: number | null;
  isActive?: boolean | null;
}

export interface UpdateTenantIfrs17NotificationConfig {
  deliveryMethod: Ifrs17DeliveryMethod;
  throttleMinutes?: number | null;
  isActive?: boolean | null;
}

/**
 * Thin HTTP wrapper for the tenancy-service IFRS 17 notification config
 * CRUD endpoints shipped in Phase 15 §19 (I30). One row per (event_type,
 * recipient) per tenant. The Go notification-service dispatcher (§20)
 * reads active rows and fans out.
 */
@Injectable({ providedIn: 'root' })
export class TenantIfrs17NotificationConfigService {
  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantIfrs17NotificationConfigRow[]> {
    return this.api.get<TenantIfrs17NotificationConfigRow[]>(
      `/tenants/${tenantId}/ifrs17-notification-config`,
    );
  }

  add(tenantId: string, body: AddTenantIfrs17NotificationConfig): Observable<TenantIfrs17NotificationConfigRow> {
    return this.api.post<TenantIfrs17NotificationConfigRow>(
      `/tenants/${tenantId}/ifrs17-notification-config`, body,
    );
  }

  update(tenantId: string, id: string, body: UpdateTenantIfrs17NotificationConfig): Observable<TenantIfrs17NotificationConfigRow> {
    return this.api.put<TenantIfrs17NotificationConfigRow>(
      `/tenants/${tenantId}/ifrs17-notification-config/${id}`, body,
    );
  }

  delete(tenantId: string, id: string): Observable<void> {
    return this.api.delete<void>(
      `/tenants/${tenantId}/ifrs17-notification-config/${id}`,
    );
  }
}
