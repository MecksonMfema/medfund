import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { GroupCensusReportService } from './group-census-report.service';
import { environment } from '../../../environments/environment';

describe('GroupCensusReportService', () => {
  let service: GroupCensusReportService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GroupCensusReportService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('GET /reports/policy-lifecycle/group-census forwards asOf + groupId', () => {
    service.get({ asOf: '2026-08-01', groupId: 'abc' }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/policy-lifecycle/group-census`
      && r.params.get('asOf') === '2026-08-01'
      && r.params.get('groupId') === 'abc');
    req.flush({ reportKey: 'GROUP_CENSUS', period: null, reportingCurrency: 'USD',
                data: { asOf: '2026-08-01', groups: [] },
                perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '' });
  });

  it('GET /reports/policy-lifecycle/group-census/export returns a blob', () => {
    service.exportExcel({ asOf: '2026-08-01' }).subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r => r.url === `${baseUrl}/reports/policy-lifecycle/group-census/export`);
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['z']));
  });
});
