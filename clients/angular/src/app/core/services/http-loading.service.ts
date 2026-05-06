import { Injectable, signal, computed } from '@angular/core';

/**
 * Tracks the number of in-flight HTTP requests and exposes a signal that
 * any UI element can subscribe to in order to show a loading indicator.
 *
 * The interceptor calls {@link begin} when a request fires and {@link end}
 * once it completes (success or error). The count is used instead of a
 * boolean so concurrent requests don't race each other off too early.
 */
@Injectable({ providedIn: 'root' })
export class HttpLoadingService {
  private readonly count = signal(0);

  /** True while at least one HTTP request is pending. */
  readonly isLoading = computed(() => this.count() > 0);

  /** Number of pending requests — useful for debug overlays. */
  readonly activeRequests = computed(() => this.count());

  begin(): void {
    this.count.update(c => c + 1);
  }

  end(): void {
    this.count.update(c => Math.max(0, c - 1));
  }
}
