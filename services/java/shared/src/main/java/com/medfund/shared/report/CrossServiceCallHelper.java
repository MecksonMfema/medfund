package com.medfund.shared.report;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * Encapsulates the {@code .timeout(...) + .retry(...) + .onErrorResume(...)}
 * pattern that every cross-service report call uses per G37. Failures are
 * captured on the composing envelope's {@code warnings} list — the caller
 * appends a human-readable line and the report still succeeds with partial
 * data, matching the "best-effort with warnings" spirit of G28.
 *
 * <p>Deliberately not a Spring {@code @Component} — no state, no injection.
 * Static entry points keep the fluent WebClient chain readable at the call
 * site.
 *
 * <p>Typical use inside a service:
 *
 * <pre>{@code
 * List<String> warnings = new ArrayList<>();
 * Mono<List<MonthlyAggregateRow>> billing = CrossServiceCallHelper.guarded(
 *         "billing-aggregate-monthly",
 *         contributionsClient.aggregateBillingMonthly(periodStart, periodEnd),
 *         List.<MonthlyAggregateRow>of(),
 *         warnings);
 * }</pre>
 *
 * <p>Do NOT introduce Resilience4j to this pattern without a platform-wide
 * grill (see plan invariant #7 + G37).
 */
@Slf4j
public final class CrossServiceCallHelper {

    /** Default per-hop ceiling — 2 seconds per plan §Performance + G37. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    /** One retry attempt; peer downtime should not stretch the report page load. */
    public static final int DEFAULT_MAX_RETRIES = 1;

    /** Small backoff between attempts so a transient hiccup gets a fresh dial. */
    public static final Duration DEFAULT_BACKOFF = Duration.ofMillis(250);

    private CrossServiceCallHelper() {}

    /**
     * Wraps {@code call} in timeout + retry + fallback, appending a human
     * warning to {@code warnings} on failure and returning {@code fallback}.
     * Uses the default 2-second per-hop ceiling + 1 retry attempt.
     *
     * @param callName  short identifier for the warning message (e.g.
     *                  {@code "billing-aggregate-monthly"})
     * @param call      the cross-service {@link Mono} to guard
     * @param fallback  what to substitute when {@code call} fails after
     *                  retries — typically an empty list so the composing
     *                  report can still render
     * @param warnings  mutable list the caller passes through to the
     *                  envelope; a failure appends {@code "callName call
     *                  failed: <reason>"}
     */
    public static <T> Mono<T> guarded(String callName,
                                      Mono<T> call,
                                      T fallback,
                                      List<String> warnings) {
        return guarded(callName, call, fallback, warnings,
                DEFAULT_TIMEOUT, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF);
    }

    /**
     * Full-parameter variant for tests + special-case callers (e.g. an
     * actuarial call that legitimately needs a longer ceiling).
     */
    public static <T> Mono<T> guarded(String callName,
                                      Mono<T> call,
                                      T fallback,
                                      List<String> warnings,
                                      Duration perHopTimeout,
                                      int maxRetries,
                                      Duration backoff) {
        Mono<T> guarded = call.timeout(perHopTimeout);
        if (maxRetries > 0) {
            guarded = guarded.retryWhen(Retry.backoff(maxRetries, backoff)
                    .filter(err -> !(err instanceof IllegalArgumentException)));
        }
        return guarded.onErrorResume(err -> {
            // Retry.backoff wraps in RetryExhaustedException — unwrap so the
            // warning names the actual peer failure, not the retry envelope.
            Throwable cause = err.getCause() != null ? err.getCause() : err;
            String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            log.warn("[cross-service] {} call failed: {}", callName, reason);
            if (warnings != null) {
                warnings.add(callName + " call failed: " + reason);
            }
            return Mono.just(fallback);
        });
    }
}
