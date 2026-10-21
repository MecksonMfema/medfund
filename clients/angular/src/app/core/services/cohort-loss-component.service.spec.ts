import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { CohortLossComponentService } from './cohort-loss-component.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link CohortLossComponentService}. Backend at
 * {@code /api/v1/underwriting/cohorts/{id}/loss-component} (Phase 15 §5).
 */
describe('CohortLossComponentService', () => {
  let service: CohortLossComponentService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CohortLossComponentService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /underwriting/cohorts/{id}/loss-component', () => {
    service.listForCohort('c1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/cohorts/c1/loss-component`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /underwriting/cohorts/{id}/loss-component/balance passes currency', () => {
    service.balance('c1', 'USD').subscribe();
    const req = http.expectOne(
      `${baseUrl}/underwriting/cohorts/c1/loss-component/balance?currency=USD`,
    );
    expect(req.request.method).toBe('GET');
    req.flush({ cohortId: 'c1', currency: 'USD', balance: '0.00' });
  });

  it('POST /underwriting/cohorts/{id}/loss-component sends the movement body', () => {
    service
      .record('c1', {
        movementType: 'INITIAL_RECOGNITION',
        amount: '50000.00',
        currency: 'USD',
        reasonNote: 'test',
      })
      .subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/cohorts/c1/loss-component`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      movementType: 'INITIAL_RECOGNITION',
      amount: '50000.00',
      currency: 'USD',
      reasonNote: 'test',
    });
    req.flush({});
  });
});
