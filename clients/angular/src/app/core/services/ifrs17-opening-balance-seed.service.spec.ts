import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { Ifrs17OpeningBalanceSeedService } from './ifrs17-opening-balance-seed.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link Ifrs17OpeningBalanceSeedService}. Backend at
 * {@code /api/v1/underwriting/opening-balances} (Phase 15 §7).
 */
describe('Ifrs17OpeningBalanceSeedService', () => {
  let service: Ifrs17OpeningBalanceSeedService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Ifrs17OpeningBalanceSeedService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /underwriting/opening-balances', () => {
    service.list().subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/opening-balances`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POST /underwriting/opening-balances sends the create body', () => {
    service
      .add({
        portfolioId: 'p1',
        cohortId: 'c1',
        currency: 'USD',
        balanceType: 'LRC',
        amount: '50000.00',
        effectiveFrom: '2026-01-01',
        reasonNote: 'manual override for jan opening',
      })
      .subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/opening-balances`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      portfolioId: 'p1',
      cohortId: 'c1',
      currency: 'USD',
      balanceType: 'LRC',
      amount: '50000.00',
      effectiveFrom: '2026-01-01',
      reasonNote: 'manual override for jan opening',
    });
    req.flush({});
  });

  it('PUT /underwriting/opening-balances/{id} sends the update body', () => {
    service
      .update('s1', { amount: '62000.00', reasonNote: 'adjusted' })
      .subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/opening-balances/s1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ amount: '62000.00', reasonNote: 'adjusted' });
    req.flush({});
  });

  it('DELETE /underwriting/opening-balances/{id}', () => {
    service.delete('s1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/opening-balances/s1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
