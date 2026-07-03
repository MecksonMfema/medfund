package com.medfund.contributions.service;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterisation test for the {@code .cache()} on
 * {@link BillingService#revokeBilling}'s {@code targetInvoiceIds} Mono.
 *
 * <p><b>What broke:</b> The revoke pipeline enumerated invoice IDs, captured
 * their PDF pointers, deleted contributions, then deleted the invoices whose
 * IDs it had enumerated. Because {@code targetInvoiceIds} was a plain
 * {@code Mono<List<UUID>>} (cold), each downstream {@code .flatMap} that
 * referenced it re-ran the underlying SELECT. On the second re-run
 * contributions had already been deleted, so group invoices — which have
 * {@code scheme_id = NULL} and were matched only via the contributions-join
 * branch when a line filter was set — dropped out of the list. The invoice
 * survived the DELETE while its contributions and balance were reversed —
 * the exact bug that stranded {@code INV-476216} on 2026-07-02.
 *
 * <p><b>The fix:</b> {@code .cache()} on the ID enumeration Mono so both
 * downstream subscribers replay the same result set, regardless of what has
 * been mutated in the DB between subscribes.
 *
 * <p>This test doesn't boot Spring or Postgres — it verifies the Reactor
 * semantic directly with an {@link AtomicInteger}-backed producer. If a
 * future edit removes {@code .cache()} from {@code revokeBilling}, running
 * the sibling {@link #revokeStyleChain_withoutCache_secondSubscriberSeesEmpty}
 * expectation makes the regression pattern explicit; and the positive test
 * {@link #revokeStyleChain_withCache_bothSubscribersSeeSameResult} documents
 * the intended behaviour.
 *
 * <p>See project memory {@code bug_reactor_kafka_ack_swallow} for the sister
 * pattern (offset ack semantics) that surfaced the same weekend.
 */
class BillingRevokeCacheTest {

    @Test
    void revokeStyleChain_withCache_bothSubscribersSeeSameResult() {
        // Simulate the enumeration SELECT: returns [A, B] on first subscribe,
        // empty afterwards — mirrors the pre-fix DB state where deletedContributions
        // ran between the two subscribes.
        AtomicInteger subscribeCount = new AtomicInteger();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        Mono<List<UUID>> targetInvoiceIds = Mono.defer(() -> {
            int n = subscribeCount.incrementAndGet();
            return Mono.just(n == 1 ? List.of(a, b) : List.<UUID>of());
        }).cache();          // ← the fix under test

        // First subscriber (analogue of capturePdfPointers).
        StepVerifier.create(targetInvoiceIds)
                .assertNext(ids -> assertThat(ids).containsExactly(a, b))
                .verifyComplete();

        // Second subscriber (analogue of deletedInvoices) MUST see the same
        // enumeration even though the underlying "SELECT" would now return
        // empty. This is the critical guarantee.
        StepVerifier.create(targetInvoiceIds)
                .assertNext(ids -> assertThat(ids).containsExactly(a, b))
                .verifyComplete();

        // And the underlying producer must have been triggered exactly once.
        assertThat(subscribeCount.get())
                .as("cached Mono must not re-invoke its source on subsequent subscribes")
                .isEqualTo(1);
    }

    /**
     * Negative characterisation: proves that <em>without</em> {@code .cache()}
     * the second subscriber sees the (now-empty) result. This is what the bug
     * looked like in production — the enumeration ran after
     * {@code deletedContributions} had wiped the join input, so group invoices
     * dropped out of the ID list and the {@code DELETE FROM invoices WHERE id
     * IN (:ids)} statement received an empty list. The invoice survived.
     *
     * <p>Kept in the suite as an explicit anti-example so a future edit that
     * "simplifies" the chain by removing {@code .cache()} can see, in one
     * assertion, the exact behaviour they'd be re-introducing.
     */
    @Test
    void revokeStyleChain_withoutCache_secondSubscriberSeesEmpty() {
        AtomicInteger subscribeCount = new AtomicInteger();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        Mono<List<UUID>> targetInvoiceIds = Mono.defer(() -> {
            int n = subscribeCount.incrementAndGet();
            return Mono.just(n == 1 ? List.of(a, b) : List.<UUID>of());
        });                   // ← no cache — bug reproducer

        StepVerifier.create(targetInvoiceIds)
                .assertNext(ids -> assertThat(ids).containsExactly(a, b))
                .verifyComplete();

        StepVerifier.create(targetInvoiceIds)
                .assertNext(ids -> assertThat(ids)
                        .as("without cache, a stateful producer leaks its state — this is the bug we fixed")
                        .isEmpty())
                .verifyComplete();

        assertThat(subscribeCount.get()).isEqualTo(2);
    }
}
