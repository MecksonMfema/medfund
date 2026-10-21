import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { EndorsementRegisterReportService } from './endorsement-register-report.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link EndorsementRegisterReportService}. Backend at
 * {@code /api/v1/reports/premium/endorsements} in user-service; the gateway
 * proxies the endorsements sub-route ahead of the broader
 * {@code /api/v1/reports/premium/*} catchall so registration-order
 * dispatch reaches user-service.
 */
describe('EndorsementRegisterReportService', () => {
  let service: EndorsementRegisterReportService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(EndorsementRegisterReportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /reports/premium/endorsements forwards period + line + status + reporting currency', () => {
    service.get({
      periodStart:       '2026-04-01',
      periodEnd:         '2026-04-30',
      insuranceLine:     'LIFE',
      status:            'COMMITTED',
      reportingCurrency: 'USD',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/endorsements`
      && r.params.get('periodStart')       === '2026-04-01'
      && r.params.get('periodEnd')         === '2026-04-30'
      && r.params.get('insuranceLine')     === 'LIFE'
      && r.params.get('status')            === 'COMMITTED'
      && r.params.get('reportingCurrency') === 'USD');
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'ENDORSEMENT_REGISTER', period: null, reportingCurrency: 'USD',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/premium/endorsements omits optional filters when unset', () => {
    service.get({
      periodStart: '2026-04-01',
      periodEnd:   '2026-04-30',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/endorsements`
      && r.params.get('periodStart')       === '2026-04-01'
      && r.params.get('periodEnd')         === '2026-04-30'
      && r.params.get('insuranceLine')     === null
      && r.params.get('status')            === null
      && r.params.get('reportingCurrency') === null);
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'ENDORSEMENT_REGISTER', period: null, reportingCurrency: '',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/premium/endorsements/export/excel returns a blob', () => {
    service.exportExcel({
      periodStart: '2026-04-01',
      periodEnd:   '2026-04-30',
    }).subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/endorsements/export/excel`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['x'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
  });

  it('GET /reports/premium/endorsements/export/excel forwards status filter', () => {
    service.exportExcel({
      periodStart: '2026-04-01',
      periodEnd:   '2026-04-30',
      status:      'DRAFT',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/premium/endorsements/export/excel`
      && r.params.get('status') === 'DRAFT');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['y']));
  });
});
