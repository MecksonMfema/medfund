package com.medfund.claims.siu.scheduler;

import com.medfund.claims.siu.repository.FraudFlagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-tests {@link FraudFlagRetentionJob#runOnce()} with a mock repository —
 * asserts the SQL cutoff hands the repo an OffsetDateTime approximately one
 * year in the past. Cron auto-fire is orthogonal to this test (it's a
 * Spring-scheduled concern); direct {@code runOnce()} invocation is the
 * package-private hook the Phase 2 plan calls out.
 */
class FraudFlagRetentionJobTest {

    private FraudFlagRepository repo;
    private FraudFlagRetentionJob job;

    @BeforeEach
    void setUp() {
        repo = mock(FraudFlagRepository.class);
        job = new FraudFlagRetentionJob(repo);
    }

    @Test
    void runOnce_purgesUnlinkedRowsOlderThan1Year() {
        when(repo.purgeUnlinkedOlderThan(any(OffsetDateTime.class))).thenReturn(Mono.just(42L));

        StepVerifier.create(job.runOnce())
                .expectNext(42L)
                .verifyComplete();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        org.mockito.Mockito.verify(repo).purgeUnlinkedOlderThan(cutoff.capture());
        OffsetDateTime expected = OffsetDateTime.now().minus(1, ChronoUnit.YEARS);
        // ± 5 seconds tolerance for wall-clock drift between the assertion & the call.
        assertThat(cutoff.getValue()).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void runOnce_zeroDeletions_completesWith0() {
        when(repo.purgeUnlinkedOlderThan(any(OffsetDateTime.class))).thenReturn(Mono.just(0L));

        StepVerifier.create(job.runOnce())
                .expectNext(0L)
                .verifyComplete();
    }
}
