import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { RegulatoryDueDatesService } from './regulatory-due-dates.service';
import { environment } from '../../../environments/environment';

describe('RegulatoryDueDatesService', () => {
  let service: RegulatoryDueDatesService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RegulatoryDueDatesService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('GET /reports/regulatory/due-dates returns the banner rows', () => {
    const payload = [
      {
        reportKey: 'IPEC_QUARTERLY_RETURN',
        reportLabel: 'IPEC — quarterly return (ZW)',
        cadence: 'QUARTERLY',
        periodStart: '2026-04-01',
        periodEnd: '2026-06-30',
        dueDate: '2026-07-30',
        daysUntilDue: -31,
        submissionStatus: 'PENDING',
        severity: 'RED',
      },
    ];
    service.list().subscribe(rows => {
      expect(rows.length).toBe(1);
      expect(rows[0].reportKey).toBe('IPEC_QUARTERLY_RETURN');
      expect(rows[0].severity).toBe('RED');
    });
    const req = http.expectOne(`${baseUrl}/reports/regulatory/due-dates`);
    expect(req.request.method).toBe('GET');
    req.flush(payload);
  });
});
