import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ProducerService } from './producer.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link ProducerService}. Backend at
 * {@code /api/v1/producers/*}, {@code /api/v1/commission/rate-cards/*},
 * and {@code /api/v1/members/{id}/producer-assignment*}; the gateway
 * proxies all three prefixes to finance-service. A rename of any of
 * these URLs would silently break the producer tenant-admin surface.
 */
describe('ProducerService', () => {
  let service: ProducerService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProducerService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /producers with page + size + active filter', () => {
    service.listProducers(2, 25, true).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/producers`
      && r.params.get('page') === '2'
      && r.params.get('size') === '25'
      && r.params.get('active') === 'true');
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], total: 0, page: 2, size: 25, totalPages: 0 });
  });

  it('POST /producers with the create payload', () => {
    service.createProducer({
      producerCode: 'BRK-001',
      name: 'Test Broker',
      homeCurrency: 'USD',
    }).subscribe();
    const req = http.expectOne(`${baseUrl}/producers`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      producerCode: 'BRK-001',
      name: 'Test Broker',
      homeCurrency: 'USD',
    });
    req.flush({ id: 'p-1', producerCode: 'BRK-001', name: 'Test Broker',
                homeCurrency: 'USD', active: true });
  });

  it('PUT /producers/{id} on update', () => {
    service.updateProducer('p-1', {
      name: 'Renamed', homeCurrency: 'USD', active: false,
    }).subscribe();
    const req = http.expectOne(`${baseUrl}/producers/p-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      name: 'Renamed', homeCurrency: 'USD', active: false,
    });
    req.flush({});
  });

  it('GET /producers/{id}/ancestry returns array', () => {
    service.getProducerAncestry('p-1').subscribe(rows => {
      expect(rows.length).toBe(2);
    });
    const req = http.expectOne(`${baseUrl}/producers/p-1/ancestry`);
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'p-1' }, { id: 'p-parent' }]);
  });

  it('GET /producers/search with q and limit', () => {
    service.searchProducers('alpha', 15).subscribe();
    const req = http.expectOne(r => r.url === `${baseUrl}/producers/search`
      && r.params.get('q') === 'alpha'
      && r.params.get('limit') === '15');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /commission/rate-cards paginated', () => {
    service.listRateCards(0, 50, true).subscribe();
    const req = http.expectOne(r => r.url === `${baseUrl}/commission/rate-cards`
      && r.params.get('page') === '0'
      && r.params.get('size') === '50'
      && r.params.get('active') === 'true');
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], total: 0, page: 0, size: 50, totalPages: 0 });
  });

  it('POST /commission/rate-cards with payload', () => {
    service.createRateCard({
      name: 'Std HEALTH',
      insuranceLine: 'HEALTH',
      baseRatePct: 8,
      effectiveFrom: '2026-01-01',
    }).subscribe();
    const req = http.expectOne(`${baseUrl}/commission/rate-cards`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.name).toBe('Std HEALTH');
    req.flush({});
  });

  it('DELETE /commission/rate-cards/{id} deactivates', () => {
    service.deactivateRateCard('rc-1').subscribe();
    const req = http.expectOne(`${baseUrl}/commission/rate-cards/rc-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });

  it('GET /members/{id}/producer-assignment returns current', () => {
    service.currentAssignmentFor('m-1').subscribe();
    const req = http.expectOne(`${baseUrl}/members/m-1/producer-assignment`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('POST /members/{id}/producer-assignment with assign payload', () => {
    service.assignMember('m-1', {
      producerId: 'p-1',
      effectiveFrom: '2026-03-01',
      changeReason: 'onboarding',
    }).subscribe();
    const req = http.expectOne(`${baseUrl}/members/m-1/producer-assignment`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      producerId: 'p-1',
      effectiveFrom: '2026-03-01',
      changeReason: 'onboarding',
    });
    req.flush({});
  });

  it('GET /members/{id}/producer-assignment/history returns array', () => {
    service.assignmentHistoryFor('m-1').subscribe(rows => {
      expect(Array.isArray(rows)).toBe(true);
    });
    const req = http.expectOne(`${baseUrl}/members/m-1/producer-assignment/history`);
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'a-1' }]);
  });

  it('DELETE /members/{id}/producer-assignment closes current', () => {
    service.closeCurrentAssignment('m-1').subscribe();
    const req = http.expectOne(`${baseUrl}/members/m-1/producer-assignment`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('GET /producers/{id}/assignments/count returns number', () => {
    service.countOpenAssignments('p-1').subscribe(count => {
      expect(count).toBe(42);
    });
    const req = http.expectOne(`${baseUrl}/producers/p-1/assignments/count`);
    expect(req.request.method).toBe('GET');
    req.flush(42);
  });

  it('POST /producers/{id}/terminate with effectiveDate', () => {
    service.terminateProducer('p-1', { effectiveDate: '2026-08-31' }).subscribe();
    const req = http.expectOne(`${baseUrl}/producers/p-1/terminate`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ effectiveDate: '2026-08-31' });
    req.flush({ id: 'p-1', active: false });
  });

  it('POST /producers/{id}/reassign-bulk with newProducerId + memberIds + effectiveFrom', () => {
    service.bulkReassign('p-old', {
      newProducerId: 'p-new',
      memberIds: ['m-1', 'm-2', 'm-3'],
      effectiveFrom: '2026-09-01',
      changeReason: 'Successor for BRK-OLD',
    }).subscribe(rep => {
      expect(rep.total).toBe(3);
      expect(rep.succeeded).toBe(2);
      expect(rep.items[1].success).toBe(false);
    });
    const req = http.expectOne(`${baseUrl}/producers/p-old/reassign-bulk`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      newProducerId: 'p-new',
      memberIds: ['m-1', 'm-2', 'm-3'],
      effectiveFrom: '2026-09-01',
      changeReason: 'Successor for BRK-OLD',
    });
    req.flush({
      total: 3, succeeded: 2, failed: 1,
      items: [
        { memberId: 'm-1', success: true },
        { memberId: 'm-2', success: false, reason: 'deactivated producer' },
        { memberId: 'm-3', success: true },
      ],
    });
  });

  it('POST /producers/{id}/reassign-bulk allows null changeReason', () => {
    service.bulkReassign('p-old', {
      newProducerId: 'p-new',
      memberIds: ['m-1'],
      effectiveFrom: '2026-09-01',
      changeReason: null,
    }).subscribe();
    const req = http.expectOne(`${baseUrl}/producers/p-old/reassign-bulk`);
    expect(req.request.body.changeReason).toBeNull();
    req.flush({ total: 1, succeeded: 1, failed: 0, items: [{ memberId: 'm-1', success: true }] });
  });

  // ── Phase 10 backfill ────────────────────────────────────────────────

  it('POST /producers/backfill/run with an empty body', () => {
    service.runProducerBackfill().subscribe();
    const req = http.expectOne(`${baseUrl}/producers/backfill/run`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush(null);
  });

  it('GET /producers/backfill/progress returns idle shape', () => {
    service.getBackfillProgress().subscribe(p => {
      expect(p.running).toBeFalse();
      expect(p.processed).toBe(0);
    });
    const req = http.expectOne(`${baseUrl}/producers/backfill/progress`);
    expect(req.request.method).toBe('GET');
    req.flush({
      startedAt: null, completedAt: null,
      processed: 0, skipped: 0, autoAccepted: 0, pending: 0, failed: 0,
      running: false, errorMessage: null,
    });
  });

  it('GET /producers/backfill/candidates with page + size', () => {
    service.listBackfillCandidates(1, 25).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/producers/backfill/candidates`
      && r.params.get('page') === '1'
      && r.params.get('size') === '25');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /producers/backfill/candidates/pending-count', () => {
    service.countPendingBackfillCandidates().subscribe(n => expect(n).toBe(3));
    const req = http.expectOne(`${baseUrl}/producers/backfill/candidates/pending-count`);
    expect(req.request.method).toBe('GET');
    req.flush(3);
  });

  it('PUT /producers/backfill/candidates/{id}/accept', () => {
    service.acceptBackfillCandidate('c-1').subscribe();
    const req = http.expectOne(`${baseUrl}/producers/backfill/candidates/c-1/accept`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({});
    req.flush(null);
  });

  it('PUT /producers/backfill/candidates/{id}/reject', () => {
    service.rejectBackfillCandidate('c-1').subscribe();
    const req = http.expectOne(`${baseUrl}/producers/backfill/candidates/c-1/reject`);
    expect(req.request.method).toBe('PUT');
    req.flush(null);
  });
});
