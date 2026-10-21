package com.medfund.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.entity.Ifrs17Cohort;
import com.medfund.user.repository.Ifrs17CohortRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage of the short-circuit paths on
 * {@link Ifrs17CohortLockInService}. The happy-path SQL round-trip is
 * exercised by {@code Ifrs17CohortLockInIT} with a real Postgres — mocking
 * {@link DatabaseClient} at the {@code sql().bind().fetch().rowsUpdated()}
 * fluent-call level would be more brittle than it's worth.
 */
@ExtendWith(MockitoExtension.class)
class Ifrs17CohortLockInServiceTest {

    @Mock private Ifrs17CohortRepository cohortRepository;
    @Mock private DatabaseClient db;
    @Mock private AuditPublisher auditPublisher;

    private Ifrs17CohortLockInService service;
    private final UUID cohortId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new Ifrs17CohortLockInService(cohortRepository, db, new ObjectMapper(), auditPublisher);
    }

    @Test
    void lockInIfFirstPolicy_nullInputs_returnsFalseWithoutTouchingRepository() {
        StepVerifier.create(service.lockInIfFirstPolicy(null, "USD", LocalDate.now()))
                .expectNext(false).verifyComplete();
        StepVerifier.create(service.lockInIfFirstPolicy(cohortId, null, LocalDate.now()))
                .expectNext(false).verifyComplete();
        StepVerifier.create(service.lockInIfFirstPolicy(cohortId, "USD", null))
                .expectNext(false).verifyComplete();

        verify(cohortRepository, never()).findById(any(UUID.class));
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void lockInIfFirstPolicy_alreadyLocked_returnsFalseWithoutSqlOrAudit() {
        Ifrs17Cohort cohort = new Ifrs17Cohort();
        cohort.setId(cohortId);
        cohort.setLockedInAt(Instant.parse("2026-05-01T00:00:00Z"));
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.just(cohort));

        StepVerifier.create(service.lockInIfFirstPolicy(cohortId, "USD", LocalDate.of(2026, 6, 1)))
                .expectNext(false).verifyComplete();

        // Only findById — no SQL, no audit, no idempotency race window.
        verify(cohortRepository).findById(cohortId);
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void lockInIfFirstPolicy_missingCohort_returnsFalseFromDefaultIfEmpty() {
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.empty());

        StepVerifier.create(service.lockInIfFirstPolicy(cohortId, "USD", LocalDate.now()))
                .expectNext(false).verifyComplete();

        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void constructedEntityFields_areReadableByGettersAndSetters() {
        // Belt-and-braces on the entity extension so a rename here (e.g. dropping
        // getLockedInAt) surfaces before it silently breaks the IT.
        Ifrs17Cohort c = new Ifrs17Cohort();
        Instant t = Instant.parse("2026-08-01T00:00:00Z");
        c.setLockedInAt(t);
        c.setLockedInYieldCurveSnapshot("[{\"tenorMonths\":12,\"spotRate\":\"0.075\"}]");
        assertThat(c.getLockedInAt()).isEqualTo(t);
        assertThat(c.getLockedInYieldCurveSnapshot()).contains("0.075");
    }
}
