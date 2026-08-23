import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  CreateEndorsementPayload,
  EndorsementResponse,
  EndorsementService,
} from './endorsement.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link EndorsementService}. Every path
 * (list / get / by-policy / create / approve / commit / void)
 * lands on the user-service {@code /api/v1/endorsements} surface
 * that Phase 8 introduced. A rename would silently break the
 * review-queue + create-modal, so each seam is asserted directly.
 */
describe('EndorsementService', () => {
  let service: EndorsementService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  const stubResponse: EndorsementResponse = {
    id: 'end-1',
    reference: 'END-2026-000001',
    policyId: 'pol-1',
    policySource: 'LIFE_POLICY',
    insuranceLine: 'LIFE',
    changeType: 'PREMIUM_ADJUSTMENT',
    effectiveFrom: '2026-05-01',
    premiumDelta: '120.00',
    currencyCode: 'USD',
    reason: 'Cover uplift request from broker.',
    status: 'DRAFT',
    draftActorId: 'actor-a',
    draftActorEmail: 'a@tenant',
    draftAt: '2026-04-15T10:00:00Z',
    approveActorId: null,
    approveActorEmail: null,
    approveAt: null,
    commitActorId: null,
    commitActorEmail: null,
    commitAt: null,
    voidedReason: null,
    voidedAt: null,
    createdAt: '2026-04-15T10:00:00Z',
    updatedAt: '2026-04-15T10:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(EndorsementService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /endorsements returns a page with no query params by default', () => {
    service.list().subscribe(page => {
      expect(page.content.length).toBe(1);
      expect(page.content[0].reference).toBe('END-2026-000001');
    });
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/endorsements`
      && r.params.get('status') === null
      && r.params.get('page') === null
      && r.params.get('size') === null);
    expect(req.request.method).toBe('GET');
    req.flush({
      content: [stubResponse],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    });
  });

  it('GET /endorsements forwards status + page + size when supplied', () => {
    service.list({ status: 'DRAFT', page: 1, size: 25 }).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/endorsements`
      && r.params.get('status') === 'DRAFT'
      && r.params.get('page') === '1'
      && r.params.get('size') === '25');
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 1, size: 25 });
  });

  it('GET /endorsements/{id} fetches a single row', () => {
    service.get('end-1').subscribe(r => expect(r.id).toBe('end-1'));
    const req = http.expectOne(`${baseUrl}/endorsements/end-1`);
    expect(req.request.method).toBe('GET');
    req.flush(stubResponse);
  });

  it('GET /endorsements/by-policy sends policyId + policySource', () => {
    service.listByPolicy('pol-1', 'LIFE_POLICY').subscribe(list => {
      expect(list.length).toBe(1);
    });
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/endorsements/by-policy`
      && r.params.get('policyId') === 'pol-1'
      && r.params.get('policySource') === 'LIFE_POLICY');
    expect(req.request.method).toBe('GET');
    req.flush([stubResponse]);
  });

  it('POST /endorsements sends the draft payload as-is', () => {
    const payload: CreateEndorsementPayload = {
      policyId: 'pol-1',
      policySource: 'LIFE_POLICY',
      insuranceLine: 'LIFE',
      changeType: 'PREMIUM_ADJUSTMENT',
      effectiveFrom: '2026-05-01',
      premiumDelta: '120.00',
      currencyCode: 'USD',
      reason: 'Cover uplift request from broker.',
    };
    service.create(payload).subscribe();
    const req = http.expectOne(`${baseUrl}/endorsements`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(payload);
    req.flush(stubResponse);
  });

  it('PUT /endorsements/{id}/approve sends an empty body', () => {
    service.approve('end-1').subscribe();
    const req = http.expectOne(`${baseUrl}/endorsements/end-1/approve`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({});
    req.flush({ ...stubResponse, status: 'APPROVED' });
  });

  it('PUT /endorsements/{id}/commit sends an empty body', () => {
    service.commit('end-1').subscribe();
    const req = http.expectOne(`${baseUrl}/endorsements/end-1/commit`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({});
    req.flush({ ...stubResponse, status: 'COMMITTED' });
  });

  it('POST /endorsements/{id}/void sends the reason', () => {
    service.void('end-1', { reason: 'Duplicate draft; cancelling.' }).subscribe();
    const req = http.expectOne(`${baseUrl}/endorsements/end-1/void`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Duplicate draft; cancelling.' });
    req.flush({ ...stubResponse, status: 'VOIDED', voidedReason: 'Duplicate draft; cancelling.' });
  });
});
