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
