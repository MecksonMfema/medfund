import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { PremiumRegisterReportService } from './premium-register-report.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link PremiumRegisterReportService}. Backend at
 * {@code /api/v1/reports/premium/register}; the gateway proxies the
 * {@code /api/v1/reports/premium} prefix to contributions-service.
 */
describe('PremiumRegisterReportService', () => {
  let service: PremiumRegisterReportService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PremiumRegisterReportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /reports/premium/register forwards period + line + reporting currency', () => {
    service.get({
      periodStart:       '2026-04-01',
      periodEnd:         '2026-06-30',
      insuranceLine:     'FUNERAL',
      reportingCurrency: 'ZAR',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/register`
      && r.params.get('periodStart')       === '2026-04-01'
      && r.params.get('periodEnd')         === '2026-06-30'
      && r.params.get('insuranceLine')     === 'FUNERAL'
      && r.params.get('reportingCurrency') === 'ZAR');
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'PREMIUM_REGISTER', period: null, reportingCurrency: 'ZAR',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/premium/register omits optional line + currency when unset', () => {
    service.get({
      periodStart: '2026-04-01',
      periodEnd:   '2026-04-30',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/register`
      && r.params.get('insuranceLine')     === null
      && r.params.get('reportingCurrency') === null);
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'PREMIUM_REGISTER', period: null, reportingCurrency: '',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/premium/register/export/excel returns a blob', () => {
    service.exportExcel({
      periodStart: '2026-04-01',
      periodEnd:   '2026-06-30',
    }).subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/register/export/excel`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['x']));
  });

  it('GET /reports/premium/register/export/excel forwards insuranceLine filter', () => {
    service.exportExcel({
      periodStart:   '2026-04-01',
      periodEnd:     '2026-06-30',
      insuranceLine: 'HEALTH',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/register/export/excel`
      && r.params.get('insuranceLine') === 'HEALTH');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['y']));
  });
});
