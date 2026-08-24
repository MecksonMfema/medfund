import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ProviderNetworkUtilizationReportService } from './provider-network-utilization-report.service';
import { environment } from '../../../environments/environment';

describe('ProviderNetworkUtilizationReportService', () => {
  let service: ProviderNetworkUtilizationReportService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProviderNetworkUtilizationReportService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('GET /reports/claims/provider-network-utilization forwards line + tier + currency', () => {
    service.get({
      periodStart: '2026-06-01', periodEnd: '2026-06-30',
      insuranceLine: 'HEALTH', networkTier: 'TIER_1', reportingCurrency: 'USD',
    }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/claims/provider-network-utilization`
      && r.params.get('insuranceLine')     === 'HEALTH'
      && r.params.get('networkTier')       === 'TIER_1'
      && r.params.get('reportingCurrency') === 'USD');
    req.flush({ reportKey: 'PROVIDER_NETWORK_UTILIZATION', period: null, reportingCurrency: 'USD',
                data: { summary: {}, detail: [] },
                perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '' });
  });

  it('GET /reports/claims/provider-network-utilization/export returns a blob', () => {
    service.exportExcel({ periodStart: '2026-06-01', periodEnd: '2026-06-30' })
      .subscribe(blob => expect(blob).toBeInstanceOf(Blob));
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/reports/claims/provider-network-utilization/export`);
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['w']));
  });
});
