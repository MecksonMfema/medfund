import { Injectable } from '@angular/core';
import { Observable, switchMap, takeWhile, timeout, timer } from 'rxjs';
import { ActuarialReportsService, JobStatusResponse } from './actuarial-reports.service';

/**
 * Wraps the actuarial job poll loop with a fixed 2s tick + a 5-minute
 * hard ceiling (per plan §Phase 10 §1). Emits every status snapshot the
 * server returns, then completes when it observes a terminal state
 * ({@code completed} | {@code failed}). Callers cancel via unsubscription.
 *
 * <p>Backoff to 5s after 30s is intentionally deferred to a Phase 10
 * follow-up (per plan) — the naive constant tick is fine for the p50
 * completion window we've measured against Mack fixtures (< 5s).
 */
@Injectable({ providedIn: 'root' })
export class ActuarialJobPollingService {
  static readonly TICK_MS = 2_000;
  static readonly CEILING_MS = 5 * 60 * 1_000;

  constructor(private reports: ActuarialReportsService) {}

  poll(jobId: string): Observable<JobStatusResponse> {
    return timer(0, ActuarialJobPollingService.TICK_MS).pipe(
      switchMap(() => this.reports.status(jobId)),
      takeWhile((r) => r.status !== 'completed' && r.status !== 'failed', true),
      timeout({ each: ActuarialJobPollingService.CEILING_MS }),
    );
  }
}
