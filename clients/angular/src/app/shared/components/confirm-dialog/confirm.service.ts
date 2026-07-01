import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

/**
 * Options for a confirmation dialog. Mirrors the shape of a browser
 * {@code confirm()} call plus platform styling knobs (title, danger flag).
 *
 * <p>Destructive actions (delete, revoke, void) should always set
 * {@code danger: true} so the confirm button renders red — it's the
 * strongest signal a user gets that this can't be undone with a click.
 */
export interface ConfirmOptions {
  title: string;
  /** Long-form explanation shown under the title. Optional. */
  message?: string;
  /** Label for the confirm (right) button. Defaults to "Confirm". */
  confirmLabel?: string;
  /** Label for the cancel (left) button. Defaults to "Cancel". */
  cancelLabel?: string;
  /** Renders the confirm button as `btn-danger`. Default false. */
  danger?: boolean;
}

/** Internal — associates an open dialog with its pending Promise. */
interface ActiveRequest {
  options: Required<ConfirmOptions>;
  resolve: (result: boolean) => void;
}

/**
 * Drop-in replacement for the browser's `confirm()` — returns a
 * Promise&lt;boolean&gt; that resolves to true when the user confirms
 * and false when they cancel or dismiss. One dialog at a time; a
 * second `ask()` before the first is answered short-circuits the first
 * to false (matches native semantics — a new prompt cancels the old).
 *
 * <p>The single {@link ConfirmDialogComponent} (mounted in each layout)
 * subscribes to {@link #current$} and renders whichever request is
 * currently active.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly current$$ = new BehaviorSubject<ActiveRequest | null>(null);
  readonly current$: Observable<ActiveRequest | null> = this.current$$.asObservable();

  ask(options: ConfirmOptions): Promise<boolean> {
    // If a previous dialog is still open, resolve it as cancelled so
    // callers aren't left with a dangling Promise.
    const prev = this.current$$.value;
    if (prev) prev.resolve(false);

    return new Promise<boolean>(resolve => {
      const req: ActiveRequest = {
        options: {
          title: options.title,
          message: options.message ?? '',
          confirmLabel: options.confirmLabel ?? 'Confirm',
          cancelLabel: options.cancelLabel ?? 'Cancel',
          danger: options.danger ?? false,
        },
        resolve,
      };
      this.current$$.next(req);
    });
  }

  /** Called by the dialog UI when the user clicks confirm or cancel. */
  respond(result: boolean): void {
    const req = this.current$$.value;
    if (!req) return;
    req.resolve(result);
    this.current$$.next(null);
  }
}
