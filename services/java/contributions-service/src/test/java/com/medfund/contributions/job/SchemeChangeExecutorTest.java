package com.medfund.contributions.job;

import com.medfund.contributions.entity.SchemeChange;
import com.medfund.contributions.repository.SchemeChangeRepository;
import com.medfund.contributions.service.SchemeChangeService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.scheduler.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the daily SCHEME_CHANGE_ROLL sweep:
 *
 * <ul>
 *   <li>Job type wired to the shared enum value.
 *   <li>Each APPROVED-and-due row is applied via {@code makeEffective}
 *       under the {@link AuditActor#SYSTEM_ID system} actor.
 *   <li>A per-row failure is swallowed so the rest of the sweep runs.
 *   <li>An empty batch completes without side effects.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SchemeChangeExecutorTest {

    @Mock SchemeChangeRepository repository;
    @Mock SchemeChangeService service;

    private SchemeChangeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SchemeChangeExecutor(repository, service);
    }

    @Test
    void jobType_matchesEnum() {
        assertThat(executor.getJobType()).isEqualTo(JobType.SCHEME_CHANGE_ROLL);
    }

    @Test
    void noRowsReady_completesWithoutInvokingService() {
        when(repository.findReadyToApply(any(LocalDate.class))).thenReturn(Flux.empty());

        StepVerifier.create(executor.execute("tnt-1", "{}")).verifyComplete();

        verify(service, never()).makeEffective(any(), any(), any());
    }

    @Test
    void everyReadyRowIsAppliedUnderSystemActor() {
        SchemeChange a = approvedRow();
        SchemeChange b = approvedRow();
        when(repository.findReadyToApply(any(LocalDate.class))).thenReturn(Flux.just(a, b));
        when(service.makeEffective(any(), eq(AuditActor.SYSTEM_ID), eq(AuditActor.SYSTEM_EMAIL)))
                .thenAnswer(inv -> Mono.just(approvedRow()));

        StepVerifier.create(executor.execute("tnt-1", "{}")).verifyComplete();

        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        verify(service, times(2)).makeEffective(ids.capture(),
                eq(AuditActor.SYSTEM_ID), eq(AuditActor.SYSTEM_EMAIL));
        assertThat(ids.getAllValues()).containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void perRowFailure_isSwallowed_soRestOfBatchRuns() {
        SchemeChange bad = approvedRow();
        SchemeChange good = approvedRow();
        when(repository.findReadyToApply(any(LocalDate.class))).thenReturn(Flux.just(bad, good));
        when(service.makeEffective(eq(bad.getId()), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("boom")));
        when(service.makeEffective(eq(good.getId()), any(), any()))
                .thenAnswer(inv -> Mono.just(approvedRow()));

        StepVerifier.create(executor.execute("tnt-1", "{}")).verifyComplete();

        verify(service).makeEffective(eq(bad.getId()), any(), any());
        verify(service).makeEffective(eq(good.getId()), any(), any());
    }

    private static SchemeChange approvedRow() {
        SchemeChange sc = new SchemeChange();
        sc.setId(UUID.randomUUID());
        sc.setMemberId(UUID.randomUUID());
        sc.setFromSchemeId(UUID.randomUUID());
        sc.setToSchemeId(UUID.randomUUID());
        sc.setStatus("APPROVED");
        sc.setEffectiveDate(LocalDate.now().withDayOfMonth(1));
        sc.setChangeKind("UPGRADE");
        return sc;
    }
}
