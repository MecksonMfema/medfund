import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Observable, Subscription, interval, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ApiService } from './api.service';

/**
 * Polling cadence for the bell dropdown. 30s matches the wizard's
 * inflight-job poll loop — short enough to feel live, long enough to
 * keep the request volume under 2/min/user.
 */
const POLL_INTERVAL_MS = 30_000;

/**
 * Wire shape of {@code GET /api/v1/notifications}. Extensible per the
 * server contract in {@link com.medfund.shared.notification.NotificationSummary} —
 * new producers add new {@code kind}s without breaking the client because
 * the bell template renders unknown kinds with a generic icon.
 */
export interface UserNotification {
  id: string;
  kind: string;
  title: string;
  body: string | null;
  severity: 'info' | 'success' | 'warning' | 'error' | string;
  sourceType: string | null;
  sourceId: string | null;
  actionUrl: string | null;
  tenantId: string | null;
  createdAt: string;
  seen: boolean;
}

/**
 * Reads the persistent {@code public.notifications} table via the shared
 * NotificationsController. One BehaviorSubject fans out to every
 * subscriber (header bell, tenant-layout bell) so the app never opens
 * more than one poll timer. Server owns the seen state — no localStorage,
 * survives cache clear and works cross-device.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService implements OnDestroy {
  private readonly list$ = new BehaviorSubject<UserNotification[]>([]);
  private readonly unseenCount$ = new BehaviorSubject<number>(0);
  private poll?: Subscription;

  constructor(private api: ApiService) {}

  /** Stream of the caller's most-recent notifications. */
  get list(): Observable<UserNotification[]> { return this.list$.asObservable(); }

  /** Count of unseen notifications — drives the bell badge. */
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
   *  so the bell reflects the new row without waiting 30s. */
  refresh(): void {
    this.api.get<UserNotification[]>('/notifications', { limit: '20' })
      .pipe(catchError(() => of<UserNotification[]>([])))
      .subscribe(rows => {
        const list = rows ?? [];
        this.list$.next(list);
        this.unseenCount$.next(list.filter(n => !n.seen).length);
      });
  }

  /**
   * Persist "seen" server-side and refresh so future bell opens reflect
   * the change from any device the user is logged into. The response
   * carries the count of rows updated but we don't need to look at it —
   * refresh() re-syncs both list and badge from ground truth.
   */
  markAllSeen(): void {
    this.api.post<{ marked: number }>('/notifications/mark-all-seen', {})
      .pipe(catchError(() => of({ marked: 0 })))
      .subscribe(() => this.refresh());
  }

  /** Mark a single notification read — used when a row is clicked. */
  markSeen(id: string): void {
    this.api.post<{ marked: number }>(`/notifications/${id}/mark-seen`, {})
      .pipe(catchError(() => of({ marked: 0 })))
      .subscribe(() => this.refresh());
  }

  ngOnDestroy(): void {
    this.stop();
  }
}
