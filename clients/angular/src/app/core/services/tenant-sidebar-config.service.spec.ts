import { of } from 'rxjs';
import { TenantSidebarConfigService, TenantSidebarSectionConfigRow } from './tenant-sidebar-config.service';
import { ApiService } from './api.service';

class MockApi {
  getCalls: string[] = [];
  putCalls: Array<{ path: string; body: unknown }> = [];
  rows: TenantSidebarSectionConfigRow[] = [];

  get<T>(path: string) { this.getCalls.push(path); return of(this.rows as unknown as T); }
  put<T>(path: string, body: unknown) { this.putCalls.push({ path, body }); return of(this.rows as unknown as T); }
}

describe('TenantSidebarConfigService', () => {
  const tenantId = 'tenant-123';

  it('list caches per-tenant so a second call does not re-request', () => {
    const api = new MockApi();
    const service = new TenantSidebarConfigService(api as unknown as ApiService);

    service.list(tenantId).subscribe();
    service.list(tenantId).subscribe();
    expect(api.getCalls.filter(p => p.endsWith('/sidebar-section-config')).length).toBe(1);
  });

  it('bulkUpsert invalidates the cache so the next list re-fetches', () => {
    const api = new MockApi();
    const service = new TenantSidebarConfigService(api as unknown as ApiService);

    service.list(tenantId).subscribe();
    service.bulkUpsert(tenantId, [{ sectionKey: 'FINANCE_PAYMENT_RUNS', enabled: false }]).subscribe();
    service.list(tenantId).subscribe();

    expect(api.getCalls.filter(p => p.endsWith('/sidebar-section-config')).length).toBe(2);
    expect(api.putCalls.length).toBe(1);
    expect(api.putCalls[0].body).toEqual({
      entries: [{ sectionKey: 'FINANCE_PAYMENT_RUNS', enabled: false }],
    });
  });

  it('isEnabled hits the point-lookup path', () => {
    const api = new MockApi();
    const service = new TenantSidebarConfigService(api as unknown as ApiService);

    service.isEnabled(tenantId, 'FINANCE_PAYMENT_RUNS').subscribe();
    expect(api.getCalls).toContain(`/tenants/${tenantId}/sidebar-section-config/enabled/FINANCE_PAYMENT_RUNS`);
  });

  it('invalidate() without an argument clears every cached tenant', () => {
    const api = new MockApi();
    const service = new TenantSidebarConfigService(api as unknown as ApiService);

    service.list('t1').subscribe();
    service.list('t2').subscribe();
    service.invalidate();
    service.list('t1').subscribe();
    service.list('t2').subscribe();
    expect(api.getCalls.length).toBe(4);
  });
});
