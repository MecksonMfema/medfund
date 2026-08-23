import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { CommissionAdjustmentService } from './commission-adjustment.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link CommissionAdjustmentService}. Backend at
 * {@code /api/v1/commission/adjustments/*}; the gateway wildcard at
 * {@code /api/v1/commission/*} forwards to finance-service. A rename of
 * any of these URLs would silently break the drafter/approver queues.
 */
describe('CommissionAdjustmentService', () => {
  let service: CommissionAdjustmentService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CommissionAdjustmentService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /commission/adjustments — default (no status filter) with page + size', () => {
    service.list(undefined, 1, 25).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/commission/adjustments`
      && r.params.get('page') === '1'
      && r.params.get('size') === '25'
      && !r.params.has('status'));
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], total: 0, page: 1, size: 25, totalPages: 0 });
  });

  it('GET /commission/adjustments?status=DRAFT when status is narrowed', () => {
    service.list('DRAFT').subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/commission/adjustments`
      && r.params.get('status') === 'DRAFT');
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], total: 0, page: 0, size: 50, totalPages: 0 });
  });

  it('GET /commission/adjustments/{id} on detail lookup', () => {
    service.get('adj-1').subscribe();
    const req = http.expectOne(`${baseUrl}/commission/adjustments/adj-1`);
    expect(req.request.method).toBe('GET');
    req.flush({ id: 'adj-1' });
  });

  it('POST /commission/adjustments with the create payload', () => {
    service.create({
      targetCommissionTransactionId: 'ct-1',
      adjustmentType: 'EX_GRATIA',
      adjustmentAmount: 50,
      justification: 'Broker earned a promo kicker not caught by rate card',
    }).subscribe();
    const req = http.expectOne(`${baseUrl}/commission/adjustments`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      targetCommissionTransactionId: 'ct-1',
      adjustmentType: 'EX_GRATIA',
      adjustmentAmount: 50,
      justification: 'Broker earned a promo kicker not caught by rate card',
    });
    req.flush({ id: 'adj-1' });
  });

  it('PUT /commission/adjustments/{id}/approve on approve', () => {
    service.approve('adj-1').subscribe();
    const req = http.expectOne(`${baseUrl}/commission/adjustments/adj-1/approve`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({});
    req.flush({ id: 'adj-1', status: 'APPROVED' });
  });

  it('PUT /commission/adjustments/{id}/commit on commit', () => {
    service.commit('adj-1').subscribe();
    const req = http.expectOne(`${baseUrl}/commission/adjustments/adj-1/commit`);
    expect(req.request.method).toBe('PUT');
    req.flush({ id: 'adj-1', status: 'COMMITTED' });
  });

  it('POST /commission/adjustments/{id}/void with the reason body', () => {
    service.void('adj-1', 'no longer needed').subscribe();
    const req = http.expectOne(`${baseUrl}/commission/adjustments/adj-1/void`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'no longer needed' });
    req.flush({ id: 'adj-1', status: 'VOIDED' });
  });
});
