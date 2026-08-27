import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  ActuarialReportsService,
  PersistencyStudyJobRequest,
  TriangleJobRequest,
} from './actuarial-reports.service';
import { environment } from '../../../environments/environment';

describe('ActuarialReportsService', () => {
  let service: ActuarialReportsService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  const body: TriangleJobRequest = {
    periodStart: '2024-01-01',
    periodEnd:   '2024-12-31',
    insuranceLine: 'HEALTH',
    shape: 'paid',
    grain: 'quarter',
    reportingCurrency: 'USD',
    ldfMethod: 'volume',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ActuarialReportsService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('POST /reports/actuarial/ibnr forwards the triangle body verbatim', () => {
    service.submitIbnr(body).subscribe();
    const req = http.expectOne(`${baseUrl}/reports/actuarial/ibnr`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({ jobId: 'a', status: 'requested', deduplicated: false });
  });

  it('POST /reports/actuarial/loss-triangle uses the loss endpoint', () => {
    service.submitLoss(body).subscribe();
    const req = http.expectOne(`${baseUrl}/reports/actuarial/loss-triangle`);
    expect(req.request.method).toBe('POST');
    req.flush({ jobId: 'b', status: 'requested', deduplicated: true });
  });

  it('GET /reports/actuarial/jobs/{jobId} polls status', () => {
    service.status('abc').subscribe(r => expect(r.status).toBe('completed'));
    const req = http.expectOne(`${baseUrl}/reports/actuarial/jobs/abc`);
    expect(req.request.method).toBe('GET');
    req.flush({
      jobId: 'abc', reportKey: 'IBNR_TRIANGLE', status: 'completed',
      progressPct: 100, resultJson: null, errorMessage: null,
      requestedAt: '2026-08-27T00:00:00Z', completedAt: '2026-08-27T00:00:05Z',
    });
  });

  it('exportXlsxUrl returns an absolute URL', () => {
    const url = service.exportXlsxUrl('abc');
    expect(url).toBe(`${baseUrl}/reports/actuarial/jobs/abc/export.xlsx`);
  });

  it('exportXlsxBlob GETs the export as a blob', () => {
    service.exportXlsxBlob('abc').subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(`${baseUrl}/reports/actuarial/jobs/abc/export.xlsx`);
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['y']));
  });

  it('POST /reports/actuarial/persistency-study forwards the study body', () => {
    const study: PersistencyStudyJobRequest = {
      periodStart: '2024-01-01',
      periodEnd: '2024-12-31',
      checkpoints: [3, 6, 12],
      insuranceLine: 'HEALTH',
      reportingCurrency: 'USD',
    };
    service.submitPersistencyStudy(study).subscribe();
    const req = http.expectOne(`${baseUrl}/reports/actuarial/persistency-study`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(study);
    req.flush({ jobId: 'p', status: 'requested', deduplicated: false });
  });
});
