import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  TenantAutoLapseConfigService,
  UpdateTenantAutoLapseConfigPayload,
} from './tenant-auto-lapse-config.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link TenantAutoLapseConfigService}. Backed by
 * {@code GET/PUT /api/v1/tenants/{id}/auto-lapse-config} on tenancy-service
 * (V133). A rename or path drift would silently break the tenant-admin
 * auto-lapse settings page.
 */
describe('TenantAutoLapseConfigService', () => {
  let service: TenantAutoLapseConfigService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;
  const tenantId = 'tenant-uuid-1';

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TenantAutoLapseConfigService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /tenants/{id}/auto-lapse-config returns the config', () => {
    service.get(tenantId).subscribe(config => {
      expect(config.tenantId).toBe(tenantId);
      expect(config.enabled).toBe(true);
      expect(config.arrearsThresholdMonths).toBe(3);
      expect(config.graceWindowDays).toBe(7);
    });
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/auto-lapse-config`);
    expect(req.request.method).toBe('GET');
    req.flush({
      tenantId,
      enabled: true,
      arrearsThresholdMonths: 3,
      graceWindowDays: 7,
      updatedAt: '2026-08-22T10:00:00Z',
      updatedBy: null,
      updatedByEmail: 'admin@test',
    });
  });

  it('PUT /tenants/{id}/auto-lapse-config sends the update payload', () => {
    const payload: UpdateTenantAutoLapseConfigPayload = {
      enabled: true,
      arrearsThresholdMonths: 6,
      graceWindowDays: 14,
    };
    service.update(tenantId, payload).subscribe();
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/auto-lapse-config`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(payload);
    req.flush({
      tenantId,
      enabled: true,
      arrearsThresholdMonths: 6,
      graceWindowDays: 14,
      updatedAt: '2026-08-22T10:00:00Z',
      updatedBy: null,
      updatedByEmail: 'admin@test',
    });
  });

  it('PUT accepts a disabled payload with null threshold and grace', () => {
    const payload: UpdateTenantAutoLapseConfigPayload = {
      enabled: false,
      arrearsThresholdMonths: null,
      graceWindowDays: null,
    };
    service.update(tenantId, payload).subscribe();
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/auto-lapse-config`);
    expect(req.request.body).toEqual(payload);
    req.flush({});
  });
});
