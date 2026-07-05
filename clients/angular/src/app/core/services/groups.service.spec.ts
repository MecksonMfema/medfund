import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { GroupsService } from './groups.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for GroupsService. Currently pins the group
 * terminate endpoint — the URL, method, and body key. The Terminate
 * button in the group-detail page fires this call; a silent URL rename
 * or a body-key drift ({@code effectiveDate} → {@code effective_date})
 * would 400 in prod without any compile or test signal, so pin it
 * explicitly here.
 */
describe('GroupsService (terminate wire shape)', () => {
  let service: GroupsService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GroupsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('POSTs to /groups/{id}/actions/terminate with { effectiveDate, reason } when both are set', () => {
    service.terminate('g-1', '2026-09-15', 'Ceased trading').subscribe();

    const req = http.expectOne(`${baseUrl}/groups/g-1/actions/terminate`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      effectiveDate: '2026-09-15',
      reason: 'Ceased trading',
    });
    req.flush({ id: 'g-1', status: 'TERMINATED' });
  });

  it('omits reason when only effectiveDate is provided', () => {
    // Reason is optional — the audit trail still gets the effective
    // date. The body shape must not carry a null reason (that would
    // pass Jackson but fill audit rows with useless nulls).
    service.terminate('g-2', '2026-10-01', null).subscribe();

    const req = http.expectOne(`${baseUrl}/groups/g-2/actions/terminate`);
    expect(req.request.body).toEqual({ effectiveDate: '2026-10-01' });
    req.flush({ id: 'g-2', status: 'TERMINATED' });
  });

  it('sends an empty body when both effectiveDate and reason are null (server defaults to today)', () => {
    service.terminate('g-3', null, null).subscribe();

    const req = http.expectOne(`${baseUrl}/groups/g-3/actions/terminate`);
    expect(req.request.body).toEqual({});
    req.flush({ id: 'g-3', status: 'TERMINATED' });
  });
});
