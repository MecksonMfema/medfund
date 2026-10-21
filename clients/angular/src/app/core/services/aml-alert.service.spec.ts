import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  AmlAlertPage,
  AmlAlertResponse,
  AmlAlertService,
  FileAmlAlertRequest,
  RaiseAmlAlertRequest,
  ReviewAmlAlertRequest,
} from './aml-alert.service';
import { environment } from '../../../environments/environment';

/**
 * Verifies the AmlAlertService issues the correct method + URL + body for
 * every workflow endpoint. The `HttpTestingController` intercepts calls so
 * we assert against the wire shape.
 */
describe('AmlAlertService', () => {
  let service: AmlAlertService;
  let http: HttpTestingController;

  const base = `${environment.apiBaseUrl}/regulatory/aml/alerts`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AmlAlertService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('queue: defaults omit status filter and sends pagination', () => {
    const page: AmlAlertPage = { content: [], total: 0, page: 0, size: 50, totalPages: 1 };
    service.queue(null, 0, 50).subscribe();
    const req = http.expectOne(`${base}?page=0&size=50`);
    expect(req.request.method).toBe('GET');
    req.flush(page);
  });

  it('queue: adds status filter when supplied', () => {
    service.queue('FILED', 1, 25).subscribe();
    const req = http.expectOne(`${base}?page=1&size=25&status=FILED`);
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], total: 0, page: 1, size: 25, totalPages: 1 });
  });

  it('raise: POSTs to base with the payload', () => {
    const body: RaiseAmlAlertRequest = {
      transactionRef: 'TXN-2026-000001',
      transactionType: 'PREMIUM',
      amountNative: '15000.00',
      currency: 'USD',
      memberId: null,
      providerId: null,
      description: 'Large cash premium from new member without employer support.',
    };
    service.raise(body).subscribe();

    const req = http.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(stub('RAISED'));
  });

  it('review: PUTs to /{id}/review with the note', () => {
    const body: ReviewAmlAlertRequest = { reviewNote: 'Triaged — matches known pattern' };
    service.review('id-1', body).subscribe();

    const req = http.expectOne(`${base}/id-1/review`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush(stub('REVIEWED'));
  });

  it('file: PUTs to /{id}/file with the filing reference', () => {
    const body: FileAmlAlertRequest = {
      filedRef: 'FIU-STR-2026-0001234',
      filedXlsxRef: 's3://aml/2026/xxx.xlsx',
    };
    service.file('id-2', body).subscribe();

    const req = http.expectOne(`${base}/id-2/file`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush(stub('FILED'));
  });

  it('close: POSTs to /{id}/close with the reason', () => {
    service.close('id-3', { closedReason: 'Not reportable — duplicate' }).subscribe();

    const req = http.expectOne(`${base}/id-3/close`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ closedReason: 'Not reportable — duplicate' });
    req.flush(stub('CLOSED'));
  });

  it('get: fetches a single alert by id', () => {
    service.get('id-4').subscribe();
    const req = http.expectOne(`${base}/id-4`);
    expect(req.request.method).toBe('GET');
    req.flush(stub('RAISED'));
  });

  function stub(status: AmlAlertResponse['status']): AmlAlertResponse {
    return {
      id: 'id-x',
      status,
      transactionRef: 'TXN-000001',
      transactionType: 'PREMIUM',
      amountNative: '15000.00',
      currency: 'USD',
      memberId: null,
      providerId: null,
      description: 'x',
      raisedByActorId: null,
      raisedByActorEmail: null,
      raisedAt: '2026-08-30T00:00:00Z',
      reviewerActorId: null,
      reviewerActorEmail: null,
      reviewedAt: null,
      reviewNote: null,
      filerActorId: null,
      filerActorEmail: null,
      filedAt: null,
      filedRef: null,
      filedXlsxRef: null,
      closerActorId: null,
      closerActorEmail: null,
      closedAt: null,
      closedReason: null,
    };
  }
});
