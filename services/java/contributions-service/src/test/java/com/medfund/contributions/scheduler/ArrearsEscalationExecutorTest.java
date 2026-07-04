package com.medfund.contributions.scheduler;

import com.medfund.contributions.client.UserServiceClient;
import com.medfund.contributions.dto.BadDebtRow;
import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.entity.DunningConfig;
import com.medfund.contributions.job.ArrearsEscalationExecutor;
import com.medfund.contributions.repository.DunningConfigRepository;
import com.medfund.contributions.service.ArrearsNoticePublisher;
import com.medfund.contributions.service.BalanceService;
import com.medfund.shared.scheduler.JobType;
import io.r2dbc.spi.Readable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArrearsEscalationExecutorTest {

    @Mock DunningConfigRepository dunningRepo;
    @Mock BalanceService balanceService;
    @Mock UserServiceClient userClient;
    @Mock ArrearsNoticePublisher arrearsPublisher;
    @Mock DatabaseClient db;
    @Mock com.medfund.contributions.service.BadDebtService badDebtService;

    private ArrearsEscalationExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ArrearsEscalationExecutor(dunningRepo, balanceService,
                userClient, arrearsPublisher, db, badDebtService);
        stubActiveCurrencies("USD");
        // WRITE_OFF branch now chains flagAndWriteOffAggregate — stub as
        // no-op so existing tests keep passing; the write-off test
        // asserts on the actual invocation.
        lenient().when(badDebtService.flagAndWriteOffAggregate(
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    @Test
    void jobType_matchesEnum() {
        assertThat(executor.getJobType()).isEqualTo(JobType.ARREARS_ESCALATION);
    }

    @Test
    void noConfig_noAction() {
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.empty());
        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();
        verify(userClient, never()).suspendMember(any(), any(), any());
    }

    @Test
    void bothAutoFlagsOff_earlyExit() {
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID))
                .thenReturn(Mono.just(config(false, false, false, 30, 90, 7, 3, true)));
        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();
        verify(balanceService, never()).listAged(any(), any(), any(), anyInt(), anyInt());
        verify(userClient, never()).suspendMember(any(), any(), any());
    }

    @Test
    void suspendedBucket_autoSuspendOn_callsSuspendMember() {
        DunningConfig cfg = config(true, false, false, 30, 90, 7, 3, true);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.just(cfg));
        UUID memberId = UUID.randomUUID();
        BadDebtRow row = row("MEMBER", memberId, "SUSPENDED", 40);
        stubListAged(row);
        when(userClient.suspendMember(eq(memberId), isNull(), eq("ARREARS_ESCALATION")))
                .thenReturn(Mono.empty());
        stubReactivatePhase();

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(userClient).suspendMember(eq(memberId), isNull(), eq("ARREARS_ESCALATION"));
        verify(userClient, never()).deactivateMember(any(), any(), any());
    }

    @Test
    void writeOffBucket_autoWriteOffOn_callsDeactivateMember_andFlagsWrittenOff() {
        DunningConfig cfg = config(true, true, false, 30, 90, 7, 3, true);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.just(cfg));
        UUID memberId = UUID.randomUUID();
        BadDebtRow row = row("MEMBER", memberId, "WRITE_OFF", 120);
        stubListAged(row);
        when(userClient.deactivateMember(eq(memberId), isNull(), eq("ARREARS_ESCALATION")))
                .thenReturn(Mono.empty());
        // Reactivate phase runs (autoSuspend true) — return empty listings.
        stubReactivatePhase();

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(userClient).deactivateMember(eq(memberId), isNull(), eq("ARREARS_ESCALATION"));
        verify(userClient, never()).suspendMember(any(), any(), any());
        // The auto-write-off leg must also flag + write off the debt so
        // the running balance zeros AND the bad_debts row exists for
        // future risk scoring. Fix landed after a 2026-07 audit found
        // this branch was deactivating without a ledger entry.
        verify(badDebtService).flagAndWriteOffAggregate(
                eq("MEMBER"), eq(memberId),
                eq("USD"), eq(new BigDecimal("125.00")),
                eq("AUTO_ARREARS_WRITE_OFF"),
                eq(com.medfund.shared.audit.AuditActor.SYSTEM_ID),
                eq(com.medfund.shared.audit.AuditActor.SYSTEM_EMAIL));
    }

    @Test
    void groupSubject_routesToGroupEndpoints() {
        DunningConfig cfg = config(true, false, false, 30, 90, 7, 3, true);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.just(cfg));
        UUID groupId = UUID.randomUUID();
        BadDebtRow row = row("GROUP", groupId, "SUSPENDED", 45);
        stubListAged(row);
        when(userClient.suspendGroup(eq(groupId), isNull(), eq("ARREARS_ESCALATION")))
                .thenReturn(Mono.empty());
        stubReactivatePhase();

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(userClient).suspendGroup(eq(groupId), isNull(), eq("ARREARS_ESCALATION"));
        verify(userClient, never()).suspendMember(any(), any(), any());
    }

    @Test
    void graceBucket_autoRemindOn_atTick_publishesReminder() {
        // suspensionDays=30, leadDays=7, interval=3 → first tick at day 23, then 26, 29.
        DunningConfig cfg = config(true, false, true, 30, 90, 7, 3, true);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.just(cfg));
        UUID memberId = UUID.randomUUID();
        BadDebtRow row = row("MEMBER", memberId, "GRACE", 23);
        stubListAged(row);
        when(arrearsPublisher.publish(any())).thenReturn(Mono.empty());
        stubReactivatePhase();

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        ArgumentCaptor<ArrearsNoticePublisher.ArrearsNoticePayload> cap =
                ArgumentCaptor.forClass(ArrearsNoticePublisher.ArrearsNoticePayload.class);
        verify(arrearsPublisher).publish(cap.capture());
        assertThat(cap.getValue().kind()).isEqualTo(ArrearsNoticePublisher.KIND_REMINDER_PRE_SUSPENSION);
        assertThat(cap.getValue().nextStep()).isEqualTo("SUSPENSION");
    }

    @Test
    void graceBucket_autoRemindOn_offTick_noReminder() {
        DunningConfig cfg = config(true, false, true, 30, 90, 7, 3, true);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.just(cfg));
        UUID memberId = UUID.randomUUID();
        BadDebtRow row = row("MEMBER", memberId, "GRACE", 24); // not a tick
        stubListAged(row);
        stubReactivatePhase();

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(arrearsPublisher, never()).publish(any());
    }

    @Test
    void suspendedBucket_reminderContinueOff_noReminder() {
        // autoSuspend on so we reach processRow, but reminderContinuePastSuspension=false
        // → the SUSPENDED-bucket reminder branch is silenced.
        DunningConfig cfg = config(true, false, true, 30, 90, 7, 3, false);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.just(cfg));
        UUID memberId = UUID.randomUUID();
        BadDebtRow row = row("MEMBER", memberId, "SUSPENDED", 45);
        stubListAged(row);
        // SUSPENDED bucket + autoSuspend on → executor still calls suspendMember;
        // the point of this test is that no reminder fires.
        when(userClient.suspendMember(eq(memberId), isNull(), eq("ARREARS_ESCALATION")))
                .thenReturn(Mono.empty());
        stubReactivatePhase();

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(arrearsPublisher, never()).publish(any());
    }

    @Test
    void reactivate_clearedArrears_flipsMembersAndGroupsBackToActive() {
        // autoSuspend=true so reactivate phase runs. Empty aged listing so
        // the escalate phase is a no-op, keeping the test focused on the
        // reactivation branch.
        DunningConfig cfg = config(true, false, false, 30, 90, 7, 3, true);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.just(cfg));
        when(balanceService.listAged(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(PageResponse.of(List.of(), 0, 0, 200)));
        UUID clearedMember = UUID.randomUUID();
        UUID stillAgedMember = UUID.randomUUID();
        UUID clearedGroup = UUID.randomUUID();
        // aged view returns stillAgedMember only — clearedMember is not aged anymore.
        when(balanceService.currentlyAgedSubjectIds())
                .thenReturn(Mono.just(Set.of(stillAgedMember)));
        when(userClient.listMembersSuspendedForReason(eq("ARREARS_ESCALATION")))
                .thenReturn(Flux.just(clearedMember, stillAgedMember));
        when(userClient.listGroupsSuspendedForReason(eq("ARREARS_ESCALATION")))
                .thenReturn(Flux.just(clearedGroup));
        when(userClient.reactivateMember(eq(clearedMember), eq("ARREARS_CLEARED")))
                .thenReturn(Mono.empty());
        when(userClient.reactivateGroup(eq(clearedGroup), eq("ARREARS_CLEARED")))
                .thenReturn(Mono.empty());

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(userClient).reactivateMember(eq(clearedMember), eq("ARREARS_CLEARED"));
        verify(userClient, never()).reactivateMember(eq(stillAgedMember), any());
        verify(userClient).reactivateGroup(eq(clearedGroup), eq("ARREARS_CLEARED"));
    }

    @SuppressWarnings("unchecked")
    private void stubActiveCurrencies(String... currencies) {
        DatabaseClient.GenericExecuteSpec spec = org.mockito.Mockito.mock(
                DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<String> fetch =
                org.mockito.Mockito.mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(db.sql(any(String.class))).thenReturn(spec);
        lenient().when(spec.map(any(Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.all()).thenReturn(Flux.just(currencies));
    }

    private void stubListAged(BadDebtRow row) {
        when(balanceService.listAged(any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    int page = inv.getArgument(3);
                    // First page returns the row, subsequent pages return empty.
                    if (page == 0) {
                        return Mono.just(PageResponse.of(List.of(row), 1, 0, 200));
                    }
                    return Mono.just(PageResponse.of(List.of(), 1, page, 200));
                });
    }

    private void stubReactivatePhase() {
        lenient().when(balanceService.currentlyAgedSubjectIds()).thenReturn(Mono.just(Set.of()));
        lenient().when(userClient.listMembersSuspendedForReason(any())).thenReturn(Flux.empty());
        lenient().when(userClient.listGroupsSuspendedForReason(any())).thenReturn(Flux.empty());
    }

    private static DunningConfig config(boolean autoSuspend, boolean autoWriteOff,
                                          boolean autoRemind, int suspensionDays,
                                          int deactivationDays,
                                          int leadDays, int intervalDays,
                                          boolean continuePastSuspension) {
        DunningConfig c = new DunningConfig();
        c.setId(DunningConfig.SINGLETON_ID);
        c.setGraceDays(5);
        c.setSuspensionDays(suspensionDays);
        c.setDeactivationDays(deactivationDays);
        c.setAutoSuspend(autoSuspend);
        c.setAutoWriteOff(autoWriteOff);
        c.setAutoRemind(autoRemind);
        c.setReminderLeadDays(leadDays);
        c.setReminderIntervalDays(intervalDays);
        c.setReminderContinuePastSuspension(continuePastSuspension);
        return c;
    }

    private static BadDebtRow row(String subjectType, UUID subjectId, String bucket, long daysOverdue) {
        return new BadDebtRow(
                subjectType, subjectId, "CODE-1", "Subject Name", "email@x.com",
                "USD", new BigDecimal("125.00"),
                null, null,
                daysOverdue, bucket);
    }

    // Bridge — some Readable-based Mockito plumbing wants a class ref.
    @SuppressWarnings("unused")
    private static Class<Readable> readableRef() { return Readable.class; }
}
