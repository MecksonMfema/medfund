import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export type ToastKind = 'success' | 'error' | 'info' | 'warning';

export interface Toast {
  id: string;
  message: string;
  kind: ToastKind;
}

/**
 * Lightweight toast service. Components inject this and call success() /
 * error() / info() / warning() to push a transient banner that auto-dismisses
 * after {@code defaultDurationMs} (or whatever override is passed). The
 * single {@link AppToastContainerComponent} (mounted in the layouts)
 * subscribes to {@link #toasts$} and renders the stack.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly toasts$$ = new BehaviorSubject<ReadonlyArray<Toast>>([]);
  readonly toasts$: Observable<ReadonlyArray<Toast>> = this.toasts$$.asObservable();

  /** Default auto-dismiss timings — errors linger a bit longer. */
  private readonly defaultDurations: Record<ToastKind, number> = {
    success: 4000,
    info:    4000,
    warning: 5000,
    error:   6000,
  };

  success(message: string, durationMs?: number): string { return this.show(message, 'success', durationMs); }
  error(message: string,   durationMs?: number): string { return this.show(message, 'error',   durationMs); }
  info(message: string,    durationMs?: number): string { return this.show(message, 'info',    durationMs); }
  warning(message: string, durationMs?: number): string { return this.show(message, 'warning', durationMs); }

  /** Imperatively remove a toast by id. */
  dismiss(id: string): void {
    this.toasts$$.next(this.toasts$$.value.filter(t => t.id !== id));
  }

  /** Clears every visible toast. Handy for tests and on logout. */
  clear(): void {
    this.toasts$$.next([]);
  }

  private show(message: string, kind: ToastKind, durationMs?: number): string {
    const id = this.uuid();
    this.toasts$$.next([...this.toasts$$.value, { id, message, kind }]);
    const ttl = durationMs ?? this.defaultDurations[kind];
    if (ttl > 0) {
      // setTimeout is fine outside a zone for our purposes — auto-dismiss
      // doesn't need to be batched into change detection.
      setTimeout(() => this.dismiss(id), ttl);
    }
    return id;
  }

  /** crypto.randomUUID is widely available; fall back for very old browsers. */
  private uuid(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 9)}`;
  }
}
