import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  TenantEndorsementConfigService,
  UpdateTenantEndorsementConfigPayload,
} from './tenant-endorsement-config.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link TenantEndorsementConfigService}. Backed by
 * {@code GET/PUT /api/v1/tenants/{id}/endorsement-config} on tenancy-service
 * (V134). A rename or path drift would silently break the tenant-admin
 * endorsement-config settings tab.
 */
describe('TenantEndorsementConfigService', () => {
  let service: TenantEndorsementConfigService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;
  const tenantId = 'tenant-uuid-1';

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TenantEndorsementConfigService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /tenants/{id}/endorsement-config returns the config', () => {
    service.get(tenantId).subscribe(cfg => {
      expect(cfg.tenantId).toBe(tenantId);
      expect(cfg.enabled).toBe(true);
      expect(cfg.fourEyesThresholdAmount).toBe('100.00');
      expect(cfg.thresholdCurrency).toBe('USD');
    });
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/endorsement-config`);
    expect(req.request.method).toBe('GET');
    req.flush({
      tenantId,
      enabled: true,
      fourEyesThresholdAmount: '100.00',
      thresholdCurrency: 'USD',
      updatedAt: '2026-08-22T10:00:00Z',
      updatedBy: 'actor-a',
      updatedByEmail: 'admin@test',
    });
  });

  it('PUT /tenants/{id}/endorsement-config sends the enabled payload verbatim', () => {
    const payload: UpdateTenantEndorsementConfigPayload = {
      enabled: true,
      fourEyesThresholdAmount: '250.00',
      thresholdCurrency: 'USD',
    };
    service.update(tenantId, payload).subscribe();
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/endorsement-config`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(payload);
    req.flush({
      tenantId,
      enabled: true,
      fourEyesThresholdAmount: '250.00',
      thresholdCurrency: 'USD',
      updatedAt: '2026-08-22T10:00:00Z',
      updatedBy: 'actor-a',
      updatedByEmail: 'admin@test',
    });
  });

  it('PUT accepts a disabled payload with nulled threshold + currency', () => {
    const payload: UpdateTenantEndorsementConfigPayload = {
      enabled: false,
      fourEyesThresholdAmount: null,
      thresholdCurrency: null,
    };
    service.update(tenantId, payload).subscribe();
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/endorsement-config`);
    expect(req.request.body).toEqual(payload);
    req.flush({
      tenantId,
      enabled: false,
      fourEyesThresholdAmount: null,
      thresholdCurrency: null,
      updatedAt: null,
      updatedBy: null,
      updatedByEmail: null,
    });
  });
});
