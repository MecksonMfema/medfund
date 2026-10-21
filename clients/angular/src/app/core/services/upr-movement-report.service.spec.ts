import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { UprMovementReportService } from './upr-movement-report.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link UprMovementReportService}. Backend at
 * {@code /api/v1/reports/premium/upr-movement}; the gateway proxies the
 * {@code /api/v1/reports/premium} prefix to contributions-service. A
 * rename would silently break the UPR movement page, so the four seams
 * (GET envelope, GET export, filter forwarding, optional-omission) are
 * asserted directly.
 */
describe('UprMovementReportService', () => {
  let service: UprMovementReportService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UprMovementReportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /reports/premium/upr-movement forwards period + line + reporting currency', () => {
    service.get({
      periodStart:       '2026-01-01',
      periodEnd:         '2026-03-31',
      insuranceLine:     'LIFE',
      reportingCurrency: 'USD',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/upr-movement`
      && r.params.get('periodStart')       === '2026-01-01'
      && r.params.get('periodEnd')         === '2026-03-31'
      && r.params.get('insuranceLine')     === 'LIFE'
      && r.params.get('reportingCurrency') === 'USD');
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'UPR_MOVEMENT', period: null, reportingCurrency: 'USD',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/premium/upr-movement omits optional line + currency when unset', () => {
    service.get({
      periodStart: '2026-01-01',
      periodEnd:   '2026-01-31',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/upr-movement`
      && r.params.get('periodStart')       === '2026-01-01'
      && r.params.get('periodEnd')         === '2026-01-31'
      && r.params.get('insuranceLine')     === null
      && r.params.get('reportingCurrency') === null);
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'UPR_MOVEMENT', period: null, reportingCurrency: '',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/premium/upr-movement/export/excel returns a blob', () => {
    service.exportExcel({
      periodStart: '2026-01-01',
      periodEnd:   '2026-03-31',
    }).subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/upr-movement/export/excel`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['x'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
  });

  it('GET /reports/premium/upr-movement/export/excel forwards insuranceLine filter', () => {
    service.exportExcel({
      periodStart:   '2026-01-01',
      periodEnd:     '2026-03-31',
      insuranceLine: 'VEHICLE',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/upr-movement/export/excel`
      && r.params.get('insuranceLine') === 'VEHICLE');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['y']));
  });
});
