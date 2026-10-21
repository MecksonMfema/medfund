import { Injectable } from '@angular/core';
import { Observable, switchMap, takeWhile, timeout, timer } from 'rxjs';
import { ApiService } from './api.service';
import { JobStatusResponse } from './actuarial-reports.service';

// Re-export so callers on new report pages (Phase 15 §21 IFRS 17) can
// import the polling shape from a single source without reaching back
// into actuarial-reports.service — the type is agnostic to which
// report key produced the job.
export type { JobStatusResponse } from './actuarial-reports.service';

/**
 * Canonical report-job polling service (Phase 15 §1 rename — I10 + I22).
 * Hits {@code /api/v1/reports/jobs/{jobId}}, the report-key-agnostic status
 * endpoint that serves both Phase-14 actuarial jobs and Phase-15 IFRS 17
 * jobs from a single URL shape.
 *
 * <p>Wraps the job poll loop with a fixed 2s tick + a 5-minute hard ceiling
 * (per Phase 14 §Phase 10 §1). Emits every status snapshot the server
 * returns, then completes when it observes a terminal state
 * ({@code completed} | {@code failed}). Callers cancel via unsubscription.
 *
 * <p>Backoff to 5s after 30s is intentionally deferred (per Phase 14 plan) —
 * the naive constant tick is fine for the p50 completion window observed
 * against Mack fixtures (< 5s). The IFRS 17 chunked flow (§18 aggregator)
 * may motivate the backoff — deferred until measured.
 */
@Injectable({ providedIn: 'root' })
export class ReportJobPollingService {
  static readonly TICK_MS = 2_000;
  static readonly CEILING_MS = 5 * 60 * 1_000;

  constructor(protected api: ApiService) {}

  poll(jobId: string): Observable<JobStatusResponse> {
    return timer(0, ReportJobPollingService.TICK_MS).pipe(
      switchMap(() => this.status(jobId)),
      takeWhile((r) => r.status !== 'completed' && r.status !== 'failed', true),
      timeout({ each: ReportJobPollingService.CEILING_MS }),
    );
  }

  status(jobId: string): Observable<JobStatusResponse> {
    return this.api.get<JobStatusResponse>(`/reports/jobs/${jobId}`);
  }
}
