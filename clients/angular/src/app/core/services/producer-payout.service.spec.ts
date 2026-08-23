import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ProducerPayoutService } from './producer-payout.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link ProducerPayoutService}. Backend at
 * {@code /api/v1/payment-runs} with {@code payeeType=PRODUCER} — a rename
 * of the payload keys ({@code periodStart}, {@code periodEnd},
 * {@code payeeType}) would silently break the producer-payout create form.
 */
describe('ProducerPayoutService', () => {
  let service: ProducerPayoutService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProducerPayoutService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /payment-runs?payeeType=PRODUCER when no filters supplied', () => {
    service.list().subscribe();
    const req = http.expectOne(`${baseUrl}/payment-runs?payeeType=PRODUCER`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /payment-runs with status + currency filters', () => {
    service.list('draft', 'USD').subscribe();
    const req = http.expectOne(
      `${baseUrl}/payment-runs?payeeType=PRODUCER&status=draft&currencyCode=USD`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /payment-runs/{id}', () => {
    service.get('run-1').subscribe();
    const req = http.expectOne(`${baseUrl}/payment-runs/run-1`);
    expect(req.request.method).toBe('GET');
    req.flush({ id: 'run-1' });
  });

  it('GET /payment-runs/{id}/items', () => {
    service.items('run-1').subscribe();
    const req = http.expectOne(`${baseUrl}/payment-runs/run-1/items`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POST /payment-runs body hardcodes payeeType=PRODUCER and forwards period', () => {
    service.create({
      currencyCode: 'USD',
      description: 'Q3 producer commissions',
      sourceBankAccountId: 'bank-1',
      periodStart: '2026-07-01',
      periodEnd: '2026-09-30',
    }).subscribe();
    const req = http.expectOne(`${baseUrl}/payment-runs`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      currencyCode: 'USD',
      description: 'Q3 producer commissions',
      payeeType: 'PRODUCER',
      sourceBankAccountId: 'bank-1',
      periodStart: '2026-07-01',
      periodEnd: '2026-09-30',
    });
    req.flush({ id: 'run-new' });
  });

  it('POST /payment-runs/{id}/approve with empty body', () => {
    service.approve('run-1').subscribe();
    const req = http.expectOne(`${baseUrl}/payment-runs/run-1/approve`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({});
  });

  it('POST /payment-runs/{id}/execute with empty body', () => {
    service.execute('run-1').subscribe();
    const req = http.expectOne(`${baseUrl}/payment-runs/run-1/execute`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({});
  });
});
