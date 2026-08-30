import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { CohortStatusHistoryService } from './cohort-status-history.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link CohortStatusHistoryService}. Backend at
 * {@code /api/v1/underwriting/cohorts/{id}/status-history} (Phase 15 §4).
 */
describe('CohortStatusHistoryService', () => {
  let service: CohortStatusHistoryService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CohortStatusHistoryService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /underwriting/cohorts/{id}/status-history', () => {
    service.listForCohort('c1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/cohorts/c1/status-history`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
