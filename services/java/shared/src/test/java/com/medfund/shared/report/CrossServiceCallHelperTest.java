package com.medfund.shared.report;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour contract for {@link CrossServiceCallHelper}. Guards the three
 * axes the Phase 3+ cross-service reports rely on: the happy path
 * pass-through, the retry-then-fallback path, and the warnings-capture
 * side effect that populates the report envelope's warnings list.
 */
class CrossServiceCallHelperTest {

    @Test
    void guarded_passesThroughOnSuccess() {
        List<String> warnings = new ArrayList<>();
        Mono<String> guarded = CrossServiceCallHelper.guarded(
                "billing-aggregate", Mono.just("ok"), "fallback", warnings);

        StepVerifier.create(guarded)
                .expectNext("ok")
                .verifyComplete();

        assertThat(warnings).isEmpty();
    }

    @Test
    void guarded_returnsFallbackAndWarnsWhenCallFails() {
        List<String> warnings = new ArrayList<>();
        Mono<String> guarded = CrossServiceCallHelper.guarded(
                "receipts-aggregate",
                Mono.error(new RuntimeException("peer down")),
                "empty-fallback",
                warnings);

        StepVerifier.create(guarded)
                .expectNext("empty-fallback")
                .verifyComplete();

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("receipts-aggregate")
                .contains("peer down");
    }

    @Test
    void guarded_retriesOnceBeforeFallingBack() {
        List<String> warnings = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> unstable = Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new RuntimeException("flake #" + attempts.get()));
        });

        Mono<String> guarded = CrossServiceCallHelper.guarded(
                "flaky-call", unstable, "fallback", warnings,
                Duration.ofSeconds(2), 1, Duration.ofMillis(1));

        StepVerifier.create(guarded)
                .expectNext("fallback")
                .verifyComplete();

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(warnings).hasSize(1);
    }

    @Test
    void guarded_capturesTimeoutInWarnings() {
        List<String> warnings = new ArrayList<>();
        Mono<String> tooSlow = Mono.never();

        Mono<String> guarded = CrossServiceCallHelper.guarded(
                "slow-peer", tooSlow, "fallback", warnings,
                Duration.ofMillis(50), 0, Duration.ofMillis(1));

        StepVerifier.create(guarded)
                .expectNext("fallback")
                .verifyComplete();

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("slow-peer");
    }

    @Test
    void guarded_tolerantOfNullWarningsList() {
        Mono<String> guarded = CrossServiceCallHelper.guarded(
                "unmonitored", Mono.error(new RuntimeException("boom")), "fb", null);
        StepVerifier.create(guarded).expectNext("fb").verifyComplete();
    }
}
