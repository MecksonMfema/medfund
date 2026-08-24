import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { PersistencyCohortReportService } from './persistency-cohort-report.service';
import { environment } from '../../../environments/environment';

describe('PersistencyCohortReportService', () => {
  let service: PersistencyCohortReportService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PersistencyCohortReportService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('GET /reports/policy-lifecycle/persistency-cohort forwards checkpoints + insuranceLine', () => {
    service.get({
      periodStart: '2024-01-01', periodEnd: '2024-12-31',
      checkpoints: '6,12,24', insuranceLine: 'HEALTH',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/policy-lifecycle/persistency-cohort`
      && r.params.get('checkpoints')   === '6,12,24'
      && r.params.get('insuranceLine') === 'HEALTH');
    req.flush({ reportKey: 'PERSISTENCY_COHORT', period: null, reportingCurrency: 'USD',
                data: { rows: [], freshnessWarning: null },
                perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '' });
  });

  it('GET /reports/policy-lifecycle/persistency-cohort/export returns a blob', () => {
    service.exportExcel({ periodStart: '2024-01-01', periodEnd: '2024-12-31' })
      .subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/policy-lifecycle/persistency-cohort/export`);
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['y']));
  });
});
