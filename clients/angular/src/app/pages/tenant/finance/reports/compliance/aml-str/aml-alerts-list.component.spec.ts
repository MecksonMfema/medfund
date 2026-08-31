import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { AmlAlertsListComponent } from './aml-alerts-list.component';
import { PermissionService } from '../../../../../../core/security/permission.service';
import { AmlAlertResponse } from '../../../../../../core/services/aml-alert.service';
import { environment } from '../../../../../../../environments/environment';

/**
 * Component tests for the Phase 23 REG8 AML alert list. Covers:
 * - initial load of the RAISED+REVIEWED slice (default filter)
 * - status filter change re-issues the request with ?status=…
 * - raise submit hits POST + reloads
 * - workflow submit dispatches correct HTTP verb per mode
 * - permission-gated action buttons hide when the caller lacks the perm
 * - row-level status guard: Review only on RAISED, File only on REVIEWED,
 *   Close only on RAISED|REVIEWED
 */
describe('AmlAlertsListComponent', () => {
  let fixture: ComponentFixture<AmlAlertsListComponent>;
  let component: AmlAlertsListComponent;
  let http: HttpTestingController;

  const base = `${environment.apiBaseUrl}/regulatory/aml/alerts`;

  let permissionServiceStub: { has: (key: string) => boolean; grants: Set<string> };

  beforeEach(async () => {
    // Fresh stub per test — one of the specs flips the grant set.
    permissionServiceStub = {
      grants: new Set(['compliance:aml_raise', 'compliance:aml_review',
                       'compliance:aml_file', 'compliance:aml_close']),
      has(key: string) { return this.grants.has(key); },
    };

    await TestBed.configureTestingModule({
      imports: [AmlAlertsListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PermissionService, useValue: permissionServiceStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AmlAlertsListComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  // ── Loading ─────────────────────────────────────────────────────────

  it('loads the default active-work slice (no status filter) on init', () => {
    fixture.detectChanges();

    const req = http.expectOne(`${base}?page=0&size=50`);
    expect(req.request.method).toBe('GET');
    req.flush({ content: [stub('id-1', 'RAISED')], total: 1, page: 0, size: 50, totalPages: 1 });

    expect(component.rows.length).toBe(1);
    expect(component.rows[0].status).toBe('RAISED');
    expect(component.total).toBe(1);
    expect(component.loading).toBe(false);
  });

  it('re-loads with ?status=FILED when the status filter changes', () => {
    fixture.detectChanges();
    http.expectOne(`${base}?page=0&size=50`).flush(emptyPage(50));

    component.onStatusChange('FILED');

    const req = http.expectOne(`${base}?page=0&size=50&status=FILED`);
    expect(req.request.method).toBe('GET');
    req.flush(emptyPage(50));
    expect(component.statusFilter).toBe('FILED');
    expect(component.page).toBe(0);
  });

  it('surfaces the server error message when the load fails', () => {
    fixture.detectChanges();
    const req = http.expectOne(`${base}?page=0&size=50`);
    req.flush({ detail: 'kaboom' }, { status: 500, statusText: 'Server Error' });

    expect(component.loadError).toBe('kaboom');
    expect(component.loading).toBe(false);
  });

  // ── Raise ───────────────────────────────────────────────────────────

  it('submitRaise POSTs the payload and reloads the queue', () => {
    fixture.detectChanges();
    http.expectOne(`${base}?page=0&size=50`).flush(emptyPage(50));

    component.openRaise();
    expect(component.raiseOpen).toBe(true);

    const payload = {
      transactionRef: 'TXN-2026-000001',
      transactionType: 'PREMIUM' as const,
      amountNative: '15000.00',
      currency: 'USD',
      memberId: null,
      providerId: null,
      description: 'Large cash premium raised for triage review by compliance.',
    };
    component.submitRaise(payload);

    const post = http.expectOne(base);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual(payload);
    post.flush(stub('id-new', 'RAISED'));

    // Modal closes and the queue is reloaded.
    expect(component.raiseOpen).toBe(false);
    http.expectOne(`${base}?page=0&size=50`).flush(emptyPage(50));
  });

  it('submitRaise surfaces the server error and keeps the modal open on 400', () => {
    fixture.detectChanges();
    http.expectOne(`${base}?page=0&size=50`).flush(emptyPage(50));

    component.openRaise();
    component.submitRaise({
      transactionRef: 'x',
      transactionType: 'OTHER',
      amountNative: '1',
      currency: 'XYZ',
      description: 'short',
    });

    const post = http.expectOne(base);
    post.flush({ detail: 'description too short' }, { status: 400, statusText: 'Bad Request' });

    expect(component.raiseError).toBe('description too short');
    expect(component.raiseOpen).toBe(true);
  });

  // ── Workflow transitions ────────────────────────────────────────────

  it('submitWorkflow(review) PUTs to /{id}/review and reloads', () => {
    const row = stub('id-2', 'RAISED');
    driveWorkflow(row, 'review', { mode: 'review', reviewNote: 'Confirmed suspicious activity' });

    const put = http.expectOne(`${base}/id-2/review`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ reviewNote: 'Confirmed suspicious activity' });
    put.flush(stub('id-2', 'REVIEWED'));

    expect(component.workflowOpen).toBe(false);
    http.expectOne(`${base}?page=0&size=50`).flush(emptyPage(50));
  });

  it('submitWorkflow(file) PUTs to /{id}/file with the filing reference', () => {
    const row = stub('id-3', 'REVIEWED');
    driveWorkflow(row, 'file', {
      mode: 'file',
      filedRef: 'FIU-STR-2026-0001234',
      filedXlsxRef: null,
    });

    const put = http.expectOne(`${base}/id-3/file`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({
      filedRef: 'FIU-STR-2026-0001234',
      filedXlsxRef: null,
    });
    put.flush(stub('id-3', 'FILED'));

    http.expectOne(`${base}?page=0&size=50`).flush(emptyPage(50));
  });

  it('submitWorkflow(close) POSTs to /{id}/close with the reason', () => {
    const row = stub('id-4', 'REVIEWED');
    driveWorkflow(row, 'close', {
      mode: 'close',
      closedReason: 'Not reportable — duplicate',
    });

    const post = http.expectOne(`${base}/id-4/close`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ closedReason: 'Not reportable — duplicate' });
    post.flush(stub('id-4', 'CLOSED'));

    http.expectOne(`${base}?page=0&size=50`).flush(emptyPage(50));
  });

  it('submitWorkflow surfaces the server error and keeps the modal open on 409', () => {
    const row = stub('id-5', 'FILED');
    driveWorkflow(row, 'close', { mode: 'close', closedReason: 'Too late' });

    const post = http.expectOne(`${base}/id-5/close`);
    post.flush(
      { detail: 'Cannot close a FILED alert' },
      { status: 409, statusText: 'Conflict' },
    );

    expect(component.workflowError).toBe('Cannot close a FILED alert');
    expect(component.workflowOpen).toBe(true);
  });

  // ── Row-level status + permission guards ────────────────────────────

  it('canRow enforces workflow status guards', () => {
    const raised = stub('r', 'RAISED');
    const reviewed = stub('v', 'REVIEWED');
    const filed = stub('f', 'FILED');

    expect(component.canRow(raised,   'review')).toBe(true);
    expect(component.canRow(reviewed, 'review')).toBe(false);
    expect(component.canRow(filed,    'review')).toBe(false);

    expect(component.canRow(raised,   'file')).toBe(false);
    expect(component.canRow(reviewed, 'file')).toBe(true);
    expect(component.canRow(filed,    'file')).toBe(false);

    expect(component.canRow(raised,   'close')).toBe(true);
    expect(component.canRow(reviewed, 'close')).toBe(true);
    expect(component.canRow(filed,    'close')).toBe(false);
  });

  it('canRow enforces the compliance permission requirement per transition', () => {
    permissionServiceStub.grants = new Set(['compliance:aml_review']);   // review-only role
    const row = stub('id-1', 'REVIEWED');

    expect(component.canRow(row, 'file')).toBe(false);   // needs aml_file
    expect(component.canRow(row, 'close')).toBe(false);  // needs aml_close
  });

  it('canRaise reflects the aml_raise permission grant', () => {
    permissionServiceStub.grants = new Set(['compliance:aml_review']);
    expect(component.canRaise).toBe(false);

    permissionServiceStub.grants = new Set(['compliance:aml_review', 'compliance:aml_raise']);
    expect(component.canRaise).toBe(true);
  });

  // ── Helpers ─────────────────────────────────────────────────────────

  function stub(id: string, status: AmlAlertResponse['status']): AmlAlertResponse {
    return {
      id,
      status,
      transactionRef: 'TXN-' + id,
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

  function emptyPage(size: number) {
    return { content: [], total: 0, page: 0, size, totalPages: 1 };
  }

  function driveWorkflow(
    row: AmlAlertResponse,
    mode: 'review' | 'file' | 'close',
    payload: { mode: 'review' | 'file' | 'close'; reviewNote?: string; filedRef?: string; filedXlsxRef?: string | null; closedReason?: string },
  ): void {
    fixture.detectChanges();
    http.expectOne(`${base}?page=0&size=50`).flush(emptyPage(50));
    component.openWorkflow(row, mode);
    expect(component.workflowOpen).toBe(true);
    component.submitWorkflow(payload);
  }
});
