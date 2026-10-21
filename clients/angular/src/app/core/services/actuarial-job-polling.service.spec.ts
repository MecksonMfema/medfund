import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ActuarialJobPollingService } from './actuarial-job-polling.service';
import { JobStatusResponse } from './actuarial-reports.service';
import { environment } from '../../../environments/environment';

describe('ActuarialJobPollingService', () => {
  let service: ActuarialJobPollingService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  const snapshot = (status: JobStatusResponse['status'], pct: number): JobStatusResponse => ({
    jobId: 'abc', reportKey: 'IBNR_TRIANGLE', status, progressPct: pct,
    paramsJson: null, resultJson: null, errorMessage: null,
    requestedAt: '2026-08-27T00:00:00Z',
    completedAt: status === 'completed' || status === 'failed'
      ? '2026-08-27T00:00:05Z' : null,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ActuarialJobPollingService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('ticks every 2s until it observes a terminal state', fakeAsync(() => {
    const seen: JobStatusResponse[] = [];
    let done = false;
    service.poll('abc').subscribe({
      next: (r) => seen.push(r),
      complete: () => (done = true),
    });

    // timer(0, TICK) schedules the first emission via setTimeout(0), so
    // advance the fake clock by 0 before the request registers.
    tick(0);
    http.expectOne(`${baseUrl}/reports/jobs/abc`).flush(snapshot('processing', 50));
    // Second tick still processing
    tick(ActuarialJobPollingService.TICK_MS);
    http.expectOne(`${baseUrl}/reports/jobs/abc`).flush(snapshot('processing', 50));
    // Third tick returns completed — takeWhile(inclusive) emits + completes
    tick(ActuarialJobPollingService.TICK_MS);
    http.expectOne(`${baseUrl}/reports/jobs/abc`).flush(snapshot('completed', 100));

    expect(seen.length).toBe(3);
    expect(seen[seen.length - 1].status).toBe('completed');
    expect(done).toBeTrue();
  }));

  it('completes on failed as well as completed', fakeAsync(() => {
    let done = false;
    service.poll('abc').subscribe({ complete: () => (done = true) });
    tick(0);
    http.expectOne(`${baseUrl}/reports/jobs/abc`).flush(snapshot('failed', 100));
    expect(done).toBeTrue();
  }));
});
