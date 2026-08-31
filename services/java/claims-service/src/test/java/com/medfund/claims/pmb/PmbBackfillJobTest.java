package com.medfund.claims.pmb;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PmbBackfillJob}. Covers chunk pagination, idempotency
 * on second run, classifier-error tolerance, and audit-event shape (Phase 16
 * §B REG7 success criterion: chunking + idempotency).
 */
class PmbBackfillJobTest {

    private static final String TENANT = "tenant-A";
    private static final String ACTOR = UUID.randomUUID().toString();
    private static final String ACTOR_EMAIL = "admin@example.com";

    private ClaimRepository claimRepository;
    private PmbClassifier classifier;
    private AuditPublisher auditPublisher;
    private PmbBackfillJob job;

    @BeforeEach
    void setUp() {
        claimRepository = mock(ClaimRepository.class);
        classifier = mock(PmbClassifier.class);
        auditPublisher = mock(AuditPublisher.class);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(claimRepository.save(any(Claim.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        job = new PmbBackfillJob(claimRepository, classifier, auditPublisher);
    }

    // ── Chunking ────────────────────────────────────────────────────────────

    @Test
    void run_iteratesEveryChunk_untilEmptyResult() {
        Map<UUID, Claim> table = seedClaims(2500);
        stubKeysetPagination(table, 1000);
        when(classifier.classify(any())).thenReturn(Mono.just(PmbClassification.NOT_PMB));

        StepVerifier.create(job.run(TENANT, ACTOR, ACTOR_EMAIL))
                .assertNext(r -> {
                    assertThat(r.processed()).isEqualTo(2500);
                    assertThat(r.classified()).isZero();
                })
                .verifyComplete();

        verify(claimRepository, times(4)).findChunkAfterId(any(), anyInt());
    }

    @Test
    void run_respectsCustomChunkSize() {
        Map<UUID, Claim> table = seedClaims(5);
        stubKeysetPagination(table, 2);
        when(classifier.classify(any())).thenReturn(Mono.just(PmbClassification.NOT_PMB));

        StepVerifier.create(job.run(TENANT, ACTOR, ACTOR_EMAIL, 2))
                .assertNext(r -> assertThat(r.processed()).isEqualTo(5))
                .verifyComplete();

        // 5 rows with chunkSize=2 → chunks of 2,2,1, then a final empty call.
        verify(claimRepository, times(4)).findChunkAfterId(any(), anyInt());
    }

    @Test
    void run_rejectsNonPositiveChunkSize() {
        StepVerifier.create(job.run(TENANT, ACTOR, ACTOR_EMAIL, 0))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void run_zeroClaims_completesWithZeroCounts() {
        when(claimRepository.findChunkAfterId(any(), anyInt())).thenReturn(Flux.empty());

        StepVerifier.create(job.run(TENANT, ACTOR, ACTOR_EMAIL))
                .assertNext(r -> {
                    assertThat(r.processed()).isZero();
                    assertThat(r.classified()).isZero();
                })
                .verifyComplete();

        verify(auditPublisher, never()).publish(any());
    }

    // ── Classification writes ───────────────────────────────────────────────

    @Test
    void run_writesClassificationAndAudit_forNewPmbMatches() {
        Map<UUID, Claim> table = seedClaims(3);
        stubKeysetPagination(table, 10);
        List<Claim> ordered = new ArrayList<>(table.values());
        ordered.sort((a, b) -> byteCompare(a.getId(), b.getId()));

        when(classifier.classify(any())).thenAnswer(inv -> {
            Claim c = inv.getArgument(0);
            return Mono.just(c.getId().equals(ordered.get(0).getId())
                    ? PmbClassification.pmb("PMB-001")
                    : PmbClassification.NOT_PMB);
        });

        StepVerifier.create(job.run(TENANT, ACTOR, ACTOR_EMAIL))
                .assertNext(r -> {
                    assertThat(r.processed()).isEqualTo(3);
                    assertThat(r.classified()).isEqualTo(1);
                })
                .verifyComplete();

        verify(claimRepository, times(1)).save(any(Claim.class));
        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(ev.capture());
        AuditEvent captured = ev.getValue();
        assertThat(captured.actorId()).isEqualTo(ACTOR);
        assertThat(captured.actorEmail()).isEqualTo(ACTOR_EMAIL);
        assertThat(captured.entityType()).isEqualTo("claim.pmb_classification");
        assertThat(captured.entityName()).startsWith("PMB backfill for claim ");
        assertThat(captured.entityName()).doesNotContain(captured.entityId());
        assertThat(captured.action()).isEqualTo("PMB_BACKFILL");
        assertThat(captured.newValue()).containsEntry("isPmb", true);
        assertThat(captured.newValue()).containsEntry("pmbConditionCode", "PMB-001");
        assertThat(captured.oldValue()).containsEntry("isPmb", false);
        assertThat(captured.changedFields()).containsExactly("isPmb", "pmbConditionCode");
    }

    // ── Idempotency ─────────────────────────────────────────────────────────

    @Test
    void run_secondRun_skipsUnchangedRows() {
        Map<UUID, Claim> table = seedClaims(4);
        stubKeysetPagination(table, 10);
        // First run: mark first as PMB, rest not.
        List<Claim> ordered = new ArrayList<>(table.values());
        ordered.sort((a, b) -> byteCompare(a.getId(), b.getId()));
        UUID first = ordered.get(0).getId();
        when(classifier.classify(any())).thenAnswer(inv -> {
            Claim c = inv.getArgument(0);
            return Mono.just(c.getId().equals(first)
                    ? PmbClassification.pmb("PMB-042")
                    : PmbClassification.NOT_PMB);
        });

        // Run once.
        StepVerifier.create(job.run(TENANT, ACTOR, ACTOR_EMAIL))
                .assertNext(r -> assertThat(r.classified()).isEqualTo(1))
                .verifyComplete();

        // Re-run with identical verdicts — nothing should change.
        stubKeysetPagination(table, 10);  // re-stub because Flux.fromIterable was consumed
        StepVerifier.create(job.run(TENANT, ACTOR, ACTOR_EMAIL))
                .assertNext(r -> {
                    assertThat(r.processed()).isEqualTo(4);
                    assertThat(r.classified()).isZero();
                })
                .verifyComplete();

        // Only one save + one audit across both runs (the initial classification).
        verify(claimRepository, times(1)).save(any(Claim.class));
        verify(auditPublisher, times(1)).publish(any());
    }

    @Test
    void run_flipsPmbToNotPmb_whenVerdictChanges() {
        Map<UUID, Claim> table = seedClaims(1);
        Claim only = table.values().iterator().next();
        only.setIsPmb(true);
        only.setPmbConditionCode("PMB-OLD");
        stubKeysetPagination(table, 10);

        when(classifier.classify(any())).thenReturn(Mono.just(PmbClassification.NOT_PMB));

        StepVerifier.create(job.run(TENANT, ACTOR, ACTOR_EMAIL))
                .assertNext(r -> assertThat(r.classified()).isEqualTo(1))
                .verifyComplete();

        assertThat(only.getIsPmb()).isFalse();
        assertThat(only.getPmbConditionCode()).isNull();
    }

    // ── Fault tolerance ─────────────────────────────────────────────────────

    @Test
    void run_classifierError_isSwallowedAndBackfillContinues() {
        Map<UUID, Claim> table = seedClaims(2);
        stubKeysetPagination(table, 10);
        List<Claim> ordered = new ArrayList<>(table.values());
        ordered.sort((a, b) -> byteCompare(a.getId(), b.getId()));

        AtomicInteger call = new AtomicInteger(0);
        when(classifier.classify(any())).thenAnswer(inv -> {
            if (call.getAndIncrement() == 0) {
                return Mono.error(new RuntimeException("engine boom"));
            }
            return Mono.just(PmbClassification.pmb("PMB-002"));
        });

        StepVerifier.create(job.run(TENANT, ACTOR, ACTOR_EMAIL))
                .assertNext(r -> {
                    assertThat(r.processed()).isEqualTo(2);
                    assertThat(r.classified()).isEqualTo(1);
                })
                .verifyComplete();

        // Only the second claim was persisted.
        verify(claimRepository, times(1)).save(any(Claim.class));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Map<UUID, Claim> seedClaims(int count) {
        Map<UUID, Claim> table = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Claim c = new Claim();
            c.setId(UUID.randomUUID());
            c.setClaimNumber("CLM-" + i);
            table.put(c.getId(), c);
        }
        return table;
    }

    /**
     * Stubs {@link ClaimRepository#findChunkAfterId(UUID, int)} to walk the
     * seeded table keyset-style: each call returns up to {@code chunkSize}
     * rows with id > afterId, ordered by id ASC.
     */
    private void stubKeysetPagination(Map<UUID, Claim> table, int chunkSize) {
        List<Claim> ordered = new ArrayList<>(table.values());
        // Java's UUID.compareTo is signed; Postgres orders UUIDs bytewise (unsigned).
        // Match Postgres so the stub reflects prod behaviour.
        ordered.sort((a, b) -> byteCompare(a.getId(), b.getId()));
        when(claimRepository.findChunkAfterId(any(), anyInt())).thenAnswer(inv -> {
            UUID afterId = inv.getArgument(0);
            int size = inv.getArgument(1);
            List<Claim> hit = ordered.stream()
                    .filter(c -> byteCompare(c.getId(), afterId) > 0)
                    .limit(size)
                    .toList();
            return Flux.fromIterable(hit);
        });
    }

    private static int byteCompare(UUID a, UUID b) {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        if (c != 0) return c;
        return Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
