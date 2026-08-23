import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { NewBusinessRegisterReportService } from './new-business-register-report.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link NewBusinessRegisterReportService}. Backend
 * at {@code /api/v1/reports/premium/new-business}; the gateway proxies the
 * {@code /api/v1/reports/premium} prefix to contributions-service.
 */
describe('NewBusinessRegisterReportService', () => {
  let service: NewBusinessRegisterReportService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(NewBusinessRegisterReportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /reports/premium/new-business forwards period + line + reporting currency', () => {
    service.get({
      periodStart:       '2026-07-01',
      periodEnd:         '2026-09-30',
      insuranceLine:     'HEALTH',
      reportingCurrency: 'USD',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/new-business`
      && r.params.get('periodStart')       === '2026-07-01'
      && r.params.get('periodEnd')         === '2026-09-30'
      && r.params.get('insuranceLine')     === 'HEALTH'
      && r.params.get('reportingCurrency') === 'USD');
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'NEW_BUSINESS_REGISTER', period: null, reportingCurrency: 'USD',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/premium/new-business omits optional line + currency when unset', () => {
    service.get({
      periodStart: '2026-07-01',
      periodEnd:   '2026-07-31',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/new-business`
      && r.params.get('insuranceLine')     === null
      && r.params.get('reportingCurrency') === null);
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'NEW_BUSINESS_REGISTER', period: null, reportingCurrency: '',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/premium/new-business/export/excel returns a blob', () => {
    service.exportExcel({
      periodStart: '2026-07-01',
      periodEnd:   '2026-09-30',
    }).subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/new-business/export/excel`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['x']));
  });

  it('GET /reports/premium/new-business/export/excel forwards insuranceLine filter', () => {
    service.exportExcel({
      periodStart:   '2026-07-01',
      periodEnd:     '2026-09-30',
      insuranceLine: 'PROPERTY',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/new-business/export/excel`
      && r.params.get('insuranceLine') === 'PROPERTY');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['y']));
  });
});
