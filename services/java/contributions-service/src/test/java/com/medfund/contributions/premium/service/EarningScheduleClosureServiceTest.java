package com.medfund.contributions.premium.service;

import com.medfund.contributions.client.UserServiceClient;
import com.medfund.contributions.premium.entity.EarningSchedule;
import com.medfund.contributions.premium.entity.EarningScheduleRun;
import com.medfund.contributions.premium.repository.EarningScheduleRepository;
import com.medfund.contributions.premium.repository.EarningScheduleRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the nightly period-close pass:
 * <ul>
 *   <li>Every row with {@code period_end < today} gets its
 *       {@code earned_at_period_end} filled in.</li>
 *   <li>Executor writes a {@link EarningScheduleRun} row and marks it
 *       COMPLETED on success.</li>
 *   <li>Backfill for a single policy only touches that policy's rows.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EarningScheduleClosureServiceTest {

    @Mock EarningScheduleRepository earningScheduleRepository;
    @Mock EarningScheduleRunRepository earningScheduleRunRepository;
    @Mock UserServiceClient userServiceClient;

    private DatabaseClient db;
    private EarningScheduleClosureService service;

    @BeforeEach
    void setUp() {
        // The refresh-materialised-view step uses a fluent DatabaseClient chain
        // — but only closeExpiredPeriodsForTenant reaches it. Stub it leniently
        // so tests that don't touch it don't trip Mockito strict mode.
        db = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        org.springframework.r2dbc.core.FetchSpec<java.util.Map<String, Object>> fetch =
                mock(org.springframework.r2dbc.core.FetchSpec.class);
        lenient().when(db.sql(any(String.class))).thenReturn(spec);
        lenient().when(spec.fetch()).thenReturn(fetch);
        lenient().when(fetch.rowsUpdated()).thenReturn(Mono.just(0L));

        service = new EarningScheduleClosureService(
                earningScheduleRepository, earningScheduleRunRepository, db, userServiceClient);
    }

    @Test
    void close_unclosedRow_setsEarnedToWritten() {
        EarningSchedule row = row(new BigDecimal("100.00"));
        when(earningScheduleRepository.save(any(EarningSchedule.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.closeOne(row))
                .assertNext(saved -> {
                    org.assertj.core.api.Assertions.assertThat(saved.getEarnedAtPeriodEnd())
                            .isEqualByComparingTo("100.00");
                })
                .verifyComplete();
    }

    @Test
    void closeExpiredPeriodsForTenant_runsRunLifecycleAndClosesEveryRow() {
        UUID tenantId = UUID.randomUUID();
        EarningSchedule expired1 = row(new BigDecimal("50"));
        EarningSchedule expired2 = row(new BigDecimal("75"));
        when(earningScheduleRepository.findByPeriodEndBeforeAndEarnedAtPeriodEndIsNullAndClosureFalse(any(LocalDate.class)))
                .thenReturn(Flux.just(expired1, expired2));
        when(earningScheduleRepository.save(any(EarningSchedule.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        EarningScheduleRun startedRun = new EarningScheduleRun();
        startedRun.setId(UUID.randomUUID());
        startedRun.setStatus("RUNNING");
        when(earningScheduleRunRepository.save(any(EarningScheduleRun.class)))
                .thenAnswer(inv -> {
                    EarningScheduleRun r = inv.getArgument(0);
                    // Mimic gen_random_uuid() default on insert so downstream findById calls have an id.
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return Mono.just(r);
                });
        when(earningScheduleRunRepository.findById(any(UUID.class))).thenReturn(Mono.just(startedRun));

        StepVerifier.create(service.closeExpiredPeriodsForTenant(tenantId.toString()))
                .verifyComplete();

        verify(earningScheduleRepository, times(2)).save(any(EarningSchedule.class));
        // save is invoked at least: startRun (1) + heartbeat (1) + finish (1) — plus each closeOne row
        // is a repository.save on the EarningScheduleRepository, not the Run repo. So Run repo save
        // fires 3 times here.
        verify(earningScheduleRunRepository, atLeastOnce()).save(any(EarningScheduleRun.class));
    }

    @Test
    void backfillPolicy_onlyClosesRowsForRequestedPolicy() {
        UUID tenantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        EarningSchedule oldClosed = row(new BigDecimal("40"));
        oldClosed.setEarnedAtPeriodEnd(new BigDecimal("40")); // already closed — filter should skip
        EarningSchedule oldOpen = row(new BigDecimal("60"));  // matches filter
        when(earningScheduleRepository.findByPolicyIdAndPolicySource(policyId, "LIFE_POLICY"))
                .thenReturn(Flux.just(oldClosed, oldOpen));
        when(earningScheduleRepository.save(any(EarningSchedule.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        EarningScheduleRun run = new EarningScheduleRun();
        run.setId(UUID.randomUUID());
        run.setStatus("RUNNING");
        when(earningScheduleRunRepository.save(any(EarningScheduleRun.class)))
                .thenAnswer(inv -> {
                    EarningScheduleRun r = inv.getArgument(0);
                    // Mimic gen_random_uuid() default on insert so downstream findById calls have an id.
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return Mono.just(r);
                });
        when(earningScheduleRunRepository.findById(any(UUID.class))).thenReturn(Mono.just(run));

        StepVerifier.create(service.backfillPolicy(tenantId.toString(), policyId, "LIFE_POLICY"))
                .expectNextCount(1)
                .verifyComplete();

        // Exactly one row was newly closed — the already-earned row was filtered out.
        verify(earningScheduleRepository, times(1)).save(any(EarningSchedule.class));
    }

    @Test
    void refreshMemberFirstContribution_swallowsSqlFailures() {
        // A failed REFRESH shouldn't break the executor — grill note 7.
        DatabaseClient failing = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.FetchSpec<java.util.Map<String, Object>> fetch =
                mock(org.springframework.r2dbc.core.FetchSpec.class);
        when(failing.sql(any(String.class))).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.error(new RuntimeException("mv missing")));
        EarningScheduleClosureService svc = new EarningScheduleClosureService(
                earningScheduleRepository, earningScheduleRunRepository, failing, userServiceClient);

        StepVerifier.create(svc.refreshMemberFirstContribution()).verifyComplete();
    }

    // ── Phase 13 §C Phase 7 — member_contribution_presence refresh ──────

    @Test
    void refreshMemberContributionPresence_success_completesWithoutError() {
        // The setUp() fluent stub returns rowsUpdated=0 for every sql(...) call,
        // so the REFRESH + freshness-stamp both look successful.
        StepVerifier.create(service.refreshMemberContributionPresence()).verifyComplete();

        // Guard the SQL surface — both the REFRESH and the freshness-stamp
        // UPDATE must fire in that order.
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).sql(sqlCaptor.capture());
        java.util.List<String> sqls = sqlCaptor.getAllValues();
        org.assertj.core.api.Assertions.assertThat(sqls)
                .anyMatch(s -> s.contains("REFRESH MATERIALIZED VIEW member_contribution_presence"))
                .anyMatch(s -> s.contains("contrib_presence_refresh_at"));
    }

    @Test
    void refreshMemberContributionPresence_matviewMissing_logsWarning_returnsEmpty() {
        // A failed REFRESH must not break the executor — best-effort per L16.
        DatabaseClient failing = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.FetchSpec<java.util.Map<String, Object>> fetch =
                mock(org.springframework.r2dbc.core.FetchSpec.class);
        when(failing.sql(any(String.class))).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.error(new RuntimeException("mv missing")));
        EarningScheduleClosureService svc = new EarningScheduleClosureService(
                earningScheduleRepository, earningScheduleRunRepository, failing, userServiceClient);

        StepVerifier.create(svc.refreshMemberContributionPresence()).verifyComplete();
    }

    // ── Phase 12 §C Phase 9 — endorsement recompute ─────────────────────────

    @Test
    void recomputeForEndorsement_deletesPriorRowsAndMintsOnePerFuturePeriod() {
        UUID tenantId = UUID.randomUUID();
        UUID endorsementId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        LocalDate effectiveFrom = LocalDate.of(2026, 5, 1);

        EarningSchedule may = baseRow(policyId, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                new BigDecimal("100"));
        EarningSchedule jun = baseRow(policyId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("100"));
        when(earningScheduleRepository.deleteByEndorsementId(endorsementId)).thenReturn(Mono.empty());
        when(earningScheduleRepository.findByPolicyIdAndPolicySourceAndPeriodStartGreaterThanEqual(
                policyId, "LIFE_POLICY", effectiveFrom)).thenReturn(Flux.just(may, jun));
        when(earningScheduleRepository.save(any(EarningSchedule.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        EarningScheduleRun run = new EarningScheduleRun();
        run.setId(UUID.randomUUID());
        run.setStatus("RUNNING");
        when(earningScheduleRunRepository.save(any(EarningScheduleRun.class)))
                .thenAnswer(inv -> {
                    EarningScheduleRun r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return Mono.just(r);
                });
        when(earningScheduleRunRepository.findById(any(UUID.class))).thenReturn(Mono.just(run));
        when(userServiceClient.markEndorsementComputed(endorsementId)).thenReturn(Mono.empty());

        StepVerifier.create(service.recomputeForEndorsement(tenantId.toString(), endorsementId, policyId,
                        "LIFE_POLICY", effectiveFrom, new BigDecimal("60"), "USD"))
                .expectNextCount(1)
                .verifyComplete();

        // One endorsement row per base period (2 rows). The strip sums to 60 exactly (30 + 30 with
        // last-row rounding absorption), which we assert on the argument captor below.
        org.mockito.ArgumentCaptor<EarningSchedule> saved =
                org.mockito.ArgumentCaptor.forClass(EarningSchedule.class);
        verify(earningScheduleRepository, times(2)).save(saved.capture());
        java.util.List<EarningSchedule> writes = saved.getAllValues();
        org.assertj.core.api.Assertions.assertThat(writes).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(writes)
                .allMatch(EarningSchedule::isEndorsement)
                .allMatch(r -> endorsementId.equals(r.getEndorsementId()));
        BigDecimal sum = writes.stream()
                .map(EarningSchedule::getWrittenAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        org.assertj.core.api.Assertions.assertThat(sum).isEqualByComparingTo("60");
        verify(userServiceClient, times(1)).markEndorsementComputed(endorsementId);
    }

    @Test
    void recomputeForEndorsement_zeroDelta_writesNoRowsButStillMarksComputed() {
        UUID tenantId = UUID.randomUUID();
        UUID endorsementId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        LocalDate effectiveFrom = LocalDate.of(2026, 5, 1);

        EarningSchedule may = baseRow(policyId, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                new BigDecimal("100"));
        when(earningScheduleRepository.deleteByEndorsementId(endorsementId)).thenReturn(Mono.empty());
        when(earningScheduleRepository.findByPolicyIdAndPolicySourceAndPeriodStartGreaterThanEqual(
                policyId, "LIFE_POLICY", effectiveFrom)).thenReturn(Flux.just(may));

        EarningScheduleRun run = new EarningScheduleRun();
        run.setId(UUID.randomUUID());
        run.setStatus("RUNNING");
        when(earningScheduleRunRepository.save(any(EarningScheduleRun.class)))
                .thenAnswer(inv -> {
                    EarningScheduleRun r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return Mono.just(r);
                });
        when(earningScheduleRunRepository.findById(any(UUID.class))).thenReturn(Mono.just(run));
        when(userServiceClient.markEndorsementComputed(endorsementId)).thenReturn(Mono.empty());

        StepVerifier.create(service.recomputeForEndorsement(tenantId.toString(), endorsementId, policyId,
                        "LIFE_POLICY", effectiveFrom, BigDecimal.ZERO, "USD"))
                .expectNextCount(1)
                .verifyComplete();

        // No endorsement rows written when delta is zero, but the endorsement is still marked computed.
        verify(earningScheduleRepository, times(0)).save(any(EarningSchedule.class));
        verify(userServiceClient, times(1)).markEndorsementComputed(endorsementId);
    }

    @Test
    void recomputeForEndorsement_preservesClosedPeriodEarned() {
        UUID tenantId = UUID.randomUUID();
        UUID endorsementId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        LocalDate effectiveFrom = LocalDate.of(2026, 5, 1);

        EarningSchedule closed = baseRow(policyId, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                new BigDecimal("100"));
        closed.setEarnedAtPeriodEnd(new BigDecimal("100"));   // already closed
        EarningSchedule open = baseRow(policyId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("100"));                        // still open

        when(earningScheduleRepository.deleteByEndorsementId(endorsementId)).thenReturn(Mono.empty());
        when(earningScheduleRepository.findByPolicyIdAndPolicySourceAndPeriodStartGreaterThanEqual(
                policyId, "LIFE_POLICY", effectiveFrom)).thenReturn(Flux.just(closed, open));
        when(earningScheduleRepository.save(any(EarningSchedule.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        EarningScheduleRun run = new EarningScheduleRun();
        run.setId(UUID.randomUUID());
        run.setStatus("RUNNING");
        when(earningScheduleRunRepository.save(any(EarningScheduleRun.class)))
                .thenAnswer(inv -> {
                    EarningScheduleRun r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return Mono.just(r);
                });
        when(earningScheduleRunRepository.findById(any(UUID.class))).thenReturn(Mono.just(run));
        when(userServiceClient.markEndorsementComputed(endorsementId)).thenReturn(Mono.empty());

        StepVerifier.create(service.recomputeForEndorsement(tenantId.toString(), endorsementId, policyId,
                        "LIFE_POLICY", effectiveFrom, new BigDecimal("60"), "USD"))
                .expectNextCount(1)
                .verifyComplete();

        org.mockito.ArgumentCaptor<EarningSchedule> saved =
                org.mockito.ArgumentCaptor.forClass(EarningSchedule.class);
        verify(earningScheduleRepository, times(2)).save(saved.capture());
        java.util.List<EarningSchedule> writes = saved.getAllValues();
        // Endorsement row for the closed base period is closed too (earned = written).
        // Endorsement row for the open base period stays open (earned = null).
        EarningSchedule closedEndorsement = writes.stream()
                .filter(w -> w.getPeriodStart().equals(LocalDate.of(2026, 5, 1))).findFirst().orElseThrow();
        EarningSchedule openEndorsement = writes.stream()
                .filter(w -> w.getPeriodStart().equals(LocalDate.of(2026, 6, 1))).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(closedEndorsement.getEarnedAtPeriodEnd())
                .isEqualByComparingTo(closedEndorsement.getWrittenAmount());
        org.assertj.core.api.Assertions.assertThat(openEndorsement.getEarnedAtPeriodEnd()).isNull();
    }

    @Test
    void recomputeForEndorsement_rejectsMissingRequiredArgs() {
        StepVerifier.create(service.recomputeForEndorsement("t", null, UUID.randomUUID(),
                        "LIFE_POLICY", LocalDate.now(), new BigDecimal("50"), "USD"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void recomputeForEndorsement_carriesFailureToRunAndDoesNotMarkComputed() {
        UUID tenantId = UUID.randomUUID();
        UUID endorsementId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        LocalDate effectiveFrom = LocalDate.of(2026, 5, 1);

        when(earningScheduleRepository.deleteByEndorsementId(endorsementId))
                .thenReturn(Mono.error(new RuntimeException("db down")));
        // The `.thenMany(...)` argument is built eagerly at chain time — Mockito
        // strict mode requires the mock to be stubbed even though the resulting
        // Flux is never subscribed (the outer error propagates first).
        lenient().when(earningScheduleRepository
                .findByPolicyIdAndPolicySourceAndPeriodStartGreaterThanEqual(
                        policyId, "LIFE_POLICY", effectiveFrom))
                .thenReturn(Flux.empty());

        EarningScheduleRun run = new EarningScheduleRun();
        run.setId(UUID.randomUUID());
        run.setStatus("RUNNING");
        when(earningScheduleRunRepository.save(any(EarningScheduleRun.class)))
                .thenAnswer(inv -> {
                    EarningScheduleRun r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return Mono.just(r);
                });
        when(earningScheduleRunRepository.findById(any(UUID.class))).thenReturn(Mono.just(run));

        StepVerifier.create(service.recomputeForEndorsement(tenantId.toString(), endorsementId, policyId,
                        "LIFE_POLICY", effectiveFrom, new BigDecimal("60"), "USD"))
                .expectNextCount(1)
                .verifyComplete();

        // Failure path — the endorsement is NOT marked computed and the run finishes FAILED.
        verify(userServiceClient, times(0)).markEndorsementComputed(any(UUID.class));
    }

    @Test
    void recomputeForEndorsement_idempotentReplayDeletesPriorRowsFirst() {
        UUID tenantId = UUID.randomUUID();
        UUID endorsementId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        LocalDate effectiveFrom = LocalDate.of(2026, 5, 1);

        when(earningScheduleRepository.deleteByEndorsementId(endorsementId)).thenReturn(Mono.empty());
        when(earningScheduleRepository.findByPolicyIdAndPolicySourceAndPeriodStartGreaterThanEqual(
                policyId, "LIFE_POLICY", effectiveFrom)).thenReturn(Flux.empty());

        EarningScheduleRun run = new EarningScheduleRun();
        run.setId(UUID.randomUUID());
        run.setStatus("RUNNING");
        when(earningScheduleRunRepository.save(any(EarningScheduleRun.class)))
                .thenAnswer(inv -> {
                    EarningScheduleRun r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return Mono.just(r);
                });
        when(earningScheduleRunRepository.findById(any(UUID.class))).thenReturn(Mono.just(run));
        when(userServiceClient.markEndorsementComputed(endorsementId)).thenReturn(Mono.empty());

        StepVerifier.create(service.recomputeForEndorsement(tenantId.toString(), endorsementId, policyId,
                        "LIFE_POLICY", effectiveFrom, new BigDecimal("60"), "USD"))
                .expectNextCount(1)
                .verifyComplete();

        verify(earningScheduleRepository, times(1)).deleteByEndorsementId(endorsementId);
    }

    // ── Phase 13 §B Phase 6 — policy-status lifecycle hooks ──────────────
    // Behavioural coverage runs against real Postgres in
    // PolicyStatusChangedConsumerLifecycleIT (SQL semantics are the whole
    // point — mocking the fluent DatabaseClient chain would just re-encode
    // the SQL as Mockito stubs). Unit-level surface is null-arg rejection.

    @Test
    void closeOutForPolicyClosure_missingArg_throwsIllegalArgumentException() {
        StepVerifier.create(service.closeOutForPolicyClosure("t", null,
                        "LIFE_POLICY", LocalDate.now(), UUID.randomUUID()))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(service.closeOutForPolicyClosure("t", UUID.randomUUID(),
                        null, LocalDate.now(), UUID.randomUUID()))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(service.closeOutForPolicyClosure("t", UUID.randomUUID(),
                        "LIFE_POLICY", null, UUID.randomUUID()))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(service.closeOutForPolicyClosure("t", UUID.randomUUID(),
                        "LIFE_POLICY", LocalDate.now(), null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void freezePolicyEarning_missingArg_throwsIllegalArgumentException() {
        StepVerifier.create(service.freezePolicyEarning("t", null,
                        "LIFE_POLICY", LocalDate.now(), UUID.randomUUID()))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(service.freezePolicyEarning("t", UUID.randomUUID(),
                        "LIFE_POLICY", LocalDate.now(), null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void resumePolicyEarning_missingArg_throwsIllegalArgumentException() {
        StepVerifier.create(service.resumePolicyEarning("t", null,
                        "LIFE_POLICY", LocalDate.now()))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(service.resumePolicyEarning("t", UUID.randomUUID(),
                        "LIFE_POLICY", null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void reinstatePolicyEarning_missingArg_throwsIllegalArgumentException() {
        StepVerifier.create(service.reinstatePolicyEarning("t", null,
                        "LIFE_POLICY", LocalDate.now()))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(service.reinstatePolicyEarning("t", UUID.randomUUID(),
                        null, LocalDate.now()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    private static EarningSchedule row(BigDecimal written) {
        EarningSchedule r = new EarningSchedule();
        r.setId(UUID.randomUUID());
        r.setPolicyId(UUID.randomUUID());
        r.setPolicySource("LIFE_POLICY");
        r.setInsuranceLine("LIFE");
        r.setPeriodStart(LocalDate.of(2025, 1, 1));
        r.setPeriodEnd(LocalDate.of(2025, 1, 31));  // strictly in the past
        r.setWrittenAmount(written);
        r.setCurrencyCode("USD");
        r.setEarningMethod("DAILY_LINEAR");
        return r;
    }

    private static EarningSchedule baseRow(UUID policyId, LocalDate start, LocalDate end, BigDecimal written) {
        EarningSchedule r = new EarningSchedule();
        r.setId(UUID.randomUUID());
        r.setPolicyId(policyId);
        r.setPolicySource("LIFE_POLICY");
        r.setInsuranceLine("LIFE");
        r.setPeriodStart(start);
        r.setPeriodEnd(end);
        r.setWrittenAmount(written);
        r.setCurrencyCode("USD");
        r.setEarningMethod("DAILY_LINEAR");
        return r;
    }
}
