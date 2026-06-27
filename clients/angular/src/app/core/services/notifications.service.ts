import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Observable, Subscription, interval, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { ApiService } from './api.service';
import { ScheduledJobRun } from './admin.service';

/**
 * Last-seen timestamp the user has acknowledged. Anything that landed
 * after this (i.e. ended_at > lastSeenAt) is counted in the bell badge.
 */
const LAST_SEEN_KEY = 'medfund_notifications_last_seen';

/**
 * Polling cadence for the bell dropdown. 30s matches the wizard's
 * inflight-job poll loop — short enough to feel live, long enough to
 * keep the request volume under 2/min/user.
 */
const POLL_INTERVAL_MS = 30_000;

/**
 * NotificationsService runs a single shared polling loop against
 * /api/v1/scheduled-jobs/runs/recent-for-me and exposes the result as
 * a BehaviorSubject. Multiple components (header bell, wizard,
 * dashboard) can subscribe without each opening their own timer.
 *
 * Lifecycle: start() is called once from HeaderComponent.ngOnInit; the
 * service stays alive for the app's lifetime. stop() is wired to
 * destruction for symmetry but in practice never fires until logout.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService implements OnDestroy {
  private readonly runs$ = new BehaviorSubject<ScheduledJobRun[]>([]);
  private readonly unseenCount$ = new BehaviorSubject<number>(0);
  private poll?: Subscription;

  constructor(private api: ApiService) {}

  /** Stream of recent + in-flight runs the current user triggered. */
  get list(): Observable<ScheduledJobRun[]> { return this.runs$.asObservable(); }

  /** Count of completed runs the user hasn't acknowledged yet. Drives the bell badge. */
  get badge(): Observable<number> { return this.unseenCount$.asObservable(); }

  /** Begin polling. Idempotent — start twice and the second call is a no-op. */
  start(): void {
    if (this.poll) return;
    // Fire immediately on start so the bell isn't empty on first paint,
    // then every POLL_INTERVAL_MS thereafter.
    this.refresh();
    this.poll = interval(POLL_INTERVAL_MS).subscribe(() => this.refresh());
  }

  /** Stop polling. */
  stop(): void {
    this.poll?.unsubscribe();
    this.poll = undefined;
  }

  /** Force an immediate refresh — used after the user triggers a job
   *  so the bell reflects the new RUNNING row without waiting 30s. */
  refresh(): void {
    this.api.get<ScheduledJobRun[]>('/scheduled-jobs/runs/recent-for-me', { limit: '20' })
      .pipe(catchError(() => of<ScheduledJobRun[]>([])))
      .subscribe(rows => {
        this.runs$.next(rows ?? []);
        this.unseenCount$.next(this.computeUnseen(rows ?? []));
      });
  }

  /** Mark every currently-completed run as seen. Anything that
   *  completes after this call shows in the badge again. */
  markAllSeen(): void {
    try {
      localStorage.setItem(LAST_SEEN_KEY, new Date().toISOString());
    } catch { /* private mode — degrade silently */ }
    this.unseenCount$.next(0);
  }

  ngOnDestroy(): void {
    this.stop();
  }

  private computeUnseen(rows: ScheduledJobRun[]): number {
    const lastSeen = this.readLastSeen();
    return rows.filter(r => {
      if (r.status === 'RUNNING') return false;
      if (!r.endedAt) return false;
      return new Date(r.endedAt).getTime() > lastSeen;
    }).length;
  }

  private readLastSeen(): number {
    try {
      const raw = localStorage.getItem(LAST_SEEN_KEY);
      if (!raw) return 0;
      const ts = new Date(raw).getTime();
      return Number.isFinite(ts) ? ts : 0;
    } catch {
      return 0;
    }
  }
}
