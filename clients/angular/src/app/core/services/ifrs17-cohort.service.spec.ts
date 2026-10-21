import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { Ifrs17CohortService } from './ifrs17-cohort.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link Ifrs17CohortService}. Backend at
 * {@code /api/v1/underwriting/cohorts}.
 */
describe('Ifrs17CohortService', () => {
  let service: Ifrs17CohortService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Ifrs17CohortService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /underwriting/cohorts with includeInactive + portfolioId filter', () => {
    service.list(true, 'p1').subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/underwriting/cohorts`
      && r.params.get('includeInactive') === 'true'
      && r.params.get('portfolioId') === 'p1');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POST /underwriting/cohorts with composite-key body', () => {
    service.create({ portfolioId: 'p1', cohortYear: 2026, cohortType: 'NON_ONEROUS', name: 'Cohort A' })
      .subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/cohorts`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.portfolioId).toBe('p1');
    expect(req.request.body.cohortYear).toBe(2026);
    expect(req.request.body.cohortType).toBe('NON_ONEROUS');
    req.flush({ id: 'c1', portfolioId: 'p1', cohortYear: 2026, cohortType: 'NON_ONEROUS', name: 'Cohort A', isActive: true });
  });

  it('PUT /underwriting/cohorts/{id}', () => {
    service.update('c1', { portfolioId: 'p1', cohortYear: 2027, cohortType: 'ONEROUS', name: 'New' }).subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/cohorts/c1`);
    expect(req.request.method).toBe('PUT');
    req.flush({ id: 'c1', portfolioId: 'p1', cohortYear: 2027, cohortType: 'ONEROUS', name: 'New', isActive: true });
  });

  it('DELETE /underwriting/cohorts/{id}', () => {
    service.delete('c1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/cohorts/c1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
