import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { CommissionReportService } from './commission-report.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link CommissionReportService}. Backend at
 * {@code /api/v1/reports/commission/*}; the gateway proxies the prefix to
 * finance-service. A rename of any of these URLs would silently break
 * both commission report pages, so the four seams are asserted directly.
 */
describe('CommissionReportService', () => {
  let service: CommissionReportService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CommissionReportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /reports/commission/statement with period + producer + reporting currency', () => {
    service.getStatement({
      periodStart: '2026-07-01',
      periodEnd:   '2026-09-30',
      producerId:  'prod-1',
      reportingCurrency: 'USD',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/commission/statement`
      && r.params.get('periodStart') === '2026-07-01'
      && r.params.get('periodEnd')   === '2026-09-30'
      && r.params.get('producerId')  === 'prod-1'
      && r.params.get('reportingCurrency') === 'USD');
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'COMMISSION_STATEMENT', period: null, reportingCurrency: 'USD',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/commission/statement omits optional producer + currency when unset', () => {
    service.getStatement({
      periodStart: '2026-01-01',
      periodEnd:   '2026-01-31',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/commission/statement`
      && r.params.get('periodStart') === '2026-01-01'
      && r.params.get('periodEnd')   === '2026-01-31'
      && r.params.get('producerId')        === null
      && r.params.get('reportingCurrency') === null);
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'COMMISSION_STATEMENT', period: null, reportingCurrency: '',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/commission/statement/export/excel returns a blob', () => {
    service.exportStatementExcel({
      periodStart: '2026-07-01',
      periodEnd:   '2026-09-30',
    }).subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/commission/statement/export/excel`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['x'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
  });

  it('GET /reports/commission/clawback-register forwards source filter', () => {
    service.getClawbackRegister({
      periodStart: '2026-07-01',
      periodEnd:   '2026-09-30',
      source:      'MEMBER_LAPSE',
      producerId:  'prod-7',
      reportingCurrency: 'ZAR',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/commission/clawback-register`
      && r.params.get('source')     === 'MEMBER_LAPSE'
      && r.params.get('producerId') === 'prod-7'
      && r.params.get('reportingCurrency') === 'ZAR');
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'COMMISSION_CLAWBACK', period: null, reportingCurrency: 'ZAR',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/commission/clawback-register omits source when blank', () => {
    service.getClawbackRegister({
      periodStart: '2026-07-01',
      periodEnd:   '2026-09-30',
      source:      '',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/commission/clawback-register`
      && r.params.get('source') === null);
    expect(req.request.method).toBe('GET');
    req.flush({
      reportKey: 'COMMISSION_CLAWBACK', period: null, reportingCurrency: '',
      data: [], perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    });
  });

  it('GET /reports/commission/clawback-register/export/excel returns a blob', () => {
    service.exportClawbackRegisterExcel({
      periodStart: '2026-07-01',
      periodEnd:   '2026-09-30',
      source:      'CONTRIBUTION_REVOKE',
    }).subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/commission/clawback-register/export/excel`
      && r.params.get('source') === 'CONTRIBUTION_REVOKE');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['y'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
  });
});
