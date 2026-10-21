import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { Ifrs17ReportRequest, Ifrs17ReportsService } from './ifrs17-reports.service';
import { environment } from '../../../environments/environment';

describe('Ifrs17ReportsService', () => {
  let service: Ifrs17ReportsService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  const body: Ifrs17ReportRequest = {
    periodStart: '2026-01-01',
    periodEnd: '2026-06-30',
    portfolioIds: null,
    reportingCurrency: 'USD',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Ifrs17ReportsService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('POST /reports/ifrs17/lrc-lic-reconciliation forwards the body verbatim', () => {
    service.submitLrcLicReconciliation(body).subscribe();
    const req = http.expectOne(`${baseUrl}/reports/ifrs17/lrc-lic-reconciliation`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({ jobId: 'x1', status: 'requested', chunkCount: 4, deduped: false });
  });

  it('POST /reports/ifrs17/insurance-revenue-service-result uses the second endpoint', () => {
    service.submitInsuranceRevenueServiceResult(body).subscribe();
    const req = http.expectOne(`${baseUrl}/reports/ifrs17/insurance-revenue-service-result`);
    expect(req.request.method).toBe('POST');
    req.flush({ jobId: 'y1', status: 'requested', chunkCount: 2, deduped: true });
  });

  it('exportXlsxUrl returns an absolute URL under /reports/ifrs17', () => {
    const url = service.exportXlsxUrl('abc');
    expect(url).toBe(`${baseUrl}/reports/ifrs17/jobs/abc/export.xlsx`);
  });
});
