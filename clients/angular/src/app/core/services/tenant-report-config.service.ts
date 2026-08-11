import { Injectable } from '@angular/core';
import { Observable, of, shareReplay } from 'rxjs';
import { tap } from 'rxjs/operators';
import { ApiService } from './api.service';

/**
 * One entry per catalogue report. Rows the tenant admin has never touched
 * come back with id=null and enabled=true so the UI can toggle without a
 * distinction between "no row yet" and "explicitly enabled".
 */
export interface TenantReportConfigRow {
  id: string | null;
  tenantId: string;
  reportKey: string;
  label: string;
  family: string | null;
  familyLabel: string | null;
  enabled: boolean;
  cadenced: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface ToggleEntry {
  reportKey: string;
  enabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class TenantReportConfigService {
  /** Per-tenant snapshot — shared to feed both the settings tab and the
   *  reports hub without re-fetching. Invalidated on any write. */
  private cache = new Map<string, Observable<TenantReportConfigRow[]>>();

  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantReportConfigRow[]> {
    const cached = this.cache.get(tenantId);
    if (cached) return cached;
    const fresh$ = this.api.get<TenantReportConfigRow[]>(
      `/tenants/${tenantId}/report-config`,
    ).pipe(shareReplay(1));
    this.cache.set(tenantId, fresh$);
    return fresh$;
  }

  bulkUpsert(tenantId: string, entries: ToggleEntry[]): Observable<TenantReportConfigRow[]> {
    return this.api
      .put<TenantReportConfigRow[]>(`/tenants/${tenantId}/report-config`, { entries })
      .pipe(tap(() => this.invalidate(tenantId)));
  }

  isEnabled(tenantId: string, reportKey: string): Observable<boolean> {
    return this.api.get<boolean>(`/tenants/${tenantId}/report-config/enabled/${reportKey}`);
  }

  invalidate(tenantId?: string): void {
    if (tenantId) this.cache.delete(tenantId);
    else this.cache.clear();
  }

  /** Test seam — expose an empty observable for scenarios without a tenant. */
  empty(): Observable<TenantReportConfigRow[]> {
    return of([]);
  }
}
