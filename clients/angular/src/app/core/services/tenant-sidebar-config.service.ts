import { Injectable } from '@angular/core';
import { Observable, of, shareReplay } from 'rxjs';
import { tap } from 'rxjs/operators';
import { ApiService } from './api.service';

/**
 * One entry per catalogued sidebar section. Rows the tenant admin has
 * never touched come back with id=null and enabled=true so the UI can
 * toggle without a distinction between "no row yet" and "explicitly
 * enabled".
 */
export interface TenantSidebarSectionConfigRow {
  id: string | null;
  tenantId: string;
  sectionKey: string;
  label: string;
  group: string | null;
  groupLabel: string | null;
  enabled: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface SidebarToggleEntry {
  sectionKey: string;
  enabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class TenantSidebarConfigService {
  /** Per-tenant snapshot — shared to feed both the settings tab and the
   *  operational sidebar without re-fetching. Invalidated on any write. */
  private cache = new Map<string, Observable<TenantSidebarSectionConfigRow[]>>();

  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantSidebarSectionConfigRow[]> {
    const cached = this.cache.get(tenantId);
    if (cached) return cached;
    const fresh$ = this.api.get<TenantSidebarSectionConfigRow[]>(
      `/tenants/${tenantId}/sidebar-section-config`,
    ).pipe(shareReplay(1));
    this.cache.set(tenantId, fresh$);
    return fresh$;
  }

  bulkUpsert(tenantId: string, entries: SidebarToggleEntry[]): Observable<TenantSidebarSectionConfigRow[]> {
    return this.api
      .put<TenantSidebarSectionConfigRow[]>(`/tenants/${tenantId}/sidebar-section-config`, { entries })
      .pipe(tap(() => this.invalidate(tenantId)));
  }

  isEnabled(tenantId: string, sectionKey: string): Observable<boolean> {
    return this.api.get<boolean>(`/tenants/${tenantId}/sidebar-section-config/enabled/${sectionKey}`);
  }

  invalidate(tenantId?: string): void {
    if (tenantId) this.cache.delete(tenantId);
    else this.cache.clear();
  }

  /** Test seam — expose an empty observable for scenarios without a tenant. */
  empty(): Observable<TenantSidebarSectionConfigRow[]> {
    return of([]);
  }
}
