import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { MembersService } from './members.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for MembersService. Currently pins the V046
 * dependant deactivation endpoint. The frontend prompt allows an
 * empty answer (→ null effectiveDate on the service call → empty
 * body → server defaults to today), so both shapes need to work.
 *
 * <p>URL rename here would leave the Terminate button in the
 * dependant list looking correct while silently failing every
 * call — silent enough that a smoke test would miss it. Explicit
 * URL + method assertion guards the seam.
 */
describe('MembersService (V046 dependant deactivation)', () => {
  let service: MembersService;
  let http: HttpTestingController;

  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MembersService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('POSTs /dependants/{id}/deactivate with { effectiveDate } when a date is provided', () => {
    service.deactivateDependant('d-1', '2026-09-15').subscribe();

    const req = http.expectOne(
      `${baseUrl}/dependants/d-1/deactivate`,
    );
    expect(req.request.method).toBe('POST');
    // Body carries the operator-picked ISO date verbatim — the backend
    // expects the key exactly as spelled.
    expect(req.request.body).toEqual({ effectiveDate: '2026-09-15' });
    req.flush({ id: 'd-1', status: 'deactivated' });
  });

  it('POSTs /dependants/{id}/deactivate with an EMPTY body when effectiveDate is null', () => {
    // Null → empty body so the backend's "default to today" branch
    // fires. Regression: if this ever silently sent {effectiveDate: null}
    // Jackson would still accept it but the intent is that the client
    // NOT commit to a date when the operator didn't pick one.
    service.deactivateDependant('d-2', null).subscribe();

    const req = http.expectOne(
      `${baseUrl}/dependants/d-2/deactivate`,
    );
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({ id: 'd-2', status: 'deactivated' });
  });
});

/**
 * V048 wire-shape guard for the two new mid-life operations.
 * Rename or restructure of these endpoints would silently break the
 * change-group and swap-dependant flows.
 */
describe('MembersService (V048 group change + swap)', () => {
  let service: MembersService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MembersService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('POSTs /members/{id}/group-changes with the full payload', () => {
    service.requestGroupChange('m-1', {
      targetGroupId: 'g-99',
      effectiveDate: '2026-09-01',
      reason: 'moving offices'
    }).subscribe();

    const req = http.expectOne(`${baseUrl}/members/m-1/group-changes`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      targetGroupId: 'g-99',
      effectiveDate: '2026-09-01',
      reason: 'moving offices'
    });
    req.flush({
      id: 'gc-1', memberId: 'm-1', fromGroupId: 'g-1', toGroupId: 'g-99',
      status: 'PENDING', requestedDate: '2026-07-06', effectiveDate: '2026-09-01',
      reason: 'moving offices', backdated: false
    });
  });

  it('POSTs /members/{id}/group-changes/{id}/approve with an empty body', () => {
    // Approve is a state transition — no body needed. Regression would
    // send `undefined` which the HTTP client turns into an empty string
    // and the backend rejects.
    service.approveGroupChange('m-1', 'gc-1').subscribe();

    const req = http.expectOne(`${baseUrl}/members/m-1/group-changes/gc-1/approve`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({ id: 'gc-1', memberId: 'm-1', status: 'APPROVED' });
  });

  it('POSTs /members/{id}/group-changes/{id}/reject with a reason body', () => {
    service.rejectGroupChange('m-1', 'gc-1', 'duplicate').subscribe();

    const req = http.expectOne(`${baseUrl}/members/m-1/group-changes/gc-1/reject`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'duplicate' });
    req.flush({ id: 'gc-1', memberId: 'm-1', status: 'REJECTED', rejectionReason: 'duplicate' });
  });

  it('POSTs /members/{id}/swaps with the swap payload', () => {
    service.requestSwap('m-1', {
      dependantId: 'd-5',
      effectiveDate: '2026-08-01',
      reason: 'spouse takes over'
    }).subscribe();

    const req = http.expectOne(`${baseUrl}/members/m-1/swaps`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      dependantId: 'd-5',
      effectiveDate: '2026-08-01',
      reason: 'spouse takes over'
    });
    req.flush({ id: 'sw-1', oldMemberId: 'm-1', dependantId: 'd-5',
      status: 'PENDING', requestedDate: '2026-07-06', effectiveDate: '2026-08-01' });
  });

  it('GETs the list endpoints for group-changes and swaps', () => {
    // Two lookups from the same call site: history section on the
    // member-detail page renders both lists. Guards against the
    // (all-too-common) copy-paste bug where one call hits the wrong URL.
    service.listGroupChanges('m-1').subscribe();
    service.listSwaps('m-1').subscribe();

    const gcReq = http.expectOne(`${baseUrl}/members/m-1/group-changes`);
    expect(gcReq.request.method).toBe('GET');
    gcReq.flush([]);

    const swReq = http.expectOne(`${baseUrl}/members/m-1/swaps`);
    expect(swReq.request.method).toBe('GET');
    swReq.flush([]);
  });
});
