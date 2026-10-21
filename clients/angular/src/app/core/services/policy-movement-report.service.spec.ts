import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { PolicyMovementReportService } from './policy-movement-report.service';
import { environment } from '../../../environments/environment';

describe('PolicyMovementReportService', () => {
  let service: PolicyMovementReportService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PolicyMovementReportService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('GET /reports/policy-lifecycle/movement forwards period + reporting currency', () => {
    service.get({ periodStart: '2026-01-01', periodEnd: '2026-03-31', reportingCurrency: 'USD' }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/policy-lifecycle/movement`
      && r.params.get('periodStart')       === '2026-01-01'
      && r.params.get('periodEnd')         === '2026-03-31'
      && r.params.get('reportingCurrency') === 'USD');
    expect(req.request.method).toBe('GET');
    req.flush({ reportKey: 'POLICY_MOVEMENT', period: null, reportingCurrency: 'USD',
                data: { rows: [] }, perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '' });
  });

  it('GET /reports/policy-lifecycle/movement omits reporting currency when unset', () => {
    service.get({ periodStart: '2026-01-01', periodEnd: '2026-03-31' }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/policy-lifecycle/movement`
      && r.params.get('reportingCurrency') === null);
    req.flush({ reportKey: 'POLICY_MOVEMENT', period: null, reportingCurrency: 'USD',
                data: { rows: [] }, perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '' });
  });

  it('GET /reports/policy-lifecycle/movement/export returns a blob', () => {
    service.exportExcel({ periodStart: '2026-01-01', periodEnd: '2026-03-31' })
      .subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r => r.url === `${baseUrl}/reports/policy-lifecycle/movement/export`);
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['x']));
  });
});
