import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { MemberNumberConfig, MemberNumberConfigService } from './member-number-config.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for the V126 member-number config endpoint. A URL
 * or verb regression here would silently break the admin panel's
 * ability to persist the tenant's shape knobs — the form would submit
 * with no visible error and the config would revert on next reload.
 */
describe('MemberNumberConfigService', () => {
  let service: MemberNumberConfigService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MemberNumberConfigService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GETs /tenants/{id}/member-number-config', () => {
    service.get('t-1').subscribe();

    const req = http.expectOne(`${baseUrl}/tenants/t-1/member-number-config`);
    expect(req.request.method).toBe('GET');
    req.flush({
      tenantId: 't-1',
      memberNumberScheme: 'INDEPENDENT',
      memberNumberPrefix: 'MBR-',
      dependantNumberPrefix: 'DEP-',
      memberNumberRandomLength: 6,
      memberNumberSuffixSeparator: '-',
      memberNumberSuffixPadding: 2,
      memberNumberSuffixStart: 1,
    });
  });

  it('PUTs /tenants/{id}/member-number-config with every field', () => {
    // Backend expects the full config on every PUT — no partial updates
    // are supported. A missing field would be interpreted as "reset to
    // default" by the validator; guard the wire against a stripped body.
    const payload: Omit<MemberNumberConfig, 'tenantId'> = {
      memberNumberScheme: 'SHARED_WITH_SUFFIX',
      memberNumberPrefix: 'MED-',
      dependantNumberPrefix: 'DEP-',
      memberNumberRandomLength: 8,
      memberNumberSuffixSeparator: '_',
      memberNumberSuffixPadding: 4,
      memberNumberSuffixStart: 1,
    };
    service.update('t-1', payload).subscribe();

    const req = http.expectOne(`${baseUrl}/tenants/t-1/member-number-config`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(payload);
    req.flush({ ...payload, tenantId: 't-1' });
  });
});
